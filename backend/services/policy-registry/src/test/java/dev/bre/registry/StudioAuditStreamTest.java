package dev.bre.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.bre.bundle.BundleSigner;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Publishing the studio audit trail on {@code bre.studio.audit.v1} (M3-10, FR-AUD-005).
 *
 * <p>Against a real HTTP consumer rather than a mocked client, because what is under test is a
 * delivery over HTTP with a cursor and a backoff — and a mock would assert my own assumptions about
 * what the JDK client does with a 503.
 *
 * <p>The half that matters is the second: an audit entry that is published once and then lost on a
 * consumer outage is worse than one never published, because a lender's SIEM would show a gap in a
 * gapless sequence and read it as tampering.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StudioAuditStreamTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String TENANT = "t_stream";
  private static final String ADMIN_EMAIL = "admin@stream.test";
  private static final String ADMIN_PASSWORD = "bootstrap-password-1";

  private static EmbeddedPostgres postgres;
  private static Path registryDir;
  private static HttpServer consumer;

  /** Every ce-id the consumer was sent, in order. */
  private static final List<String> ids = new CopyOnWriteArrayList<>();

  /** Every body, so the CloudEvent shape can be checked. */
  private static final List<String> bodies = new CopyOnWriteArrayList<>();

  /** What the consumer answers next. Changed per test to stand in for an outage. */
  private static final AtomicInteger answer = new AtomicInteger(200);

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry registry) throws Exception {
    postgres = EmbeddedPostgres.builder().start();
    registryDir = Files.createTempDirectory("bre-stream-test");

    org.flywaydb.core.Flyway.configure()
        .dataSource(postgres.getPostgresDatabase())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    try (var connection = postgres.getPostgresDatabase().getConnection();
        var statement = connection.createStatement()) {
      statement.execute("ALTER ROLE bre_ctl_app LOGIN PASSWORD 'test-only'");
    }

    consumer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    consumer.createContext(
        "/audit",
        exchange -> {
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          String id = exchange.getRequestHeaders().getFirst("ce-id");
          ids.add(id == null ? "" : id);
          exchange.sendResponseHeaders(answer.get(), 0);
          exchange.close();
        });
    consumer.start();

    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
    registry.add("spring.datasource.username", () -> "bre_ctl_app");
    registry.add("spring.datasource.password", () -> "test-only");
    registry.add("spring.flyway.enabled", () -> "false");
    registry.add("bre.registry.bundle-signing-key", () -> privateKey());
    registry.add("bre.registry.manifest-signing-key", () -> privateKey());
    registry.add("bre.registry.bundle-dir", () -> registryDir.resolve("bundles").toString());
    registry.add("bre.registry.manifest-dir", () -> registryDir.resolve("manifests").toString());
    registry.add("bre.registry.bootstrap-tenant", () -> TENANT);
    registry.add("bre.registry.bootstrap-admin-email", () -> ADMIN_EMAIL);
    registry.add("bre.registry.bootstrap-admin-password", () -> ADMIN_PASSWORD);
    registry.add(
        "bre.studio-audit.endpoint",
        () -> "http://127.0.0.1:" + consumer.getAddress().getPort() + "/audit");
    registry.add("bre.studio-audit.authorization", () -> "Bearer test-only-token");
    registry.add("bre.studio-audit.poll-ms", () -> "300");
  }

  private static String privateKey() {
    return Base64.getEncoder()
        .encodeToString(BundleSigner.generateKeyPair().getPrivate().getEncoded());
  }

  @AfterAll
  void stop() throws Exception {
    if (consumer != null) {
      consumer.stop(0);
    }
    if (postgres != null) {
      postgres.close();
    }
    if (registryDir != null) {
      try (var paths = Files.walk(registryDir)) {
        paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
      }
    }
  }

  @LocalServerPort private int port;

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @Test
  @Order(1)
  @DisplayName("1. the bootstrap's own audit entries reach the consumer as CloudEvents")
  void bootstrapEntriesArePublished() throws Exception {
    // The bootstrap creates a tenant and its first administrator, and audits both. So there is a
    // trail to publish before any test does anything — which is the right starting condition: a
    // consumer that only ever sees entries made after it was configured has a gap at the
    // beginning that looks exactly like a deleted entry.
    waitUntil(() -> !ids.isEmpty(), "the consumer received nothing");

    assertThat(ids).allSatisfy(id -> assertThat(id).startsWith(TENANT + ":"));

    JsonNode event = JSON.readTree(bodies.get(0));
    assertThat(event.get("specversion").asText()).isEqualTo("1.0");
    assertThat(event.get("type").asText()).isEqualTo("bre.studio.audit.v1");
    assertThat(event.get("id").asText()).isEqualTo(TENANT + ":1");
    assertThat(event.get("data").get("seq").asLong()).isEqualTo(1);
    assertThat(event.get("data").get("action").asText()).isNotBlank();

    // The entry's own hash travels with it. A consumer holding the stream can verify the chain
    // itself rather than trusting this installation to say the chain is intact — which is most of
    // the reason to hold the trail outside the system being audited.
    assertThat(event.get("data").get("entryHash").asText()).hasSize(64);
  }

  @Test
  @Order(2)
  @DisplayName("2. a new action is published, exactly once, with the next sequence number")
  void aNewActionIsPublished() throws Exception {
    int before = ids.size();
    String token = login();

    HttpResponse<String> created =
        post(
            "/v1/policies",
            token,
            "{\"policyCode\":\"STREAM_UW\",\"name\":\"Stream\",\"productLines\":[\"MFI_JLG\"],"
                + "\"stage\":\"CREDIT_UW\"}");
    assertThat(created.statusCode()).as("%s", created.body()).isIn(200, 201);

    waitUntil(() -> ids.size() > before, "the new action was not published");

    // Exactly once in the happy path. At-least-once is the contract, so a duplicate would not be
    // a defect — but a duplicate with nothing failing would mean the cursor is not advancing, and
    // that grows without bound.
    assertThat(ids).doesNotHaveDuplicates();

    // Gapless and ascending. The cursor is audit_log.seq, assigned under FOR UPDATE on the chain
    // head, so this is a property of the schema rather than of the relay.
    List<Long> sequences = ids.stream().map(StudioAuditStreamTest::seqOf).toList();
    for (int i = 1; i < sequences.size(); i++) {
      assertThat(sequences.get(i)).isEqualTo(sequences.get(i - 1) + 1);
    }
  }

  @Test
  @Order(3)
  @DisplayName("3. the pull feed returns the same entries, oldest first, and does not consume")
  void theFeedReturnsTheSameEntries() throws Exception {
    String token = login();

    JsonNode feed = JSON.readTree(get("/v1/audit/events?since=0&limit=100", token).body());
    assertThat(feed).isNotEmpty();
    assertThat(feed.get(0).get("seq").asLong()).as("oldest first, unlike /v1/audit").isEqualTo(1);

    // Reading does not consume: the same request twice returns the same rows. A consumer's cursor
    // is its own, and the relay's lives in studio_audit_delivery.
    JsonNode again = JSON.readTree(get("/v1/audit/events?since=0&limit=100", token).body());
    assertThat(again.size()).isEqualTo(feed.size());

    long first = feed.get(0).get("seq").asLong();
    JsonNode after =
        JSON.readTree(get("/v1/audit/events?since=" + first + "&limit=100", token).body());
    assertThat(after.size()).isEqualTo(feed.size() - 1);

    // AUDIT_READ, like the rest of the trail. The stream names who did what, across policies and
    // user administration, and is a separate grant from reading policies.
    assertThat(get("/v1/audit/events", null).statusCode()).isEqualTo(401);
  }

  @Test
  @Order(4)
  @DisplayName("4. a consumer outage holds the cursor: nothing is skipped, and it resumes")
  void anOutageDoesNotLoseAnEntry() throws Exception {
    answer.set(503);
    int delivered = ids.size();
    String token = login();

    assertThat(
            post(
                    "/v1/policies",
                    token,
                    "{\"policyCode\":\"STREAM_TWO\",\"name\":\"Two\","
                        + "\"productLines\":[\"MFI_JLG\"],\"stage\":\"CREDIT_UW\"}")
                .statusCode())
        .isIn(200, 201);

    // Attempted and refused. The entry is NOT lost and the cursor does NOT advance — the thing
    // this test exists for. A relay that skipped it would leave the consumer with a gap in a
    // gapless sequence, which reads as a deleted entry rather than as a failed delivery.
    waitUntil(() -> ids.size() > delivered, "the relay did not even try");
    int attempted = ids.size();

    JsonNode trail = JSON.readTree(get("/v1/audit/events?since=0&limit=500", token).body());
    long lastRecorded = trail.get(trail.size() - 1).get("seq").asLong();

    answer.set(200);
    // Waits for the BACKLOG, not for one more delivery. Two entries were held — the login and the
    // policy — and draining them takes more than one pass, so "one more arrived" would assert
    // completeness while the second was still queued. That is what the first version of this test
    // did, and it failed at seq 4 of 6 for a relay that was working correctly.
    //
    // The same ce-id arrives twice across the outage, which is why a consumer must deduplicate on
    // it — a downstream team assuming exactly-once builds something that breaks on a restart.
    waitUntil(
        () -> highestDelivered() >= lastRecorded,
        "the backlog did not drain once the consumer came back");

    assertThat(ids.size())
        .as("the held entries were retried rather than skipped")
        .isGreaterThan(attempted);
  }

  // --- plumbing --------------------------------------------------------------------------------

  /** The highest seq delivered so far, or 0. */
  private static long highestDelivered() {
    return ids.stream().map(StudioAuditStreamTest::seqOf).max(Long::compare).orElse(0L);
  }

  /** The seq out of a {@code <tenant>:<seq>} ce-id. */
  private static long seqOf(String eventId) {
    return Long.parseLong(eventId.substring(eventId.lastIndexOf(':') + 1));
  }

  /** Polls for a condition, because delivery is a scheduled poll rather than a call. */
  private static void waitUntil(java.util.function.BooleanSupplier done, String whatFailed)
      throws InterruptedException {
    // Up to thirty seconds, because a refusal backs off exponentially — 2s, 4s, 8s — and a test
    // that waits twelve seconds for a backlog held through three refusals fails on a relay that
    // is behaving exactly as designed.
    for (int attempt = 0; attempt < 300; attempt++) {
      if (done.getAsBoolean()) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError(whatFailed + " within 30 seconds");
  }

  private String login() throws Exception {
    return JSON.readTree(
            post(
                    "/v1/auth/login",
                    null,
                    "{\"email\":\"" + ADMIN_EMAIL + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}")
                .body())
        .get("token")
        .asText();
  }

  private HttpResponse<String> get(String path, String bearer) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String bearer, String body) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }
}
