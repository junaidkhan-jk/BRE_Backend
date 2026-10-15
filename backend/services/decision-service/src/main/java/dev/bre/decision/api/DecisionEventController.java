package dev.bre.decision.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bre.decision.deliver.OutboxRelay;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Decisions as a feed a downstream system can pull (D6, LLD §7.6).
 *
 * <p><b>Why both a push and a pull.</b> The relay POSTs each decision to a configured endpoint,
 * which suits a system that wants to act the moment a decision is made. A pull suits everything
 * else: a nightly reconciliation, a data warehouse, a downstream behind a firewall that will not
 * accept an inbound connection, and — most usefully — a team integrating for the first time, who
 * can read the feed with {@code curl} before anybody stands up a receiver. The same rows serve
 * both, so the two can never disagree about what was decided.
 *
 * <p><b>Cursor-based, not time-based.</b> {@code since} is the last {@code outboxId} the caller
 * processed, and the feed returns rows strictly after it in order. A timestamp cursor would drop
 * events: two decisions in the same millisecond, and a caller resuming from that timestamp skips
 * one. The outbox id is monotonic and gapless per row, so a caller that records the last id it
 * handled cannot miss one.
 *
 * <p><b>Reading the feed does not consume it.</b> A pull leaves {@code published_at} alone, so the
 * same decision can be read by the warehouse and pushed to the origination system without either
 * interfering with the other. Which also means a pulling caller must keep its own cursor — the feed
 * will happily serve the same rows again.
 *
 * <p><b>No facts.</b> An event carries the decision, its reasons and which policy decided it. §9.6
 * keeps fact values out of anything that leaves the decision path, so this feed names fields and
 * never a borrower's figures. A caller that needs the facts has them already: they sent them.
 */
@RestController
@RequestMapping("/v1/decision-events")
public class DecisionEventController {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The largest page this serves, whatever a caller asks for. */
  public static final int MAX_LIMIT = 500;

  private final JdbcTemplate jdbc;
  private final OutboxRelay relay;

  /**
   * Creates the controller.
   *
   * @param jdbc the decision-log database
   * @param relay the push relay, for replaying a dead-lettered event
   */
  public DecisionEventController(JdbcTemplate jdbc, OutboxRelay relay) {
    this.jdbc = jdbc;
    this.relay = relay;
  }

  /**
   * One decision event.
   *
   * @param outboxId the cursor — pass it back as {@code since} to continue
   * @param topic the event type
   * @param recordKey the ordering key: tenant and application reference
   * @param occurredAt when the decision was recorded
   * @param deliveredAt when the push relay delivered it, absent when it has not or there is no
   *     downstream configured
   * @param attempts how many delivery attempts have been made
   * @param deadLettered true when delivery was abandoned and an operator must replay it
   * @param lastError why the last attempt failed
   * @param decision the decision as the API returned it
   */
  public record Event(
      long outboxId,
      String topic,
      String recordKey,
      String occurredAt,
      Optional<String> deliveredAt,
      int attempts,
      boolean deadLettered,
      Optional<String> lastError,
      Map<String, Object> decision) {}

  /**
   * The decision feed, oldest first.
   *
   * @param tenantId the tenant, from the header the gateway sets
   * @param since the last {@code outboxId} processed; omit to start from the beginning
   * @param limit how many, capped at {@value #MAX_LIMIT}
   * @param pendingOnly true to see only what has not been delivered downstream
   * @return the events
   */
  @GetMapping
  public List<Event> feed(
      @RequestHeader("X-Tenant-Id") String tenantId,
      @RequestParam(defaultValue = "0") long since,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "false") boolean pendingOnly) {

    int capped = Math.clamp(limit <= 0 ? 100 : limit, 1, MAX_LIMIT);
    return jdbc.query(
        """
        SELECT outbox_id, topic, record_key,
               to_char(created_at,   'YYYY-MM-DD"T"HH24:MI:SS.USZ') AS created_at,
               to_char(published_at, 'YYYY-MM-DD"T"HH24:MI:SS.USZ') AS published_at,
               attempts, failed_at IS NOT NULL AS dead_lettered, last_error,
               payload::text AS payload
          FROM bre_dec.outbox
         WHERE tenant_id = ? AND outbox_id > ?
           AND (NOT ?::boolean OR published_at IS NULL)
         ORDER BY outbox_id
         LIMIT ?
        """,
        (rs, rowNum) ->
            new Event(
                rs.getLong("outbox_id"),
                rs.getString("topic"),
                rs.getString("record_key"),
                rs.getString("created_at"),
                Optional.ofNullable(rs.getString("published_at")),
                rs.getInt("attempts"),
                rs.getBoolean("dead_lettered"),
                Optional.ofNullable(rs.getString("last_error")),
                decision(rs.getString("payload"))),
        tenantId,
        since,
        pendingOnly,
        capped);
  }

  /**
   * What is stuck.
   *
   * <p>Separate from the feed because it answers an operator's question rather than an
   * integrator's: a dead-lettered decision means a downstream system is missing a decision that was
   * made, which is not a thing to discover by paging through a feed.
   *
   * @param tenantId the tenant
   * @return the counts and the oldest pending event's age
   */
  @GetMapping("/health")
  public Map<String, Object> health(@RequestHeader("X-Tenant-Id") String tenantId) {
    Map<String, Object> out =
        jdbc.queryForObject(
            """
            SELECT count(*) FILTER (WHERE published_at IS NULL AND failed_at IS NULL) AS pending,
                   count(*) FILTER (WHERE failed_at IS NOT NULL) AS dead_lettered,
                   count(*) FILTER (WHERE published_at IS NOT NULL) AS delivered,
                   coalesce(max(extract(epoch FROM now() - created_at))
                            FILTER (WHERE published_at IS NULL AND failed_at IS NULL), 0)
                     AS oldest_pending_seconds
              FROM bre_dec.outbox WHERE tenant_id = ?
            """,
            (rs, rowNum) ->
                Map.<String, Object>of(
                    "pending", rs.getLong("pending"),
                    "deadLettered", rs.getLong("dead_lettered"),
                    "delivered", rs.getLong("delivered"),
                    "oldestPendingSeconds", rs.getLong("oldest_pending_seconds"),
                    "pushEnabled", relay.enabled()),
            tenantId);
    return out == null ? Map.of() : out;
  }

  /**
   * Replays a dead-lettered event.
   *
   * @param tenantId the tenant
   * @param outboxId the event
   * @return 200 when it was replayed, 404 when there is no such dead-lettered event
   */
  @PostMapping("/{outboxId}/replay")
  public ResponseEntity<Map<String, Object>> replay(
      @RequestHeader("X-Tenant-Id") String tenantId, @PathVariable long outboxId) {
    if (!relay.replay(tenantId, outboxId)) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
    return ResponseEntity.ok(Map.of("outboxId", outboxId, "replayed", true));
  }

  private static Map<String, Object> decision(String payload) {
    try {
      JsonNode node = JSON.readTree(payload == null || payload.isBlank() ? "{}" : payload);
      return JSON.convertValue(
          node, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("a stored outbox payload is not valid JSON", e);
    }
  }
}
