package dev.bre.registry.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and verifies the hash-chained audit log (§5.2, §10.4, FR-AUD-005).
 *
 * <p><b>Separate from {@link AuditLog} on purpose.</b> That class writes and nothing else; this one
 * reads and nothing else. Keeping them apart means no code path holds both an insert and a select
 * against this table, so "could this service rewrite history" is answerable by looking at one
 * forty-line class.
 *
 * <p><b>Every query runs under the tenant's row-level security.</b> There is no cross-tenant audit
 * read, not even for a platform administrator: an administrator of one lender has no business
 * reading another lender's governance record, and the only cross-tenant read in {@code bre_ctl} is
 * the login lookup, which returns no audit data at all.
 */
@Repository
public class AuditQuery {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The largest page this will return, whatever a caller asks for. */
  public static final int MAX_LIMIT = 1000;

  private final JdbcTemplate jdbc;

  /**
   * Creates the queries.
   *
   * @param jdbc the control-plane database
   */
  public AuditQuery(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * One entry.
   *
   * @param seq its position in this tenant's chain, from 1 with no gaps
   * @param occurredAt when, by the database's clock
   * @param actor who
   * @param actorType {@code USER}, {@code SERVICE} or {@code SYSTEM}
   * @param action what, e.g. {@code POLICY_VERSION.APPROVED}
   * @param subjectKind what kind of thing it was done to
   * @param subjectKey which one
   * @param detail the structured detail — never fact values (§9.6)
   * @param entryHash this entry's hash, hex-encoded, so a caller can pin a citation to it
   */
  public record Entry(
      long seq,
      OffsetDateTime occurredAt,
      String actor,
      String actorType,
      String action,
      String subjectKind,
      String subjectKey,
      // A Map, NOT a JsonNode. Spring Boot 4 serialises HTTP responses with Jackson 3
      // (tools.jackson), while the rest of this build reads PSF and IR with Jackson 2 — so a
      // Jackson 2 JsonNode returned from a controller is an unrecognised POJO to Jackson 3, and
      // comes out as {"array":false,"object":true,"nodeType":"OBJECT",…}: every bean-style
      // accessor, and none of the data. Found by asserting on a field of it.
      Map<String, Object> detail,
      // Where the request came from. Null for a SYSTEM entry — there was no caller.
      String sourceIp,
      // The client that sent it. Evidence of WHICH client, never a trusted identity: a caller
      // sets its own User-Agent.
      String client,
      String entryHash) {}

  /**
   * What to return.
   *
   * @param action an action or action prefix, e.g. {@code DEPLOYMENT} or {@code
   *     DEPLOYMENT.APPROVED}
   * @param subjectKind the subject kind
   * @param subjectKey the subject key
   * @param actor the actor
   * @param from inclusive lower bound on {@code occurred_at}
   * @param to inclusive upper bound
   * @param limit how many
   */
  public record Filter(
      String action,
      String subjectKind,
      String subjectKey,
      String actor,
      OffsetDateTime from,
      OffsetDateTime to,
      int limit) {}

  /**
   * What verifying the chain established.
   *
   * @param entries how many entries were walked
   * @param intact whether every recomputed hash matched, and the sequence had no gaps
   * @param firstBrokenSeq the first entry whose hash does not follow from its predecessor
   * @param firstGapAfterSeq the entry after which a sequence number is missing
   * @param headHash the last entry's hash, hex-encoded — the value to record externally if this
   *     chain is ever to be shown untampered to somebody who does not trust the database
   */
  public record Verification(
      long entries,
      boolean intact,
      Optional<Long> firstBrokenSeq,
      Optional<Long> firstGapAfterSeq,
      Optional<String> headHash) {}

  /**
   * Lists entries, newest first.
   *
   * @param tenantId the tenant
   * @param filter what to return
   * @return the entries
   */
  public List<Entry> list(String tenantId, Filter filter) {
    int limit = Math.clamp(filter.limit() <= 0 ? 100 : filter.limit(), 1, MAX_LIMIT);
    return jdbc.query(
        """
        SELECT seq, occurred_at, actor, actor_type, action, subject_kind, subject_key,
               detail, host(source_ip) AS source_ip, client,
               encode(entry_hash, 'hex') AS entry_hash
          FROM bre_ctl.audit_log
         WHERE tenant_id = ?
           AND (?::text IS NULL OR action = ?::text OR action LIKE ?::text || '.%')
           AND (?::text IS NULL OR subject_kind = ?::text)
           AND (?::text IS NULL OR subject_key  = ?::text)
           AND (?::text IS NULL OR actor        = ?::text)
           AND (?::timestamptz IS NULL OR occurred_at >= ?::timestamptz)
           AND (?::timestamptz IS NULL OR occurred_at <= ?::timestamptz)
         ORDER BY seq DESC
         LIMIT ?
        """,
        (rs, rowNum) ->
            new Entry(
                rs.getLong("seq"),
                rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getString("actor"),
                rs.getString("actor_type"),
                rs.getString("action"),
                rs.getString("subject_kind"),
                rs.getString("subject_key"),
                readDetail(rs.getString("detail")),
                rs.getString("source_ip"),
                rs.getString("client"),
                rs.getString("entry_hash")),
        tenantId,
        filter.action(),
        filter.action(),
        filter.action(),
        filter.subjectKind(),
        filter.subjectKind(),
        filter.subjectKey(),
        filter.subjectKey(),
        filter.actor(),
        filter.actor(),
        filter.from(),
        filter.from(),
        filter.to(),
        filter.to(),
        limit);
  }

  /**
   * Entries after a sequence number, oldest first — the {@code bre.studio.audit.v1} feed.
   *
   * <p><b>Ascending from a cursor, which is the opposite of {@link #list}.</b> That one serves a
   * screen, where the newest entry is the one somebody wants; this one serves a consumer replaying
   * a stream, where the oldest unseen entry is the only correct place to start.
   *
   * <p>{@code seq} is the cursor, and it is a better one than a timestamp or a generated id: the
   * chain trigger assigns it under {@code FOR UPDATE} on the tenant's chain head, so it is gapless
   * by construction. A consumer holding seq 400 knows it has seen everything up to 400 — and a gap
   * it does encounter means somebody reached past the no-mutation triggers, which is a finding
   * rather than a concurrent writer to wait for.
   *
   * @param tenantId the tenant
   * @param sinceSeq return entries with a higher seq than this; 0 for the beginning
   * @param limit how many at most
   * @return the entries, oldest first
   */
  @Transactional(readOnly = true)
  public List<Entry> since(String tenantId, long sinceSeq, int limit) {
    return jdbc.query(
        """
        SELECT seq, occurred_at, actor, actor_type, action, subject_kind, subject_key,
               detail, host(source_ip) AS source_ip, client,
               encode(entry_hash, 'hex') AS entry_hash
          FROM bre_ctl.audit_log
         WHERE tenant_id = ? AND seq > ?
         ORDER BY seq
         LIMIT ?
        """,
        (rs, rowNum) ->
            new Entry(
                rs.getLong("seq"),
                rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getString("actor"),
                rs.getString("actor_type"),
                rs.getString("action"),
                rs.getString("subject_kind"),
                rs.getString("subject_key"),
                readDetail(rs.getString("detail")),
                rs.getString("source_ip"),
                rs.getString("client"),
                rs.getString("entry_hash")),
        tenantId,
        sinceSeq,
        Math.clamp(limit <= 0 ? 100 : limit, 1, MAX_LIMIT));
  }

  /**
   * Walks the chain and recomputes every hash.
   *
   * <p><b>Recomputed in the database, with the same expression the trigger uses.</b> Doing it in
   * Java would mean a second implementation of the hash — one that could disagree with the trigger
   * about how a timestamp is formatted, and then report a perfectly good chain as broken, which is
   * an alarm nobody can act on. The SQL below is the trigger's own expression, read back.
   *
   * <p><b>Streamed, not materialised.</b> The walk keeps four values — the expected sequence, the
   * previous entry's hash, and the first fault of each kind — so memory does not grow with the
   * chain. The first draft collected every row into a list first, which works until a tenant with a
   * few hundred thousand entries asks whether its audit log is intact and gets an OutOfMemoryError
   * for an answer.
   *
   * @param tenantId the tenant
   * @return the verification
   */
  @Transactional(readOnly = true)
  public Verification verify(String tenantId) {
    Walk walk = new Walk();
    jdbc.query(
        """
            SELECT seq,
                   encode(entry_hash, 'hex') AS entry_hash,
                   encode(sha256(prev_hash || convert_to(concat_ws(E'\\x1f',
                     tenant_id, seq::text,
                     to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                     actor, actor_type, action, subject_kind, subject_key,
                     detail::text,
                     -- Appended, exactly as V14's trigger appends them. concat_ws skips nulls, so
                     -- an entry written before V14 recomputes to the same bytes it was written
                     -- with and still verifies.
                     source_ip::text, client), 'UTF8')), 'hex') AS recomputed,
                   encode(prev_hash, 'hex') AS prev_hash,
                   lag(encode(entry_hash, 'hex')) OVER (ORDER BY seq) AS previous_entry_hash
              FROM bre_ctl.audit_log
             WHERE tenant_id = ?
             ORDER BY seq
            """,
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs -> {
              long seq = rs.getLong("seq");
              String stored = rs.getString("entry_hash");
              String recomputed = rs.getString("recomputed");
              String prevHash = rs.getString("prev_hash");
              String previousEntryHash = rs.getString("previous_entry_hash");

              if (seq != walk.expectedSeq && walk.firstGapAfter == null) {
                // A missing sequence number is a deleted entry. The no-mutation triggers make
                // that impossible through SQL, so a gap means somebody reached past them —
                // which is exactly the finding this endpoint exists to surface.
                walk.firstGapAfter = walk.expectedSeq - 1;
              }
              walk.expectedSeq = seq + 1;
              walk.entries++;

              boolean linked =
                  previousEntryHash == null
                      // The first entry's prev_hash is 32 zero bytes, by the chain-head default.
                      ? prevHash.chars().allMatch(character -> character == '0')
                      : prevHash.equals(previousEntryHash);

              if ((!stored.equals(recomputed) || !linked) && walk.firstBroken == null) {
                walk.firstBroken = seq;
              }
              walk.headHash = stored;
            },
        tenantId);

    return new Verification(
        walk.entries,
        walk.firstBroken == null && walk.firstGapAfter == null,
        Optional.ofNullable(walk.firstBroken),
        Optional.ofNullable(walk.firstGapAfter),
        Optional.ofNullable(walk.headHash));
  }

  /** The running state of one chain walk. Mutable because a row callback cannot return one. */
  private static final class Walk {
    private long entries;
    private long expectedSeq = 1;
    private Long firstBroken;
    private Long firstGapAfter;
    private String headHash;
  }

  private static Map<String, Object> readDetail(String json) {
    try {
      return JSON.readValue(
          json == null || json.isBlank() ? "{}" : json,
          new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // The column is jsonb, so the database already refused anything that is not JSON. Reaching
      // here would mean the driver handed back something the column cannot hold.
      throw new IllegalStateException("audit_log.detail is not valid JSON", e);
    }
  }
}
