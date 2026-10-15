package dev.bre.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bre.bundle.BundleSigner;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The published control-plane OpenAPI document (M6-05).
 *
 * <p><b>This test writes the document into the repository.</b>
 * `docs/api/control-plane.openapi.json` is generated here and committed, which is the point: a
 * portal served only from a running service is a contract nobody can diff. With the file in the
 * tree, adding a path or changing a response shape shows up in a code review as a change to a
 * published contract, which is what it is.
 *
 * <p>It is a snapshot test in one direction only — it refreshes the file rather than failing when
 * it differs. A failing-on-difference snapshot of a generated document trains people to re-run it
 * without reading the diff, which is the same as not having it; a refreshed file that appears in
 * {@code git status} cannot be skipped by a reviewer looking at the commit.
 *
 * <p>What it DOES fail on are the properties an integrator would be misled by: a missing endpoint,
 * an endpoint marked as needing no authentication, or the two warnings in the description going
 * absent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenApiDocumentTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String TENANT = "t_openapi";

  /**
   * Where the committed document lives.
   *
   * <p>Found by walking UP for a marker rather than written as a relative path. A relative path
   * depends on the test's working directory, which Gradle sets to the module — so {@code
   * ../../docs} wrote the file to {@code backend/docs} and the assertion that it exists still
   * passed, because it did exist, in the wrong place. Found by looking for the file afterwards and
   * not finding it where the test said it was.
   */
  private static final Path PUBLISHED =
      repositoryRoot().resolve("docs").resolve("api").resolve("control-plane.openapi.json");

  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      // Two markers, because either alone appears elsewhere: a docs/ directory exists under
      // several modules, and a Gradle settings file exists in backend/ as well as at the root.
      if (Files.isDirectory(candidate.resolve("docs"))
          && Files.isDirectory(candidate.resolve("BRE_PRD"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new AssertionError(
        "could not find the repository root above " + Path.of("").toAbsolutePath());
  }

  private static EmbeddedPostgres postgres;
  private static Path registryDir;

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry registry) throws Exception {
    postgres = EmbeddedPostgres.builder().start();
    registryDir = Files.createTempDirectory("bre-openapi-test");

    org.flywaydb.core.Flyway.configure()
        .dataSource(postgres.getPostgresDatabase())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    try (var connection = postgres.getPostgresDatabase().getConnection();
        var statement = connection.createStatement()) {
      statement.execute("ALTER ROLE bre_ctl_app LOGIN PASSWORD 'test-only'");
    }

    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
    registry.add("spring.datasource.username", () -> "bre_ctl_app");
    registry.add("spring.datasource.password", () -> "test-only");
    registry.add("spring.flyway.enabled", () -> "false");
    registry.add("bre.registry.bundle-signing-key", () -> privateKey());
    registry.add("bre.registry.manifest-signing-key", () -> privateKey());
    registry.add("bre.registry.bundle-dir", () -> registryDir.resolve("bundles").toString());
    registry.add("bre.registry.manifest-dir", () -> registryDir.resolve("manifests").toString());
    registry.add("bre.registry.bootstrap-tenant", () -> TENANT);
    registry.add("bre.registry.bootstrap-admin-email", () -> "admin@openapi.test");
    registry.add("bre.registry.bootstrap-admin-password", () -> "bootstrap-password-1");
    registry.add("bre.openapi.public", () -> "true");
  }

  private static String privateKey() {
    return Base64.getEncoder()
        .encodeToString(BundleSigner.generateKeyPair().getPrivate().getEncoded());
  }

  @AfterAll
  void stop() throws Exception {
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
  @DisplayName("the document describes every endpoint, and is written to docs/api/")
  void theDocumentIsCompleteAndPublished() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode())
        .as("bre.openapi.public is on, so the document needs no session: %s", response.body())
        .isEqualTo(200);

    JsonNode document = JSON.readTree(response.body());
    JsonNode paths = document.get("paths");
    assertThat(paths).as("a document with no paths is a portal describing nothing").isNotEmpty();

    // Every /v1 path a controller declares has to appear. The failure this guards against is a
    // controller springdoc cannot see — a custom mapping, or a class it does not scan — which
    // produces a document that looks complete and quietly omits an endpoint.
    List<String> declared = new ArrayList<>();
    paths.fieldNames().forEachRemaining(declared::add);
    assertThat(declared)
        .contains(
            "/v1/auth/login",
            "/v1/policies",
            "/v1/assets",
            "/v1/deployments",
            "/v1/deployments/schedule",
            "/v1/audit",
            "/v1/audit/events",
            "/v1/audit/verify",
            "/v1/bel/check",
            "/v1/users",
            "/v1/tenant/settings");

    // The description carries the two things an integrator gets wrong, and this is the assertion
    // that stops somebody tidying them away: that this is not the Decision API, and that an IdP
    // token does not carry roles.
    String description = document.get("info").get("description").asText();
    assertThat(description).contains("NOT the Decision API");
    assertThat(description).contains("never from a header or a token claim");
    assertThat(description).contains("Segregation of duties is enforced");

    // Security is declared globally, so no operation reads as open.
    assertThat(document.get("security")).isNotEmpty();
    assertThat(document.get("components").get("securitySchemes").has("bearer")).isTrue();

    // Written into the tree, pretty-printed and with sorted keys so a diff is readable and stable
    // between runs — springdoc's map ordering is not.
    Path target = PUBLISHED;
    Files.createDirectories(target.getParent());
    Files.writeString(
        target,
        JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(
                    JSON.readTree(
                        JSON.writer(
                                com.fasterxml.jackson.databind.SerializationFeature
                                    .ORDER_MAP_ENTRIES_BY_KEYS)
                            .writeValueAsString(document)))
            + System.lineSeparator());
    assertThat(target).exists();
  }

  @Test
  @DisplayName("every mutating endpoint in the document is one the audit-coverage gate knows about")
  void theDocumentAndTheAuditGateAgree() throws Exception {
    // Two inventories of the same API, derived differently — springdoc reads the mappings at
    // runtime, AuditCoverageTest reflects over the controller classes. Asserting they agree on the
    // SHAPE of the API is what makes either of them trustworthy: a mapping springdoc cannot see is
    // an endpoint missing from the published contract, and a controller the reflection misses is
    // an endpoint with no audit declaration. Neither failure is visible from inside one of them.
    JsonNode paths =
        JSON.readTree(
                client
                    .send(
                        HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.ofString())
                    .body())
            .get("paths");

    var mutating = new TreeSet<String>();
    paths
        .properties()
        .forEach(
            path ->
                path.getValue()
                    .fieldNames()
                    .forEachRemaining(
                        method -> {
                          if (List.of("post", "put", "delete", "patch").contains(method)) {
                            mutating.add(
                                method.toUpperCase(java.util.Locale.ROOT) + " " + path.getKey());
                          }
                        }));

    assertThat(mutating)
        .as("the document must describe the endpoints that change state, not only the reads")
        .isNotEmpty();
    // The count is asserted loosely on purpose: pinning it exactly would make every new endpoint
    // fail here as well as in AuditCoverageTest, and one gate per fact is enough.
    assertThat(mutating).hasSizeGreaterThan(20);
  }
}
