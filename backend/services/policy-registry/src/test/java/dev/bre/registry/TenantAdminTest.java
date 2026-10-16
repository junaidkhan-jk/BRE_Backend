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
import java.util.Base64;
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
 * Tenants, environments and quotas (M3-08, FR-ADM-001/003/005).
 *
 * <p>The last test is the one that matters most and is not in the acceptance criteria: a lender's
 * own administrator must not be able to make themselves a platform operator. ROLE_MANAGE lets an
 * administrator grant roles and TENANT_ADMIN carries it, so before M3-08 anybody holding it could
 * grant themselves PLATFORM_ADMIN. That was harmless only because nothing consumed PLATFORM_MANAGE
 * — and this milestone is what made it consume it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantAdminTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String TENANT = "t_platform";
  private static final String ADMIN_EMAIL = "operator@platform.test";
  private static final String ADMIN_PASSWORD = "bootstrap-password-1";
  private static final String NEW_TENANT = "t_newlender";

  private static EmbeddedPostgres postgres;
  private static Path registryDir;

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry registry) throws Exception {
    postgres = EmbeddedPostgres.builder().start();
    registryDir = Files.createTempDirectory("bre-platform-test");

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
    registry.add("bre.registry.bootstrap-admin-email", () -> ADMIN_EMAIL);
    registry.add("bre.registry.bootstrap-admin-password", () -> ADMIN_PASSWORD);
    // This installation's first administrator IS the platform operator. Off by default; on here
    // because somebody has to hold PLATFORM_MANAGE or no tenant could ever be provisioned.
    registry.add("bre.registry.bootstrap-platform-admin", () -> "true");
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
  @Order(1)
  @DisplayName("1. a tenant is provisioned with its environments and a quota, in one call")
  void provisioning() throws Exception {
    String token = login();

    HttpResponse<String> created =
        post(
            "/v1/platform/tenants",
            token,
            "{\"tenantId\":\""
                + NEW_TENANT
                + "\",\"displayName\":\"New Lender\",\"cellId\":1,"
                + "\"timeZone\":\"Asia/Kolkata\"}");
    assertThat(created.statusCode()).as("%s", created.body()).isEqualTo(201);
    assertThat(json(created).get("status").asText())
        .as("a new tenant starts ONBOARDING, not ACTIVE — nothing has been configured yet")
        .isEqualTo("ONBOARDING");

    // DEV, UAT and PROD, created WITH the tenant. A tenant with no environments cannot deploy
    // anything, and a half-provisioned tenant is the state an operator discovers at the worst
    // moment — so it is one call and one transaction.
    JsonNode environments =
        json(get("/v1/platform/tenants/" + NEW_TENANT + "/environments", token));
    assertThat(environments).hasSize(3);
    assertThat(environments.get(0).get("envCode").asText()).isEqualTo("DEV");
    assertThat(environments.get(0).get("minDeploymentApprovers").asInt()).isZero();
    assertThat(environments.get(2).get("envCode").asText()).isEqualTo("PROD");
    assertThat(environments.get(2).get("minDeploymentApprovers").asInt()).isEqualTo(2);
    assertThat(environments.get(2).get("dryRunAllowed").asBoolean())
        .as("a dry run in production would decide for real borrowers")
        .isFalse();

    JsonNode quota = json(get("/v1/platform/tenants/" + NEW_TENANT + "/quota", token));
    assertThat(quota.get("decisionsPerSecond").asInt()).isPositive();
    assertThat(quota.get("burstDecisions").asInt())
        .isGreaterThanOrEqualTo(quota.get("decisionsPerSecond").asInt());
  }

  @Test
  @Order(2)
  @DisplayName("2. provisioning the same tenant twice is refused, not silently accepted")
  void provisioningIsNotIdempotent() throws Exception {
    // Deliberately not idempotent. Silently returning the existing tenant would mean a typo in a
    // new tenant's id hands an operator somebody else's tenant, and every subsequent call in the
    // provisioning script would configure the wrong lender.
    HttpResponse<String> again =
        post(
            "/v1/platform/tenants",
            login(),
            "{\"tenantId\":\"" + NEW_TENANT + "\",\"displayName\":\"Impostor\",\"cellId\":1}");
    assertThat(again.statusCode()).isEqualTo(409);
    assertThat(again.body()).contains("already exists");
  }

  @Test
  @Order(3)
  @DisplayName("3. a tenant adds PERF with its own approver count (AC-ADM-003)")
  void addAnEnvironment() throws Exception {
    String token = login();

    HttpResponse<String> added =
        put(
            "/v1/platform/tenants/" + NEW_TENANT + "/environments",
            token,
            "{\"envCode\":\"PERF\",\"isProduction\":false,\"promotionOrder\":4,"
                + "\"minDeploymentApprovers\":1,\"dryRunAllowed\":true}");
    assertThat(added.statusCode()).as("%s", added.body()).isEqualTo(200);

    JsonNode environments = json(added);
    assertThat(environments).hasSize(4);
    JsonNode perf = environments.get(3);
    assertThat(perf.get("envCode").asText()).isEqualTo("PERF");
    assertThat(perf.get("minDeploymentApprovers").asInt()).isEqualTo(1);
  }

  @Test
  @Order(4)
  @DisplayName("4. a production environment that approves its own deployments is refused")
  void productionNeedsAnApprover() throws Exception {
    HttpResponse<String> refused =
        put(
            "/v1/platform/tenants/" + NEW_TENANT + "/environments",
            login(),
            "{\"envCode\":\"PROD2\",\"isProduction\":true,\"promotionOrder\":5,"
                + "\"minDeploymentApprovers\":0,\"dryRunAllowed\":false}");
    assertThat(refused.statusCode()).isEqualTo(409);
    assertThat(refused.body()).contains("at least one deployment approver");
  }

  @Test
  @Order(5)
  @DisplayName("5. a quota change takes effect immediately and is audited (AC-ADM-005)")
  void quotaChangeIsAudited() throws Exception {
    String token = login();

    HttpResponse<String> set =
        put(
            "/v1/platform/tenants/" + NEW_TENANT + "/quota",
            token,
            "{\"decisionsPerSecond\":500,\"burstDecisions\":1000,\"bulkConcurrency\":4,"
                + "\"bundleMemoryMb\":512}");
    assertThat(set.statusCode()).as("%s", set.body()).isEqualTo(200);
    assertThat(json(set).get("decisionsPerSecond").asInt()).isEqualTo(500);

    // Read back through a fresh request — "takes effect without a restart" is the AC, and a value
    // that only exists in the response of the call that set it would satisfy the assertion above
    // while failing the requirement.
    assertThat(
            json(get("/v1/platform/tenants/" + NEW_TENANT + "/quota", token))
                .get("bundleMemoryMb")
                .asInt())
        .isEqualTo(512);
  }

  @Test
  @Order(6)
  @DisplayName("6. a burst below the sustained rate is refused")
  void burstMustCoverTheRate() throws Exception {
    // A configuration that can only ever reject traffic the tenant is entitled to.
    HttpResponse<String> refused =
        put(
            "/v1/platform/tenants/" + NEW_TENANT + "/quota",
            login(),
            "{\"decisionsPerSecond\":500,\"burstDecisions\":100,\"bulkConcurrency\":1,"
                + "\"bundleMemoryMb\":256}");
    assertThat(refused.statusCode()).isEqualTo(409);
    assertThat(refused.body()).contains("burst below the sustained rate");
  }

  @Test
  @Order(7)
  @DisplayName("7. the platform API needs PLATFORM_MANAGE, which a tenant admin does not hold")
  void platformApiIsNotForTenantAdmins() throws Exception {
    // AC-ADM-001's isolation claim, at the capability level. A lender's administrator configures
    // their own tenant; provisioning one, assigning a cell and granting a quota are the platform
    // operator's, and nothing a TENANT_ADMIN holds reaches this controller.
    String token = login();
    assertThat(post("/v1/platform/tenants", null, "{}").statusCode()).isEqualTo(401);

    // Create a plain tenant administrator with no platform role.
    assertThat(
            post(
                    "/v1/users",
                    token,
                    "{\"email\":\"lender.admin@platform.test\",\"fullName\":\"Lender Admin\","
                        + "\"password\":\"lender-password-1\",\"roles\":[\"TENANT_ADMIN\"]}")
                .statusCode())
        .isIn(200, 201);

    String lenderToken = login("lender.admin@platform.test", "lender-password-1");
    HttpResponse<String> refused =
        post(
            "/v1/platform/tenants",
            lenderToken,
            "{\"tenantId\":\"t_sneaky\",\"displayName\":\"Sneaky\",\"cellId\":1}");
    assertThat(refused.statusCode()).isEqualTo(403);
    assertThat(refused.body()).contains("PLATFORM_MANAGE");
  }

  @Test
  @Order(8)
  @DisplayName("8. a tenant admin cannot promote themselves to platform operator")
  void noPrivilegeEscalationThroughGranting() throws Exception {
    // THE FINDING THIS MILESTONE PRODUCED.
    //
    // ROLE_MANAGE lets an administrator grant roles, and TENANT_ADMIN carries ROLE_MANAGE — so
    // without a check, a lender's own administrator could grant themselves PLATFORM_ADMIN and
    // then provision tenants, reassign cells and change every other lender's quota. The path
    // existed as soon as PLATFORM_ADMIN did; it became exploitable the moment a controller
    // consumed PLATFORM_MANAGE, which is this milestone.
    //
    // The rule is general rather than a deny-list naming PLATFORM_ADMIN: you may grant a role
    // only if you already hold everything it confers. A deny-list needs editing every time a role
    // is added, and the edit that gets forgotten is the one that matters.
    String lenderToken = login("lender.admin@platform.test", "lender-password-1");

    HttpResponse<String> escalation =
        post("/v1/users/lender.admin/roles/PLATFORM_ADMIN", lenderToken, "");
    assertThat(escalation.statusCode()).isEqualTo(403);
    assertThat(escalation.body()).contains("PLATFORM_MANAGE");

    // And they still cannot reach the platform API afterwards.
    assertThat(
            post(
                    "/v1/platform/tenants",
                    lenderToken,
                    "{\"tenantId\":\"t_sneaky2\",\"displayName\":\"Sneaky\",\"cellId\":1}")
                .statusCode())
        .isEqualTo(403);
  }

  // --- plumbing --------------------------------------------------------------------------------

  private String login() throws Exception {
    return login(ADMIN_EMAIL, ADMIN_PASSWORD);
  }

  private String login(String email, String password) throws Exception {
    return JSON.readTree(
            post(
                    "/v1/auth/login",
                    null,
                    "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .body())
        .get("token")
        .asText();
  }

  private static JsonNode json(HttpResponse<String> response) throws Exception {
    return JSON.readTree(response.body());
  }

  private HttpResponse<String> get(String path, String bearer) throws Exception {
    return send(authorise(HttpRequest.newBuilder(uri(path)), bearer).GET());
  }

  private HttpResponse<String> post(String path, String bearer, String body) throws Exception {
    return send(
        authorise(HttpRequest.newBuilder(uri(path)), bearer)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
  }

  private HttpResponse<String> put(String path, String bearer, String body) throws Exception {
    return send(
        authorise(HttpRequest.newBuilder(uri(path)), bearer)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body)));
  }

  private static HttpRequest.Builder authorise(HttpRequest.Builder builder, String bearer) {
    return bearer == null ? builder : builder.header("Authorization", "Bearer " + bearer);
  }

  private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }
}
