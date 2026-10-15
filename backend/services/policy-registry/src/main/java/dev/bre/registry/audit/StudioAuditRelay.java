package dev.bre.registry.audit;

import dev.bre.common.TenantContext;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes the studio audit trail on {@code bre.studio.audit.v1} (M3-10, FR-AUD-005).
 *
 * <p><b>Why a lender wants this stream at all.</b> The audit trail is already queryable here, and
 * that is enough to answer a question somebody thinks to ask. It is not enough for the two things a
 * stream does: a SIEM that alerts on a policy approval from an unexpected address without anybody
 * looking, and a group-level governance system that holds the trail outside the installation being
 * audited. An audit log whose only copy lives in the system it audits is a copy an administrator of
 * that system can be asked to explain away.
 *
 * <p><b>There is no outbox table, and that is deliberate.</b> An outbox exists to make a stream out
 * of state that is otherwise overwritten; the audit log is already append-only, gapless, sequenced
 * by a trigger and forbidden to UPDATE or DELETE. A second copy would double the writes and add the
 * one failure mode neither table has alone — a published trail that disagrees with the recorded
 * one. So {@code audit_log.seq} is the cursor, and {@code studio_audit_delivery} holds one row per
 * tenant saying how far delivery has got.
 *
 * <p><b>Nothing is held across the HTTP call.</b> Three short pieces of database work — claim the
 * lease, read the batch, advance the cursor — because holding a transaction across a network call
 * is how a connection pool dies. That is not a precaution here, it is a repair: the decision
 * service's first relay did exactly that and a burst of three decisions exhausted all sixteen
 * connections. Same shape as {@code OutboxRelay} for the same reason.
 *
 * <p><b>At-least-once, and the consumer must deduplicate.</b> A crash between delivering and
 * advancing the cursor redelivers. Every event carries {@code ce-id} of {@code <tenant>:<seq>},
 * which is stable for the life of the entry, so deduplication is a primary key lookup.
 *
 * <p>Off by default. An installation that has not named a consumer has none, and inventing one
 * would send a lender's governance trail somewhere nobody chose.
 */
@Component
public class StudioAuditRelay {

  private static final Logger log = LoggerFactory.getLogger(StudioAuditRelay.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** How many entries one pass delivers per tenant, so a backlog cannot starve another tenant. */
  private static final int BATCH = 200;

  /** Long enough for a batch against a slow consumer, short enough that a dead pod frees it. */
  private static final Duration LEASE = Duration.ofSeconds(60);

  private final JdbcTemplate jdbc;
  private final AuditQuery audit;
  private final TransactionTemplate transactions;
  private final HttpClient client;
  private final String endpoint;
  private final String authorization;
  private final Duration timeout;
  private final String instanceId;

  /**
   * Creates the relay.
   *
   * @param jdbc the control-plane database
   * @param audit the audit trail, read through its own read-only query object
   * @param transactionManager for the three short transactions
   * @param endpoint where to POST, or blank to publish nothing — the default
   * @param authorization an {@code Authorization} header value for the consumer, or blank
   * @param timeoutMillis how long one delivery may take
   * @param instanceId identifies this instance on a lease it holds; the pod name in a cluster
   */
  public StudioAuditRelay(
      JdbcTemplate jdbc,
      AuditQuery audit,
      org.springframework.transaction.PlatformTransactionManager transactionManager,
      @Value("${bre.studio-audit.endpoint:}") String endpoint,
      @Value("${bre.studio-audit.authorization:}") String authorization,
      @Value("${bre.studio-audit.timeout-ms:5000}") long timeoutMillis,
      @Value("${bre.instance-id:local}") String instanceId) {
    this.jdbc = jdbc;
    this.audit = audit;
    this.transactions = new TransactionTemplate(transactionManager);
    this.endpoint = endpoint == null ? "" : endpoint.trim();
    this.authorization = authorization == null ? "" : authorization.trim();
    this.timeout = Duration.ofMillis(timeoutMillis);
    this.instanceId = instanceId == null || instanceId.isBlank() ? "local" : instanceId;
    this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  /** Whether a consumer is configured at all. */
  public boolean enabled() {
    return !endpoint.isEmpty();
  }

  /** Delivers whatever is undelivered, for every tenant that has something. */
  @Scheduled(fixedDelayString = "${bre.studio-audit.poll-ms:5000}")
  public void publishPending() {
    if (!enabled()) {
      return;
    }
    List<String> tenants;
    try {
      tenants =
          jdbc.queryForList("SELECT * FROM bre_ctl.tenants_with_undelivered_audit()", String.class);
    } catch (RuntimeException unavailable) {
      log.warn("could not look for undelivered audit entries", unavailable);
      return;
    }
    for (String tenantId : tenants) {
      try {
        // Each tenant's work runs inside its own scope, and the transaction is opened INSIDE it.
        // The other order silently fails: a @Transactional method called on `this` is not proxied,
        // so the scope dies with its own statement and RLS then hides every row — which is how
        // DeploymentActivator shipped broken once.
        TenantContext.runIn(tenantId, () -> publishFor(tenantId));
      } catch (RuntimeException failed) {
        log.error("studio audit delivery failed for tenant={}", tenantId, failed);
      }
    }
  }

  /**
   * Delivers one tenant's undelivered entries, in order, stopping at the first refusal.
   *
   * <p>Stopping matters more here than for decisions: the trail is a sequence, and a consumer that
   * received entry 7 and then entry 9 has a gap it cannot tell from tampering.
   *
   * @param tenantId the tenant, already in scope
   */
  void publishFor(String tenantId) {
    if (!claim(tenantId)) {
      return;
    }
    try {
      long cursor = cursorOf(tenantId);
      List<AuditQuery.Entry> batch = audit.since(tenantId, cursor, BATCH);
      for (AuditQuery.Entry entry : batch) {
        if (!deliver(tenantId, entry)) {
          // refused() has already set the backoff and dropped the lease, so the next pass can
          // pick the tenant up when the backoff expires rather than waiting out a lease too.
          return;
        }
        advance(tenantId, entry.seq());
      }
    } finally {
      // Released rather than left to expire, and this is not an optimisation.
      //
      // advance() renews the lease, which is what lets a long batch against a slow consumer keep
      // going. Without this release that renewal was also the END state: a successful pass left
      // leased_until sixty seconds in the future, claim() refuses an unexpired lease, and so the
      // tenant published its backlog once and then went silent for a minute at a time. The first
      // run of this relay delivered the bootstrap entries and nothing after them, and the two
      // tests that assert a NEW action is published are what caught it.
      release(tenantId);
    }
  }

  private boolean claim(String tenantId) {
    return Boolean.TRUE.equals(
        transactions.execute(
            status ->
                jdbc.update(
                        """
                        INSERT INTO bre_ctl.studio_audit_delivery (tenant_id, leased_by,
                            leased_until)
                        VALUES (?, ?, now() + (? || ' seconds')::interval)
                        ON CONFLICT (tenant_id) DO UPDATE
                           SET leased_by = excluded.leased_by,
                               leased_until = excluded.leased_until
                         WHERE bre_ctl.studio_audit_delivery.leased_until < now()
                        """,
                        tenantId,
                        instanceId,
                        LEASE.toSeconds())
                    > 0));
  }

  private long cursorOf(String tenantId) {
    Long seq =
        transactions.execute(
            status ->
                jdbc.queryForObject(
                    "SELECT delivered_seq FROM bre_ctl.studio_audit_delivery WHERE tenant_id = ?",
                    Long.class,
                    tenantId));
    return seq == null ? 0L : seq;
  }

  private void advance(String tenantId, long seq) {
    transactions.executeWithoutResult(
        status ->
            jdbc.update(
                """
                UPDATE bre_ctl.studio_audit_delivery
                   SET delivered_seq = ?, attempts = 0, last_error = NULL,
                       next_attempt_at = now(), leased_until = now() + interval '60 seconds',
                       updated_at = now()
                 WHERE tenant_id = ? AND delivered_seq < ?
                """,
                seq,
                tenantId,
                seq));
  }

  private void release(String tenantId) {
    transactions.executeWithoutResult(
        status ->
            jdbc.update(
                """
                UPDATE bre_ctl.studio_audit_delivery
                   SET leased_until = now(), leased_by = NULL
                 WHERE tenant_id = ? AND leased_by = ?
                """,
                tenantId,
                instanceId));
  }

  private void refused(String tenantId, String why) {
    transactions.executeWithoutResult(
        status ->
            jdbc.update(
                """
                UPDATE bre_ctl.studio_audit_delivery
                   SET attempts = attempts + 1, last_error = ?,
                       next_attempt_at =
                         now() + (least(power(2, least(attempts + 1, 8)), 300) || ' seconds')
                                 ::interval,
                       -- Lease dropped here too: the backoff is what holds the tenant back now,
                       -- and holding a lease on top of it would delay the retry by whichever is
                       -- longer rather than by the backoff.
                       leased_until = now(), leased_by = NULL, updated_at = now()
                 WHERE tenant_id = ?
                """,
                why,
                tenantId));
  }

  /**
   * POSTs one entry as a CloudEvent.
   *
   * @return true when the consumer accepted it
   */
  private boolean deliver(String tenantId, AuditQuery.Entry entry) {
    String eventId = tenantId + ":" + entry.seq();
    // The event carries the entry's own hash. A consumer holding the stream can therefore verify
    // the chain itself, without trusting this installation to tell it the chain is intact — which
    // is most of the reason to hold the trail outside the system being audited.
    Map<String, Object> event =
        Map.ofEntries(
            Map.entry("specversion", "1.0"),
            Map.entry("type", "bre.studio.audit.v1"),
            Map.entry("source", "/bre/policy-registry"),
            Map.entry("id", eventId),
            Map.entry("subject", entry.subjectKind() + "/" + entry.subjectKey()),
            Map.entry("time", entry.occurredAt().toString()),
            Map.entry("datacontenttype", "application/json"),
            Map.entry(
                "data",
                Map.ofEntries(
                    Map.entry("tenantId", tenantId),
                    Map.entry("seq", entry.seq()),
                    Map.entry("action", entry.action()),
                    Map.entry("actor", entry.actor()),
                    Map.entry("actorType", entry.actorType()),
                    Map.entry("subjectKind", entry.subjectKind()),
                    Map.entry("subjectKey", entry.subjectKey()),
                    Map.entry("detail", entry.detail()),
                    Map.entry("sourceIp", entry.sourceIp() == null ? "" : entry.sourceIp()),
                    Map.entry("client", entry.client() == null ? "" : entry.client()),
                    Map.entry("entryHash", entry.entryHash()))));

    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(timeout)
            .header("Content-Type", "application/cloudevents+json")
            .header("ce-id", eventId)
            .header("ce-type", "bre.studio.audit.v1")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(event)));
    if (!authorization.isEmpty()) {
      request.header("Authorization", authorization);
    }

    try {
      HttpResponse<String> response =
          client.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 == 2) {
        return true;
      }
      // A 4xx is not retried into oblivion by raising attempts faster — it is still backed off,
      // because unlike a decision event there is nothing to dead-letter: the entry cannot be
      // dropped, so the only options are to keep trying and to make the failure visible.
      refused(tenantId, "HTTP " + response.statusCode());
      log.warn(
          "studio audit consumer refused tenant={} seq={} status={}",
          tenantId,
          entry.seq(),
          response.statusCode());
      return false;
    } catch (java.io.IOException unreachable) {
      refused(tenantId, unreachable.getClass().getSimpleName());
      return false;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      refused(tenantId, "interrupted");
      return false;
    }
  }
}
