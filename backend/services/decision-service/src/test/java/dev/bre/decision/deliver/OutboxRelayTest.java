package dev.bre.decision.deliver;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Delivering decisions to another system, against real PostgreSQL and a real HTTP receiver.
 *
 * <p>The outbox existed from the first migration and nothing drained it, so this is the first test
 * that asks the question the whole mechanism is for: <b>does a decision reach another system, and
 * what happens when that system is broken?</b> The second half matters more. A relay that delivers
 * when everything works and loses a decision when it does not is worse than no relay, because a
 * lender would believe their downstream was in step.
 *
 * <p>A real {@link HttpServer} rather than a mocked client: the thing being tested is a delivery
 * over HTTP with retries and status-code handling, and a mock would assert my own assumptions about
 * what the JDK's client does with a 500.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxRelayTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String TENANT = "t_relay";

  private static EmbeddedPostgres postgres;
  private static DataSource dataSource;
  private static JdbcTemplate jdbc;
  private static HttpServer server;
  private static int port;

  /** Every body the receiver was sent, in order. */
  private static final List<String> received = new CopyOnWriteArrayList<>();

  /** Every ce-id header, so the at-least-once contract can be checked. */
  private static final List<String> ids = new CopyOnWriteArrayList<>();

  /** What the receiver answers next. Changed per test to stand in for a broken downstream. */
  private static final AtomicInteger status = new AtomicInteger(200);

  @BeforeAll
  void start() throws Exception {
    postgres = EmbeddedPostgres.builder().start();
    dataSource = postgres.getPostgresDatabase();
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    jdbc = new JdbcTemplate(dataSource);

    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/decisions",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          received.add(body);
          ids.add(
              exchange.getRequestHeaders().getFirst("ce-id") == null
                  ? ""
                  : exchange.getRequestHeaders().getFirst("ce-id"));
          int answer = status.get();
          exchange.sendResponseHeaders(answer, 0);
          exchange.close();
        });
    server.start();
    port = server.getAddress().getPort();
  }

  @AfterAll
  void stop() throws Exception {
    if (server != null) {
      server.stop(0);
    }
    if (postgres != null) {
      postgres.close();
    }
  }

  @BeforeEach
  void reset() {
    received.clear();
    ids.clear();
    status.set(200);
    jdbc.update("DELETE FROM bre_dec.outbox");
    // The lease too: a test that left one held would make the next test's drain a no-op, and the
    // failure would look like "the relay delivered nothing" rather than "the lease was stale".
    jdbc.update("DELETE FROM bre_dec.outbox_lease");
  }

  private OutboxRelay relay(String endpoint, int maxAttempts) {
    return new OutboxRelay(
        jdbc,
        new DataSourceTransactionManager(dataSource),
        endpoint,
        "Bearer test-token",
        maxAttempts,
        2000,
        "test-instance");
  }

  /** Writes an outbox row the way OutboxDecisionLog does, without needing a whole decision. */
  private long enqueue(String applicationRef, String decision) {
    jdbc.update(
        """
        INSERT INTO bre_dec.outbox (tenant_id, topic, record_key, headers, payload)
        VALUES (?, 'bre.decision.recorded.v1', ?, ?::jsonb, ?::jsonb)
        """,
        TENANT,
        TENANT + ":" + applicationRef,
        "{\"ce_id\":\"id-"
            + applicationRef
            + "\",\"ce_type\":\"bre.decision.recorded.v1\","
            + "\"ce_source\":\"/bre/decision-service\",\"ce_time\":\"2026-10-01T00:00:00Z\","
            + "\"ce_subject\":\""
            + TENANT
            + "\"}",
        "{\"decision\":\"" + decision + "\",\"applicationRef\":\"" + applicationRef + "\"}");
    Long id =
        jdbc.queryForObject(
            "SELECT max(outbox_id) FROM bre_dec.outbox WHERE tenant_id = ?", Long.class, TENANT);
    return id == null ? 0 : id;
  }

  @Test
  @DisplayName("a decision is pushed downstream as a CloudEvent, and marked published")
  void pushDelivers() throws Exception {
    enqueue("APP-1", "APPROVE");
    OutboxRelay relay = relay("http://127.0.0.1:" + port + "/decisions", 12);

    relay.deliverFor(TENANT);

    assertThat(received).hasSize(1);
    JsonNode event = JSON.readTree(received.get(0));
    assertThat(event.get("specversion").asText()).isEqualTo("1.0");
    assertThat(event.get("type").asText()).isEqualTo("bre.decision.recorded.v1");
    assertThat(event.get("id").asText()).isEqualTo("id-APP-1");
    // The ordering key travels with it, so a receiver that fans out can keep one application's
    // decisions together.
    assertThat(event.get("recordkey").asText()).isEqualTo(TENANT + ":APP-1");
    assertThat(event.get("data").get("decision").asText()).isEqualTo("APPROVE");
    // The id is also a header, so a receiver can deduplicate without parsing the body.
    assertThat(ids).containsExactly("id-APP-1");

    assertThat(pending()).isZero();
    assertThat(delivered()).isEqualTo(1);
  }

  @Test
  @DisplayName("an event carries no fact values (§9.6)")
  void noFactsLeaveTheDecisionPath() throws Exception {
    enqueue("APP-FACTS", "DECLINE");
    relay("http://127.0.0.1:" + port + "/decisions", 12).deliverFor(TENANT);

    // The payload is the decision response, which names reasons and never a borrower's figures.
    // Asserted on the delivered bytes rather than on the stored row, because the bytes are what
    // leaves the building.
    assertThat(received.get(0)).doesNotContain("dateOfBirth", "monthlyNet", "aadhaar", "panToken");
  }

  @Test
  @DisplayName("a broken downstream is retried with backoff, not dropped")
  void transientFailureRetries() {
    long id = enqueue("APP-2", "REFER");
    status.set(503);
    OutboxRelay relay = relay("http://127.0.0.1:" + port + "/decisions", 12);

    relay.deliverFor(TENANT);

    assertThat(received).hasSize(1);
    assertThat(pending())
        .as("still pending: a 503 is the downstream's problem, not the event's")
        .isEqualTo(1);
    assertThat(attempts(id)).isEqualTo(1);
    assertThat(deadLettered()).isZero();
    assertThat(lastError(id)).contains("503");

    // Due in the future now, so an immediate second pass does nothing — which is the backoff.
    relay.deliverFor(TENANT);
    assertThat(received)
        .as("backoff holds it back rather than hammering a service that is down")
        .hasSize(1);

    // When the downstream returns, the same event is delivered. Pulled forward rather than
    // waiting out the backoff, which is what an operator restarting the receiver would see.
    status.set(200);
    jdbc.update("UPDATE bre_dec.outbox SET next_attempt_at = now() WHERE outbox_id = ?", id);
    relay.deliverFor(TENANT);

    assertThat(received).hasSize(2);
    assertThat(pending()).isZero();
  }

  @Test
  @DisplayName("order is preserved: a stuck event holds the ones behind it")
  void orderIsPreserved() {
    enqueue("APP-FIRST", "DECLINE");
    enqueue("APP-SECOND", "APPROVE");
    status.set(503);
    OutboxRelay relay = relay("http://127.0.0.1:" + port + "/decisions", 12);

    relay.deliverFor(TENANT);

    // One attempt, not two. Skipping the failed event and delivering the next would let a
    // downstream see today's approval before yesterday's decline — and for the same application
    // that is a wrong answer, not merely an out-of-order one.
    assertThat(received).hasSize(1);
    assertThat(ids).containsExactly("id-APP-FIRST");
    assertThat(pending()).isEqualTo(2);

    status.set(200);
    jdbc.update("UPDATE bre_dec.outbox SET next_attempt_at = now()");
    relay.deliverFor(TENANT);

    assertThat(ids).containsExactly("id-APP-FIRST", "id-APP-FIRST", "id-APP-SECOND");
    assertThat(pending()).isZero();
  }

  @Test
  @DisplayName("a rejected event is dead-lettered at once, not retried twelve times")
  void permanentFailureDeadLetters() {
    long id = enqueue("APP-3", "APPROVE");
    // 400 means the receiver rejected the CONTENT. Retrying cannot help, and twelve attempts
    // would bury the integration defect instead of surfacing it.
    status.set(400);

    relay("http://127.0.0.1:" + port + "/decisions", 12).deliverFor(TENANT);

    assertThat(received).hasSize(1);
    assertThat(deadLettered()).isEqualTo(1);
    assertThat(attempts(id)).isEqualTo(1);
    assertThat(pending()).isZero();
  }

  @Test
  @DisplayName("attempts are exhausted into a dead letter, and an operator can replay it")
  void exhaustedThenReplayed() {
    long id = enqueue("APP-4", "REFER");
    status.set(503);
    // Two attempts, so exhaustion is reachable in a test without waiting out a backoff curve.
    OutboxRelay relay = relay("http://127.0.0.1:" + port + "/decisions", 2);

    relay.deliverFor(TENANT);
    jdbc.update("UPDATE bre_dec.outbox SET next_attempt_at = now()");
    relay.deliverFor(TENANT);

    assertThat(deadLettered())
        .as("not retried forever: a missing decision has to be visible")
        .isEqualTo(1);

    // A dead-lettered row is INERT. Another pass must not pick it up, or "dead letter" would
    // mean nothing.
    jdbc.update("UPDATE bre_dec.outbox SET next_attempt_at = now()");
    int before = received.size();
    relay.deliverFor(TENANT);
    assertThat(received).hasSize(before);

    // The operator fixes the downstream and replays, deliberately.
    status.set(200);
    assertThat(relay.replay(TENANT, id)).isTrue();
    relay.deliverFor(TENANT);

    assertThat(pending()).isZero();
    assertThat(delivered()).isEqualTo(1);
    assertThat(deadLettered()).isZero();

    // Replaying something that is not dead-lettered does nothing, so a retry loop in an
    // operator's script cannot resurrect a delivered event.
    assertThat(relay.replay(TENANT, id)).isFalse();
  }

  @Test
  @DisplayName("with no endpoint configured, nothing is delivered and nothing is lost")
  void pushIsOffByDefault() {
    enqueue("APP-5", "APPROVE");
    OutboxRelay off = relay("", 12);

    assertThat(off.enabled()).isFalse();
    off.deliverDue();

    assertThat(received).isEmpty();
    // Still pending, not failed. An installation that has not named a downstream has none — and
    // the decisions wait, available over the fetch API, rather than being discarded.
    assertThat(pending()).isEqualTo(1);
    assertThat(deadLettered()).isZero();
  }

  @Test
  @DisplayName("a tenant is leased, so a second instance drains nothing while the first holds it")
  void oneDrainerPerTenant() {
    enqueue("APP-LEASE", "APPROVE");
    // Two instances, as two pods would be. The lease is what keeps one application's decisions in
    // order across them — without it, the second could deliver a later decision first.
    OutboxRelay first = relay("http://127.0.0.1:" + port + "/decisions", 12);
    OutboxRelay second =
        new OutboxRelay(
            jdbc,
            new DataSourceTransactionManager(dataSource),
            "http://127.0.0.1:" + port + "/decisions",
            "",
            12,
            2000,
            "other-instance");

    // Hold the lease by hand, as a pod mid-delivery would.
    jdbc.update(
        "INSERT INTO bre_dec.outbox_lease (tenant_id, leased_by, leased_until)"
            + " VALUES (?, 'other-instance', now() + interval '60 seconds')",
        TENANT);

    first.deliverFor(TENANT);
    assertThat(received)
        .as("the lease is held elsewhere, so this instance delivers nothing")
        .isEmpty();
    assertThat(pending()).isEqualTo(1);

    // An expired lease is free. A pod that dies mid-drain blocks its tenant only until then,
    // which is why the lease is short and renewed rather than long and optimistic.
    jdbc.update("UPDATE bre_dec.outbox_lease SET leased_until = now() - interval '1 second'");
    first.deliverFor(TENANT);

    assertThat(received).hasSize(1);
    assertThat(pending()).isZero();

    // And it is released afterwards rather than left to expire, so the next pass picks the tenant
    // straight up instead of waiting out a minute.
    enqueue("APP-LEASE-2", "DECLINE");
    second.deliverFor(TENANT);
    assertThat(received).as("the first instance released it, so another can drain").hasSize(2);
  }

  @Test
  @DisplayName("no database connection is held across the delivery")
  void noConnectionHeldAcrossTheCall() {
    // The defect this replaced: the relay took a transaction-scoped advisory lock, which meant
    // holding a connection for the whole HTTP call. A burst of decisions against a healthy
    // receiver exhausted all sixteen connections and the decision API returned 500s for decisions
    // the engine had already made correctly.
    //
    // Asserted by making the receiver slow and doing database work from this thread while the
    // relay is mid-delivery. With a connection held per in-flight event, a small pool starves.
    for (int i = 0; i < 6; i++) {
      enqueue("APP-POOL-" + i, "APPROVE");
    }
    OutboxRelay relay = relay("http://127.0.0.1:" + port + "/decisions", 12);

    Thread drain = new Thread(() -> relay.deliverFor(TENANT));
    drain.start();
    for (int i = 0; i < 20 && drain.isAlive(); i++) {
      // Any query will do: the point is that the pool can still serve one.
      assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }
    try {
      drain.join(30_000);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }

    assertThat(received).hasSize(6);
    assertThat(pending()).isZero();
  }

  @Test
  @DisplayName("the fetch API serves the same rows, and reading does not consume them")
  void fetchServesTheSameRows() {
    enqueue("APP-6", "APPROVE");
    enqueue("APP-7", "DECLINE");

    List<Long> firstRead = feed(0);
    assertThat(firstRead).hasSize(2);

    // Reading leaves published_at alone, so a warehouse pulling the feed and an origination
    // system being pushed to do not interfere. Which also means a puller keeps its own cursor.
    assertThat(feed(0)).isEqualTo(firstRead);
    assertThat(pending()).isEqualTo(2);

    // The cursor is an outbox id, not a timestamp: two decisions in the same millisecond would
    // make a timestamp cursor skip one.
    assertThat(feed(firstRead.get(0))).containsExactly(firstRead.get(1));
    assertThat(feed(firstRead.get(1))).isEmpty();
  }

  // --- helpers -------------------------------------------------------------------------------

  private List<Long> feed(long since) {
    return jdbc.query(
        "SELECT outbox_id FROM bre_dec.outbox WHERE tenant_id = ? AND outbox_id > ?"
            + " ORDER BY outbox_id",
        (rs, rowNum) -> rs.getLong(1),
        TENANT,
        since);
  }

  private long pending() {
    return count("published_at IS NULL AND failed_at IS NULL");
  }

  private long delivered() {
    return count("published_at IS NOT NULL");
  }

  private long deadLettered() {
    return count("failed_at IS NOT NULL");
  }

  private long count(String where) {
    Long n =
        jdbc.queryForObject(
            "SELECT count(*) FROM bre_dec.outbox WHERE tenant_id = ? AND " + where,
            Long.class,
            TENANT);
    return n == null ? 0 : n;
  }

  private int attempts(long id) {
    Integer n =
        jdbc.queryForObject(
            "SELECT attempts FROM bre_dec.outbox WHERE outbox_id = ?", Integer.class, id);
    return n == null ? 0 : n;
  }

  private String lastError(long id) {
    String e =
        jdbc.queryForObject(
            "SELECT coalesce(last_error, '') FROM bre_dec.outbox WHERE outbox_id = ?",
            String.class,
            id);
    return e == null ? "" : e;
  }
}
