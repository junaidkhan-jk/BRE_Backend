package dev.bre.decision.deliver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers recorded decisions to a downstream system (D6, LLD §7.6).
 *
 * <p><b>The outbox has existed since V1 and nothing drained it.</b> Every decision wrote a row, in
 * the same transaction as the decision itself, and {@code published_at} was never set — so a
 * decision was durable here and reached nothing else. This is the missing half: the relay.
 *
 * <p><b>Why an outbox rather than a call inside the decision.</b> Publishing downstream inside the
 * decision transaction gives two failure modes and the second is intolerable: a decision recorded
 * and not published, or <em>published and not recorded</em> — a downstream system acting on a
 * decision this installation cannot show it made. The outbox removes the second phase: the decision
 * and the intent to publish commit together, and delivery becomes a separate, retryable act.
 *
 * <p><b>At-least-once, and the event says so.</b> A row is marked published after a successful
 * delivery, so a crash between the two redelivers. Exactly-once is not achievable without the
 * receiver participating, so every event carries a stable {@code ce_id} — the decision id — and the
 * receiver is expected to deduplicate on it. A downstream team that assumes exactly-once will build
 * a double disbursement, so it is stated in the event, in the docs, and here.
 *
 * <p><b>Order is preserved per tenant, by taking one advisory lock per tenant.</b> The obvious
 * {@code FOR UPDATE SKIP LOCKED} would let a second pod skip past a row the first is retrying and
 * deliver a later decision for the same application first — so a downstream system could see an
 * approval superseded by a decline that was made before it. One drainer per tenant costs
 * parallelism across pods and buys an ordering guarantee worth more than it.
 *
 * <p><b>This is the only outbound network call in the whole application</b>, and it is off by
 * default. Everything else BRE does is facts-in, decision-out: it never calls a bureau, a KYC
 * provider or a core banking system (D2). Turning this on is a deliberate act, and it is the one
 * line of {@code docs/EXTERNAL-CALLS.md} that changes when you do.
 */
@Component
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * How many events one pass delivers per tenant.
   *
   * <p>Bounded so a backlog — a downstream that was down overnight — cannot hold one tenant's
   * advisory lock for minutes while another tenant's decisions wait behind it.
   */
  private static final int BATCH = 50;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final HttpClient client;
  private final String endpoint;
  private final String authorization;
  private final int maxAttempts;
  private final Duration timeout;

  /**
   * Creates the relay.
   *
   * @param jdbc the decision-log database
   * @param transactionManager makes each tenant's batch one transaction, holding its lock
   * @param endpoint where to POST each event, or blank to deliver nothing. Blank is the default: an
   *     installation that has not named a downstream has none, and inventing one would send a
   *     lender's credit decisions somewhere nobody chose.
   * @param authorization an {@code Authorization} header value for the downstream, or blank
   * @param maxAttempts how many times one event is retried before it is dead-lettered
   * @param timeoutMillis how long one delivery may take
   */
  public OutboxRelay(
      JdbcTemplate jdbc,
      org.springframework.transaction.PlatformTransactionManager transactionManager,
      @Value("${bre.outbox.endpoint:}") String endpoint,
      @Value("${bre.outbox.authorization:}") String authorization,
      @Value("${bre.outbox.max-attempts:12}") int maxAttempts,
      @Value("${bre.outbox.timeout-ms:5000}") long timeoutMillis) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
    this.endpoint = endpoint == null ? "" : endpoint.trim();
    this.authorization = authorization == null ? "" : authorization.trim();
    this.maxAttempts = maxAttempts;
    this.timeout = Duration.ofMillis(timeoutMillis);
    this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  /** Whether a downstream is configured at all. */
  public boolean enabled() {
    return !endpoint.isEmpty();
  }

  /**
   * Delivers whatever is due.
   *
   * <p>Every two seconds. A downstream origination system is usually waiting on a decision, and the
   * poll is cheap because the index covers exactly the due rows.
   */
  @Scheduled(fixedDelayString = "${bre.outbox.poll-ms:2000}")
  public void deliverDue() {
    if (!enabled()) {
      return;
    }
    List<String> tenants;
    try {
      tenants = pendingTenants();
    } catch (RuntimeException unavailable) {
      log.warn("could not look for pending decision events: {}", unavailable.getMessage());
      return;
    }
    for (String tenantId : tenants) {
      try {
        deliverFor(tenantId);
      } catch (RuntimeException failed) {
        // One tenant's broken downstream must not stop another tenant's decisions.
        log.error("decision delivery failed for tenant={}", tenantId, failed);
      }
    }
  }

  private List<String> pendingTenants() {
    return jdbc.query(
        """
        SELECT DISTINCT tenant_id FROM bre_dec.outbox
         WHERE published_at IS NULL AND failed_at IS NULL AND next_attempt_at <= now()
        """,
        (rs, rowNum) -> rs.getString(1));
  }

  /**
   * Delivers one tenant's due events, in order, stopping at the first failure.
   *
   * <p><b>Stopping matters.</b> Skipping a failed event and delivering the next would reorder a
   * tenant's decisions — a downstream could see today's approval before yesterday's decline. The
   * failed event is retried with backoff and the rest wait behind it, which is what a queue does.
   *
   * @param tenantId the tenant
   */
  void deliverFor(String tenantId) {
    transactions.executeWithoutResult(
        status -> {
          Boolean mine =
              jdbc.queryForObject(
                  "SELECT pg_try_advisory_xact_lock(hashtext(?))", Boolean.class, tenantId);
          if (!Boolean.TRUE.equals(mine)) {
            // Another pod is draining this tenant. Not an error, and not worth logging every
            // two seconds.
            return;
          }
          for (Event event : due(tenantId)) {
            if (!deliver(event)) {
              return;
            }
          }
        });
  }

  private record Event(
      long id,
      String tenantId,
      String topic,
      String key,
      JsonNode headers,
      JsonNode payload,
      int attempts) {}

  private List<Event> due(String tenantId) {
    return jdbc.query(
        """
        SELECT outbox_id, tenant_id, topic, record_key, headers::text AS headers,
               payload::text AS payload, attempts
          FROM bre_dec.outbox
         WHERE tenant_id = ? AND published_at IS NULL AND failed_at IS NULL
           AND next_attempt_at <= now()
         ORDER BY outbox_id
         LIMIT ?
        """,
        (rs, rowNum) ->
            new Event(
                rs.getLong("outbox_id"),
                rs.getString("tenant_id"),
                rs.getString("topic"),
                rs.getString("record_key"),
                readJson(rs.getString("headers")),
                readJson(rs.getString("payload")),
                rs.getInt("attempts")),
        tenantId,
        BATCH);
  }

  /**
   * Delivers one event.
   *
   * @return true when it was accepted and marked published
   */
  private boolean deliver(Event event) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(endpoint))
              .timeout(timeout)
              .header("Content-Type", "application/cloudevents+json")
              // The CloudEvents attributes as headers as well as in the body, so a receiver can
              // route and deduplicate without parsing the payload.
              .header("ce-id", text(event.headers(), "ce_id"))
              .header("ce-type", text(event.headers(), "ce_type"))
              .header("ce-source", text(event.headers(), "ce_source"))
              .header("ce-subject", text(event.headers(), "ce_subject"))
              .header("ce-specversion", "1.0")
              // The ordering key, so a receiver that fans out can keep one application's
              // decisions together.
              .header("bre-record-key", event.key())
              .POST(HttpRequest.BodyPublishers.ofString(body(event)));
      if (!authorization.isEmpty()) {
        builder.header("Authorization", authorization);
      }

      HttpResponse<String> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() / 100 == 2) {
        jdbc.update(
            "UPDATE bre_dec.outbox SET published_at = now(), attempts = attempts + 1,"
                + " last_error = NULL WHERE outbox_id = ?",
            event.id());
        log.info(
            "decision event delivered tenant={} id={} key={} status={}",
            event.tenantId(),
            text(event.headers(), "ce_id"),
            event.key(),
            response.statusCode());
        return true;
      }

      // A 4xx other than 408 or 429 will not succeed on a retry: the receiver has rejected the
      // content rather than failed to handle it. Dead-lettering at once is kinder than twelve
      // attempts that cannot work, and it surfaces the integration defect while somebody is
      // still looking at it.
      boolean permanent =
          response.statusCode() / 100 == 4
              && response.statusCode() != 408
              && response.statusCode() != 429;
      fail(event, "HTTP " + response.statusCode() + ": " + abbreviate(response.body()), permanent);
      return false;

    } catch (java.io.IOException transportFailure) {
      fail(event, "IO: " + transportFailure.getMessage(), false);
      return false;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      fail(event, "interrupted", false);
      return false;
    }
  }

  /** The event body: a CloudEvent whose data is the decision as the API returned it. */
  private String body(Event event) {
    try {
      Map<String, Object> envelope =
          Map.of(
              "specversion", "1.0",
              "id", text(event.headers(), "ce_id"),
              "type", event.topic(),
              "source", text(event.headers(), "ce_source"),
              "subject", text(event.headers(), "ce_subject"),
              "time", text(event.headers(), "ce_time"),
              "datacontenttype", "application/json",
              "recordkey", event.key(),
              "data", JSON.treeToValue(event.payload(), Object.class));
      return JSON.writeValueAsString(envelope);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("a stored outbox payload could not be re-serialised", e);
    }
  }

  private void fail(Event event, String error, boolean permanent) {
    int attempts = event.attempts() + 1;
    if (permanent || attempts >= maxAttempts) {
      jdbc.update(
          "UPDATE bre_dec.outbox SET attempts = ?, last_error = ?, failed_at = now()"
              + " WHERE outbox_id = ?",
          attempts,
          error,
          event.id());
      // At ERROR, naming the tenant and the key: a dead-lettered decision means a downstream is
      // missing a decision that was made, and nobody finds that out from a metric.
      log.error(
          "decision event DEAD-LETTERED after {} attempt(s) tenant={} key={}: {}. It will not be"
              + " retried automatically; replay it once the cause is fixed.",
          attempts,
          event.tenantId(),
          event.key(),
          error);
      return;
    }
    // 2s, 4s, 8s … capped at five minutes. Capped because an outage longer than the curve must not
    // push the next attempt hours out: when the downstream returns, the backlog should drain.
    long backoffSeconds = Math.min(300L, (long) Math.pow(2, Math.min(attempts, 8)));
    jdbc.update(
        "UPDATE bre_dec.outbox SET attempts = ?, last_error = ?,"
            + " next_attempt_at = now() + (? || ' seconds')::interval WHERE outbox_id = ?",
        attempts,
        error,
        backoffSeconds,
        event.id());
    log.warn(
        "decision event delivery failed tenant={} key={} attempt={} retrying in {}s: {}",
        event.tenantId(),
        event.key(),
        attempts,
        backoffSeconds,
        error);
  }

  /**
   * Returns a dead-lettered event to the queue.
   *
   * <p>An operator's deliberate act: a row is dead-lettered because something refused it, and
   * replaying before fixing that only spends the attempts again.
   *
   * @param tenantId the tenant
   * @param outboxId the event
   * @return true when a row was replayed
   */
  public boolean replay(String tenantId, long outboxId) {
    return jdbc.update(
            "UPDATE bre_dec.outbox SET failed_at = NULL, attempts = 0, next_attempt_at = now(),"
                + " last_error = NULL"
                + " WHERE tenant_id = ? AND outbox_id = ? AND failed_at IS NOT NULL",
            tenantId,
            outboxId)
        > 0;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? "" : value.asText();
  }

  private static String abbreviate(String body) {
    if (body == null) {
      return "";
    }
    return body.length() <= 300 ? body : body.substring(0, 300) + "…";
  }

  private static JsonNode readJson(String json) {
    try {
      return JSON.readTree(json == null || json.isBlank() ? "{}" : json);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("a stored outbox column is not valid JSON", e);
    }
  }
}
