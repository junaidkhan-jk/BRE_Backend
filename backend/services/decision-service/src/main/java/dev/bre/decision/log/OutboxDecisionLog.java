package dev.bre.decision.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bre.common.UuidV7;
import dev.bre.decision.api.DecisionApi.DecisionResponse;
import dev.bre.decision.api.DecisionApi.RuleOutcome;
import dev.bre.decision.crypto.FactsSealer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compact-mode decision log: PostgreSQL first, with a transactional outbox (D6, LLD §7.6).
 *
 * <p><b>One transaction, three inserts, and the response waits for the commit.</b> The decision,
 * its idempotency key and the outbox event are written together, so no two of them can disagree: an
 * event without a decision would be a phantom, a decision without an idempotency key would let a
 * retry produce a second decision, and a decision whose event was lost would never reach the sink.
 * Either all three are durable or none is.
 *
 * <p>That is what RPO 0 means for an <em>acknowledged</em> decision. If the commit fails the caller
 * gets an error and no decision — which is the fail-closed direction, because a decision the caller
 * acted on but the lender cannot produce on audit is worse than no decision at all.
 *
 * <p>The outbox exists because there is no second phase to fail. Publishing to Kafka inside the
 * transaction is impossible (Kafka is not in it) and publishing after it has committed can be
 * interrupted; writing the event as a row makes publication a separate, retryable step that cannot
 * lose the event.
 */
public class OutboxDecisionLog implements DecisionLog {

  private static final Logger log = LoggerFactory.getLogger(OutboxDecisionLog.class);

  /** The topic a recorded decision is published on (§7.2). */
  private static final String DECISION_RECORDED_TOPIC = "bre.decision.recorded.v1";

  /** Placeholders for the control-plane values the registry supplies from M3. */
  private static final String UNKNOWN_POLICY = "UNKNOWN";

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final String engineVersion;
  private final String envCode;
  private final boolean storeFacts;
  private final FactsSealer sealer;

  /**
   * Creates the log.
   *
   * @param jdbc the data source
   * @param objectMapper used for the results and event payloads
   * @param engineVersion the engine version pinned on every decision (D8)
   * @param envCode the environment code recorded on every decision
   * @param storeFacts whether to persist fact snapshots, sealed per §9.2; off by default
   * @param sealer seals the snapshot into its two envelopes (M2-06)
   */
  public OutboxDecisionLog(
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      String engineVersion,
      String envCode,
      boolean storeFacts,
      FactsSealer sealer) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.engineVersion = engineVersion;
    this.envCode = envCode;
    this.storeFacts = storeFacts;
    this.sealer = sealer;
    if (storeFacts) {
      log.info(
          "bre.decision-log.store-facts is ON: snapshots are sealed per §9.2 — the"
              + " non-identifying half under the tenant-hour TDK, each party's identifying fields"
              + " under that principal's DPK, so erasure is key destruction (§9.5). Check that the"
              + " key stores are durable: with the in-memory defaults a restart makes every stored"
              + " snapshot unreadable.");
    }
  }

  /**
   * Appends a decision, its idempotency key and its outbox event in one transaction.
   *
   * <p>{@code REQUIRES_NEW} so the write is its own transaction even when a caller already has one
   * open: the durability guarantee is about this record, and joining an outer transaction would
   * make it depend on work the decision log knows nothing about.
   */
  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void append(DecisionRecord record) {
    UUID decisionId = UUID.fromString(record.response().decisionId());
    LocalDate decisionDate = decisionDateOf(decisionId, record);

    insertDecision(record, decisionId, decisionDate);
    insertIdempotencyKey(record, decisionId, decisionDate);
    if (storeFacts) {
      insertFacts(record, decisionId, decisionDate);
    }
    insertOutboxEvent(record, decisionId);
  }

  /**
   * The UTC date the row is partitioned by (§5.3): the date of the decision id's UUIDv7 timestamp.
   *
   * <p>Derived from the identifier rather than from {@code recordedAt} so that a lookup by id can
   * compute the partition without reading a row first — which is the entire reason §5.3 wants a v7
   * identifier. A v4 id has no timestamp, so it falls back to the record's own time and loses the
   * pruning; that is a correctness-preserving fallback, not an equivalent one.
   */
  private LocalDate decisionDateOf(UUID decisionId, DecisionRecord record) {
    if (decisionId.version() == 7) {
      return LocalDate.ofInstant(UuidV7.instantOf(decisionId), ZoneOffset.UTC);
    }
    log.warn(
        "decision id {} is version {}, not 7: partition pruning by id is not possible for this row",
        decisionId,
        decisionId.version());
    return LocalDate.ofInstant(record.recordedAt(), ZoneOffset.UTC);
  }

  private void insertDecision(DecisionRecord record, UUID decisionId, LocalDate decisionDate) {
    DecisionResponse response = record.response();
    jdbc.update(
        """
        INSERT INTO bre_dec.decisions (
            tenant_id, decision_date, decision_id, request_id, application_ref, env_code,
            policy_code, policy_version, bundle_hash, engine_version, as_of, as_of_date,
            time_zone, received_at, completed_at, latency_us, decision, stp_eligible,
            reason_codes, deviation_types, required_levels, error_count, fuel_used, results_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
        """,
        (PreparedStatement statement) -> {
          int index = 1;
          statement.setString(index++, record.tenantId());
          statement.setObject(index++, decisionDate);
          statement.setObject(index++, decisionId);
          statement.setString(index++, record.requestId());
          statement.setString(index++, record.applicationRef().orElse(record.requestId()));
          statement.setString(index++, envCode);
          statement.setString(index++, record.policyCode().orElse(UNKNOWN_POLICY));
          statement.setInt(index++, record.policyVersion().orElse(0));
          statement.setString(index++, record.bundleHash().orElse(UNKNOWN_POLICY));
          statement.setString(index++, engineVersion);
          statement.setTimestamp(
              index++, Timestamp.from(response.asOf().atStartOfDay(ZoneOffset.UTC).toInstant()));
          statement.setObject(index++, response.asOf());
          statement.setString(index++, record.timeZone());
          statement.setTimestamp(index++, Timestamp.from(record.receivedAt()));
          statement.setTimestamp(index++, Timestamp.from(record.recordedAt()));
          statement.setInt(index++, (int) record.latencyMicros());
          statement.setString(index++, response.decision());
          statement.setBoolean(index++, response.stpEligible());
          statement.setArray(index++, textArray(statement, reasonCodes(response)));
          statement.setArray(index++, textArray(statement, List.of()));
          statement.setArray(index++, textArray(statement, List.of()));
          statement.setShort(index++, (short) errorCount(response));
          statement.setLong(index++, record.fuelUsed());
          statement.setString(index, writeJson(response.results()));
        });
  }

  private void insertIdempotencyKey(
      DecisionRecord record, UUID decisionId, LocalDate decisionDate) {
    // The fingerprint lets a later retry be told apart from a different request reusing the key.
    // Enforcing that difference is M3's job; recording it now means the evidence exists when it is.
    jdbc.update(
        """
        INSERT INTO bre_dec.idempotency_keys (
            tenant_id, request_id, decision_id, decision_date, request_fingerprint)
        VALUES (?, ?, ?, ?, ?)
        """,
        record.tenantId(),
        record.requestId(),
        decisionId,
        decisionDate,
        sha256(record.factsJson()));
  }

  private void insertFacts(DecisionRecord record, UUID decisionId, LocalDate decisionDate) {
    FactsSealer.Sealed sealed;
    try {
      sealed =
          sealer.seal(
              record.tenantId(),
              record.response().decisionId(),
              objectMapper.readTree(record.factsJson()),
              // The hour the DECISION happened in, not now: the kid has to agree with the record,
              // and a write retried across an hour boundary must not seal under a different key
              // from the one the decision's own timestamp implies.
              record.receivedAt());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // Do not put the facts in the message: that is the PII leak §9.6 forbids.
      throw new IllegalStateException("the fact snapshot could not be read back for sealing");
    }

    jdbc.update(
        """
        INSERT INTO bre_dec.decision_facts (
            tenant_id, decision_date, decision_id, facts_envelope, facts_kid,
            principal_envelopes, facts_hmac, fact_schema)
        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
        """,
        record.tenantId(),
        decisionDate,
        decisionId,
        sealed.factsEnvelope(),
        sealed.factsKid(),
        writeJson(sealed.principalEnvelopes()),
        sealed.factsHmac(),
        record.factSchema());
  }

  @Override
  public Optional<StoredFacts> facts(String tenantId, String decisionId) {
    if (!storeFacts) {
      return Optional.empty();
    }
    UUID id = UUID.fromString(decisionId);
    // Partition-pruned by the id's own UUIDv7 timestamp (§5.3), which is why the id is a v7.
    LocalDate decisionDate =
        id.version() == 7 ? LocalDate.ofInstant(UuidV7.instantOf(id), ZoneOffset.UTC) : null;

    List<StoredFacts> found =
        jdbc.query(
            decisionDate != null
                ? """
                  SELECT facts_envelope, principal_envelopes, facts_hmac
                    FROM bre_dec.decision_facts
                   WHERE tenant_id = ? AND decision_id = ? AND decision_date = ?
                  """
                : """
                  SELECT facts_envelope, principal_envelopes, facts_hmac
                    FROM bre_dec.decision_facts
                   WHERE tenant_id = ? AND decision_id = ?
                  """,
            (rs, rowNum) -> {
              FactsSealer.Opened opened =
                  sealer.open(
                      tenantId,
                      decisionId,
                      rs.getBytes("facts_envelope"),
                      readEnvelopeMap(rs.getString("principal_envelopes")),
                      rs.getString("facts_hmac"));
              return new StoredFacts(
                  opened.facts(), opened.shreddedParties(), opened.integrityVerified());
            },
            decisionDate != null
                ? new Object[] {tenantId, id, decisionDate}
                : new Object[] {tenantId, id});
    return found.stream().findFirst();
  }

  private Map<String, String> readEnvelopeMap(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(
          json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("principal_envelopes is not a JSON object of envelopes");
    }
  }

  private String writeJson(Map<String, String> envelopes) {
    try {
      return objectMapper.writeValueAsString(envelopes);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("the principal envelopes could not be serialised");
    }
  }

  private void insertOutboxEvent(DecisionRecord record, UUID decisionId) {
    // CloudEvents 1.0 attributes (D12, §7.1). The relay copies these onto the Kafka headers.
    String headers =
        writeJson(
            java.util.Map.of(
                "ce_specversion",
                "1.0",
                "ce_type",
                DECISION_RECORDED_TOPIC,
                "ce_source",
                "/bre/decision-service",
                "ce_id",
                decisionId.toString(),
                "ce_time",
                record.recordedAt().toString(),
                "ce_subject",
                record.tenantId()));

    jdbc.update(
        """
        INSERT INTO bre_dec.outbox (tenant_id, topic, record_key, headers, payload)
        VALUES (?, ?, ?, ?::jsonb, ?::jsonb)
        """,
        record.tenantId(),
        DECISION_RECORDED_TOPIC,
        record.tenantId() + ":" + record.applicationRef().orElse(record.requestId()),
        headers,
        writeJson(record.response()));
  }

  @Override
  public Optional<DecisionRecord> find(String tenantId, String decisionId) {
    // Tenant-scoped, always: a decision is never readable across tenants (D1). The tenant is part
    // of the primary key, so this is a single index lookup rather than a filter over a found row.
    return jdbc
        .query(
            """
            SELECT tenant_id, decision_id, request_id, completed_at, decision, stp_eligible,
                   as_of_date, results_json
              FROM bre_dec.decisions
             WHERE tenant_id = ? AND decision_id = ?
            """,
            decisionMapper(),
            tenantId,
            UUID.fromString(decisionId))
        .stream()
        .findFirst();
  }

  @Override
  public Optional<DecisionRecord> findByRequestId(String tenantId, String requestId) {
    return jdbc
        .query(
            """
            SELECT d.tenant_id, d.decision_id, d.request_id, d.completed_at, d.decision,
                   d.stp_eligible, d.as_of_date, d.results_json
              FROM bre_dec.idempotency_keys k
              JOIN bre_dec.decisions d
                ON d.tenant_id = k.tenant_id
               AND d.decision_date = k.decision_date
               AND d.decision_id = k.decision_id
             WHERE k.tenant_id = ? AND k.request_id = ?
            """,
            decisionMapper(),
            tenantId,
            requestId)
        .stream()
        .findFirst();
  }

  private RowMapper<DecisionRecord> decisionMapper() {
    return (rows, rowNumber) -> {
      List<RuleOutcome> results = readResults(rows.getString("results_json"));
      DecisionResponse response =
          new DecisionResponse(
              rows.getString("decision_id"),
              rows.getString("tenant_id"),
              rows.getString("request_id"),
              rows.getObject("as_of_date", LocalDate.class),
              rows.getString("decision"),
              rows.getBoolean("stp_eligible"),
              results);
      return DecisionRecord.readBack(
          rows.getString("decision_id"),
          rows.getString("tenant_id"),
          rows.getString("request_id"),
          rows.getTimestamp("completed_at").toInstant(),
          response);
    };
  }

  private List<RuleOutcome> readResults(String json) {
    try {
      return objectMapper.readValue(
          json,
          objectMapper
              .getTypeFactory()
              .constructCollectionType(ArrayList.class, RuleOutcome.class));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // A stored result that will not parse is corruption, and it must not be returned as if it
      // were the decision. The message names the row, never its contents.
      throw new IllegalStateException("stored decision results are not readable");
    }
  }

  private static List<String> reasonCodes(DecisionResponse response) {
    return response.results().stream()
        .map(RuleOutcome::reasonCode)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
  }

  private static int errorCount(DecisionResponse response) {
    return (int) response.results().stream().filter(result -> result.error() != null).count();
  }

  /**
   * Builds a {@code text[]} on the statement's own connection.
   *
   * <p><b>This used to call {@code jdbc.getDataSource().getConnection()}</b> — a second, borrowed,
   * never-closed connection, three times per decision. Sixteen pooled connections therefore
   * survived five decisions: a burst returned {@code Connection is not available, request timed out
   * ... (total=16, active=16)} and the decision API answered 500 for decisions the engine had
   * already made correctly. The statement already holds the connection this insert is running on,
   * and an array created there is freed with the statement.
   */
  private static java.sql.Array textArray(PreparedStatement statement, List<String> values)
      throws SQLException {
    return statement.getConnection().createArrayOf("text", values.toArray(String[]::new));
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // Never put the VALUE in the message: for the facts payload that would be the PII leak §9.6
      // forbids. The CAUSE is kept, though — Jackson's own message names the type and the problem
      // and never the data, and dropping it left "could not serialise DecisionResponse" as the
      // only evidence of a failure that took a rebuild to identify.
      throw new IllegalStateException(
          "could not serialise " + value.getClass().getSimpleName() + " for the decision record",
          e);
    }
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  @Override
  public String describe() {
    return "PostgreSQL compact mode (durable; decision + idempotency key + outbox in one"
        + " transaction)"
        + (storeFacts ? " — storing fact snapshots, sealed per §9.2" : " — not storing facts");
  }
}
