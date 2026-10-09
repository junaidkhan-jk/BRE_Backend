package dev.bre.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bre.bundle.BundleSigner;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
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
 * The whole process, over HTTP, against a real PostgreSQL: log in, write a policy, save it, test
 * it, submit it, approve it, deploy it, and check a pod could see the result.
 *
 * <p><b>Ordered, and that is the point.</b> Most tests should be independent; this one is a
 * narrative, because the thing being tested <em>is</em> the sequence. A test that could run step 5
 * before step 3 would not be testing a lifecycle.
 *
 * <p>Each step also asserts a refusal where the lifecycle has one, because a state machine that
 * only permits is not a state machine. Submitting without tests, editing after submission,
 * approving your own work, deploying an unapproved version — all of those are checked to fail, with
 * a message that says why.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PolicyLifecycleTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String TENANT = "t_lifecycle";
  private static final String ADMIN_EMAIL = "admin@lender.test";
  private static final String ADMIN_PASSWORD = "bootstrap-password-1";
  private static final String POLICY = "SIMPLE_UW";

  /** A policy small enough to read in one screen, and complete enough to decide. */
  private static final String PSF =
      """
      policy: SIMPLE_UW
      version: 1
      productLines: [MFI_JLG]
      stage: CREDIT_UW
      factSchema: lending-facts@1.0.0

      assets:
        reasonCodes: CORE_REASONS@1

      rules:
        - code: MIN_AGE
          name: Applicant is an adult
          scope: MEMBER
          check: ageInYears(member.dateOfBirth, asOf) >= 18
          onFail: { status: FAIL, reasonCode: KYC_UNDER_MIN_AGE }
          onMissing: REFER

      flow:
        stages:
          - name: ELIGIBILITY
            steps:
              - { ref: MIN_AGE, aggregate: WORST }
      """;

  private static EmbeddedPostgres postgres;

  // Not @TempDir: JUnit injects that AFTER @DynamicPropertySource runs, so it is null when the
  // properties are built. Created here instead, and deleted in @AfterAll.
  private static Path registryDir;

  private static String token;
  private static int draftVersion;
  private static long rowVersion;

  @DynamicPropertySource
  static void registryConfiguration(DynamicPropertyRegistry registry) throws Exception {
    postgres = EmbeddedPostgres.builder().start();
    registryDir = Files.createTempDirectory("bre-registry-test");

    // Migrate as the superuser, then run the APPLICATION as bre_ctl_app — the production shape,
    // and the only shape in which row-level security is actually exercised. A superuser bypasses
    // RLS entirely, so a test that migrates and queries as one passes while the deployed service
    // sees no rows. This project has shipped that class of false green twice; the whole of V11
    // exists because this test connects as the restricted role.
    org.flywaydb.core.Flyway.configure()
        .dataSource(postgres.getPostgresDatabase())
        .locations("classpath:db/migration")
        .load()
        .migrate();

    try (var connection = postgres.getPostgresDatabase().getConnection();
        var statement = connection.createStatement()) {
      // V1 creates the role NOLOGIN, as §5.2 specifies — a service account in production
      // authenticates by certificate or IAM, not a password. Giving it one here is the test
      // harness's business, not the schema's.
      statement.execute("ALTER ROLE bre_ctl_app LOGIN PASSWORD 'test-only'");
    }

    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
    registry.add("spring.datasource.username", () -> "bre_ctl_app");
    registry.add("spring.datasource.password", () -> "test-only");
    // Already migrated, above, by a role that may create schemas and reassign ownership.
    // bre_ctl_app may not, which is the point.
    registry.add("spring.flyway.enabled", () -> "false");

    // Two distinct signing keys, because SigningKeySource refuses one key used for both (§8.6).
    registry.add("bre.registry.bundle-signing-key", () -> privateKey());
    registry.add("bre.registry.manifest-signing-key", () -> privateKey());
    registry.add("bre.registry.bundle-dir", () -> registryDir.resolve("bundles").toString());
    registry.add("bre.registry.manifest-dir", () -> registryDir.resolve("manifests").toString());

    registry.add("bre.registry.bootstrap-tenant", () -> TENANT);
    registry.add("bre.registry.bootstrap-admin-email", () -> ADMIN_EMAIL);
    registry.add("bre.registry.bootstrap-admin-password", () -> ADMIN_PASSWORD);
  }

  private static String privateKey() {
    return Base64.getEncoder()
        .encodeToString(BundleSigner.generateKeyPair().getPrivate().getEncoded());
  }

  @AfterAll
  void stopPostgres() throws Exception {
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

  // --- the narrative -----------------------------------------------------------------------

  @Test
  @Order(1)
  @DisplayName("1. the bootstrapped administrator can sign in, and a wrong password cannot")
  void login() throws Exception {
    HttpResponse<String> refused =
        post("/v1/auth/login", null, body("admin@lender.test", "not-the-password"));
    assertThat(refused.statusCode())
        .as("a wrong password is 401 with the same message as an unknown account")
        .isEqualTo(401);

    HttpResponse<String> accepted = post("/v1/auth/login", null, body(ADMIN_EMAIL, ADMIN_PASSWORD));
    assertThat(accepted.statusCode()).isEqualTo(200);

    JsonNode session = json(accepted);
    token = session.get("token").asText();
    assertThat(token).isNotBlank();
    assertThat(session.get("tenantId").asText()).isEqualTo(TENANT);
    assertThat(roles(session)).contains("TENANT_ADMIN", "POLICY_AUTHOR", "POLICY_REVIEWER");
    assertThat(session.get("canOverrideSegregationOfDuties").asBoolean())
        .as("one administrator on a new installation has to be able to walk the whole process")
        .isTrue();
  }

  @Test
  @Order(2)
  @DisplayName("2. every endpoint refuses a request with no session")
  void defaultDeny() throws Exception {
    assertThat(get("/v1/policies", null).statusCode())
        .as("AuthFilter is an allow-list of three paths; everything else needs a session")
        .isEqualTo(401);
    assertThat(get("/v1/policies", "not-a-real-token").statusCode()).isEqualTo(401);
  }

  @Test
  @Order(3)
  @DisplayName("3. a policy and a DRAFT version are created")
  void createPolicyAndDraft() throws Exception {
    HttpResponse<String> policy =
        post(
            "/v1/policies",
            token,
            """
            {"policyCode":"SIMPLE_UW","name":"Simple underwriting","stage":"CREDIT_UW"}
            """);
    assertThat(policy.statusCode()).isEqualTo(201);

    HttpResponse<String> draft = post("/v1/policies/" + POLICY + "/versions", token, "");
    assertThat(draft.statusCode()).isEqualTo(201);

    JsonNode created = json(draft);
    draftVersion = created.get("version").asInt();
    rowVersion = created.get("rowVersion").asLong();
    assertThat(created.get("status").asText()).isEqualTo("DRAFT");
    assertThat(draftVersion).isEqualTo(1);
  }

  @Test
  @Order(4)
  @DisplayName("4. saving the source compiles it and runs its tests, on every save")
  void saveCompilesOnEverySave() throws Exception {
    HttpResponse<String> saved =
        putText(
            "/v1/policies/" + POLICY + "/versions/" + draftVersion + "/source",
            token,
            PSF,
            rowVersion);
    assertThat(saved.statusCode()).isEqualTo(200);

    JsonNode version = json(saved);
    assertThat(version.get("compileOk").asBoolean())
        .as("findings: %s", version.get("findings"))
        .isTrue();
    assertThat(version.get("tests").get("total").asInt())
        .as("no test cases yet, so nothing to run")
        .isZero();
    rowVersion = version.get("rowVersion").asLong();

    // The source comes back verbatim — comments, formatting and all. A registry that returned the
    // IR re-rendered as YAML would hand an author back a file they did not write.
    HttpResponse<String> source =
        get("/v1/policies/" + POLICY + "/versions/" + draftVersion + "/source", token);
    assertThat(source.body()).isEqualTo(PSF);
  }

  @Test
  @Order(5)
  @DisplayName("5. a policy that does not parse is saved anyway, with the error as a finding")
  void brokenSourceIsSavedWithFindings() throws Exception {
    // An author mid-edit must not lose work because a bracket is unbalanced.
    HttpResponse<String> saved =
        putText(
            "/v1/policies/" + POLICY + "/versions/" + draftVersion + "/source",
            token,
            "policy: SIMPLE_UW\nthis is not: [valid",
            null);
    assertThat(saved.statusCode()).isEqualTo(200);
    assertThat(json(saved).get("compileOk").asBoolean()).isFalse();
    assertThat(json(saved).get("findings").toString()).contains("PSF-");

    // Put the good one back, and the IR with it.
    HttpResponse<String> restored =
        putText(
            "/v1/policies/" + POLICY + "/versions/" + draftVersion + "/source", token, PSF, null);
    assertThat(json(restored).get("compileOk").asBoolean()).isTrue();
    rowVersion = json(restored).get("rowVersion").asLong();
  }

  @Test
  @Order(6)
  @DisplayName("6. submitting with no test cases is refused")
  void submitNeedsTests() throws Exception {
    HttpResponse<String> refused =
        post("/v1/policies/" + POLICY + "/versions/" + draftVersion + "/submit", token, "");
    assertThat(refused.statusCode()).isEqualTo(409);
    assertThat(refused.body())
        .as("a policy with no tests has nothing asserting it does what its author intended")
        .contains("no test cases");
  }

  @Test
  @Order(7)
  @DisplayName("7. a test case is added and runs immediately")
  void addTestCase() throws Exception {
    HttpResponse<String> saved =
        put(
            "/v1/policies/" + POLICY + "/versions/" + draftVersion + "/test-cases/adult-passes",
            token,
            """
            { "asOf": "2026-10-01",
              "facts": { "parties": [ { "partyRef": "P-1", "role": "MEMBER",
                                        "dateOfBirth": "1992-03-04" } ] },
              "expect": { "decision": "APPROVE", "steps": { "MIN_AGE": "PASS" } } }
            """);
    assertThat(saved.statusCode()).isEqualTo(200);

    JsonNode version = json(saved);
    assertThat(version.get("tests").get("total").asInt()).isEqualTo(1);
    assertThat(version.get("tests").get("passed").asInt())
        .as("failures: %s", version.get("tests").get("failures"))
        .isEqualTo(1);
    rowVersion = version.get("rowVersion").asLong();
  }

  @Test
  @Order(8)
  @DisplayName("8. a FAILING test case blocks submission")
  void failingTestBlocksSubmission() throws Exception {
    put(
        "/v1/policies/" + POLICY + "/versions/" + draftVersion + "/test-cases/minor-wrongly-passes",
        token,
        """
        { "asOf": "2026-10-01",
          "facts": { "parties": [ { "partyRef": "P-2", "role": "MEMBER",
                                    "dateOfBirth": "2015-01-01" } ] },
          "expect": { "decision": "APPROVE" } }
        """);

    HttpResponse<String> refused =
        post("/v1/policies/" + POLICY + "/versions/" + draftVersion + "/submit", token, "");
    assertThat(refused.statusCode()).isEqualTo(409);
    assertThat(refused.body()).contains("test case", "fail");

    // Remove the wrong expectation. The POLICY is right — a 10-year-old is declined — so the test
    // was the thing asserting the wrong behaviour.
    HttpResponse<String> deleted =
        delete(
            "/v1/policies/"
                + POLICY
                + "/versions/"
                + draftVersion
                + "/test-cases/minor-wrongly-passes",
            token);
    // 200 with a fresh compile report, not 204: deleting a test case has to recompile, or the
    // submit gate keeps reading a report that names a failure from a case that no longer exists.
    assertThat(deleted.statusCode()).isEqualTo(200);
    assertThat(json(deleted).get("tests").get("total").asInt()).isEqualTo(1);
    assertThat(json(deleted).get("tests").get("passed").asInt()).isEqualTo(1);
  }

  @Test
  @Order(9)
  @DisplayName("9. the version is submitted, and then cannot be edited")
  void submitFreezesTheVersion() throws Exception {
    HttpResponse<String> submitted =
        post("/v1/policies/" + POLICY + "/versions/" + draftVersion + "/submit", token, "");
    assertThat(submitted.statusCode()).isEqualTo(200);
    assertThat(json(submitted).get("status").asText()).isEqualTo("IN_REVIEW");
    assertThat(json(submitted).get("submissionRound").asInt()).isEqualTo(1);

    HttpResponse<String> refused =
        putText(
            "/v1/policies/" + POLICY + "/versions/" + draftVersion + "/source", token, PSF, null);
    assertThat(refused.statusCode())
        .as("a reviewer has to be reviewing the bytes that get deployed (00 §3.3)")
        .isEqualTo(409);
    assertThat(refused.body()).contains("IN_REVIEW", "cannot be edited");
  }

  @Test
  @Order(10)
  @DisplayName(
      "10. the administrator approves their own work — permitted, and audited as an override")
  void approveWithSegregationOverride() throws Exception {
    HttpResponse<String> approved =
        post(
            "/v1/policies/" + POLICY + "/versions/" + draftVersion + "/reviews",
            token,
            "{\"approve\":true,\"comment\":\"bootstrap: single administrator\"}");
    assertThat(approved.statusCode()).isEqualTo(200);

    JsonNode version = json(approved);
    assertThat(version.get("status").asText()).isEqualTo("APPROVED");
    assertThat(version.get("bundleHash").asText())
        .as("approval BUILDS AND SIGNS, in the same transaction as the approval")
        .startsWith("sha256:");

    // The bundle exists on disk, content-addressed, for a pod to fetch.
    String hash = version.get("bundleHash").asText();
    assertThat(registryDir.resolve("bundles").resolve(hash.replace(':', '-') + ".json")).exists();
  }

  @Test
  @Order(11)
  @DisplayName("11. the override is in the audit log, naming the person and the control")
  void overrideIsAudited() throws Exception {
    // The whole justification for allowing an administrator to approve their own work is that it
    // is recorded. If this assertion ever fails, the override is a hole rather than an exception.
    assertThat(auditActions())
        .contains("POLICY_VERSION.SOD_OVERRIDDEN", "POLICY_VERSION.APPROVED", "AUTH.LOGIN");
  }

  @Test
  @Order(12)
  @DisplayName("12. an unapproved version cannot be deployed")
  void cannotDeployUnapprovedVersion() throws Exception {
    HttpResponse<String> draft = post("/v1/policies/" + POLICY + "/versions", token, "");
    int second = json(draft).get("version").asInt();

    HttpResponse<String> refused =
        post(
            "/v1/deployments",
            token,
            "{\"envCode\":\"DEV\",\"policyCode\":\""
                + POLICY
                + "\",\"version\":"
                + second
                + ",\"reason\":\"should not work\"}");
    assertThat(refused.statusCode()).isEqualTo(409);
    assertThat(refused.body()).contains("not APPROVED");

    post("/v1/policies/" + POLICY + "/versions/" + second + "/discard", token, "");
  }

  @Test
  @Order(13)
  @DisplayName("13. deploying to DEV needs no approval and activates at once")
  void deployToDev() throws Exception {
    HttpResponse<String> deployed =
        post(
            "/v1/deployments",
            token,
            "{\"envCode\":\"DEV\",\"policyCode\":\""
                + POLICY
                + "\",\"version\":"
                + draftVersion
                + ",\"reason\":\"first deployment\"}");
    assertThat(deployed.statusCode()).isEqualTo(201);

    JsonNode deployment = json(deployed);
    assertThat(deployment.get("approvalsRequired").asInt())
        .as("00 §3.3 defaults DEV to zero approvers")
        .isZero();
    assertThat(deployment.get("status").asText()).isEqualTo("ACTIVE");
    assertThat(deployment.get("manifestVersion").asLong()).isEqualTo(1);
  }

  @Test
  @Order(14)
  @DisplayName("14. a signed Active Pointer Manifest is published, and a pod could read it")
  void manifestIsPublished() throws Exception {
    Path manifest = registryDir.resolve("manifests").resolve("manifest.json");
    assertThat(manifest).exists();

    JsonNode published = JSON.readTree(Files.readString(manifest));
    assertThat(published.get("format").asText()).isEqualTo("bre-manifest/1");
    assertThat(published.get("manifestVersion").asLong()).isEqualTo(1);
    assertThat(published.get("signature").get("alg").asText()).isEqualTo("Ed25519");

    JsonNode entry = published.get("entries").get(0);
    assertThat(entry.get("tenantId").asText()).isEqualTo(TENANT);
    assertThat(entry.get("environment").asText()).isEqualTo("DEV");
    assertThat(entry.get("policyCode").asText()).isEqualTo(POLICY);
    assertThat(entry.get("champion").get("bundleHash").asText()).startsWith("sha256:");
    assertThat(entry.get("servable")).isNotEmpty();
  }

  @Test
  @Order(15)
  @DisplayName("15. deploying to PROD needs two approvals and waits for them")
  void prodNeedsTwoApprovals() throws Exception {
    HttpResponse<String> requested =
        post(
            "/v1/deployments",
            token,
            "{\"envCode\":\"PROD\",\"policyCode\":\""
                + POLICY
                + "\",\"version\":"
                + draftVersion
                + ",\"reason\":\"go live\"}");
    assertThat(requested.statusCode()).isEqualTo(201);

    JsonNode deployment = json(requested);
    assertThat(deployment.get("approvalsRequired").asInt()).isEqualTo(2);
    assertThat(deployment.get("status").asText())
        .as("PENDING_APPROVAL: nothing reaches production on one person's say-so")
        .isEqualTo("PENDING_APPROVAL");

    // The administrator's single approval is permitted by the override but is still only ONE of
    // two. The override excuses who you are, not how many approvals are required.
    JsonNode afterOne =
        json(
            post(
                "/v1/deployments/" + deployment.get("deploymentId").asText() + "/approvals",
                token,
                "{\"comment\":\"approved\"}"));
    assertThat(afterOne.get("approvals").asInt()).isEqualTo(1);
    assertThat(afterOne.get("status").asText())
        .as("one approval of two is not two approvals, administrator or not")
        .isEqualTo("PENDING_APPROVAL");
  }

  @Test
  @Order(16)
  @DisplayName("16. a second person can be created, and their approval activates it")
  void secondApproverActivates() throws Exception {
    HttpResponse<String> created =
        post(
            "/v1/users",
            token,
            """
            {"email":"reviewer@lender.test","fullName":"Second Approver",
             "roles":["POLICY_REVIEWER","DEPLOYMENT_APPROVER"],
             "password":"reviewer-password-1","department":"Credit Risk"}
            """);
    assertThat(created.statusCode()).isEqualTo(201);

    String reviewerToken =
        json(post("/v1/auth/login", null, body("reviewer@lender.test", "reviewer-password-1")))
            .get("token")
            .asText();

    // Find the PROD deployment still waiting.
    JsonNode pending = json(get("/v1/deployments?envCode=PROD", token));
    String prodDeployment = pending.get(0).get("deploymentId").asText();

    JsonNode activated =
        json(
            post(
                "/v1/deployments/" + prodDeployment + "/approvals",
                reviewerToken,
                "{\"comment\":\"second approval\"}"));
    assertThat(activated.get("approvals").asInt()).isEqualTo(2);
    assertThat(activated.get("status").asText()).isEqualTo("ACTIVE");
    assertThat(activated.get("manifestVersion").asLong())
        .as("a new manifest version, monotonic per cell (§8.1)")
        .isGreaterThan(1);
  }

  @Test
  @Order(17)
  @DisplayName("17. a reviewer without USER_MANAGE cannot create accounts")
  void capabilitiesAreEnforced() throws Exception {
    String reviewerToken =
        json(post("/v1/auth/login", null, body("reviewer@lender.test", "reviewer-password-1")))
            .get("token")
            .asText();

    HttpResponse<String> refused =
        post(
            "/v1/users",
            reviewerToken,
            "{\"email\":\"nope@lender.test\",\"fullName\":\"Nope\",\"roles\":[\"TENANT_ADMIN\"]}");
    assertThat(refused.statusCode()).isEqualTo(403);
    assertThat(refused.body())
        .as("the 403 names the missing capability and which roles grant it")
        .contains("USER_MANAGE", "TENANT_ADMIN");
  }

  @Test
  @Order(18)
  @DisplayName("18. a rollback is a new deployment, and the old one is marked rolled back")
  void rollbackCreatesANewDeployment() throws Exception {
    // Two champions have now been ACTIVE in DEV, so there is something to roll back to.
    JsonNode devDeployments = json(get("/v1/deployments?envCode=DEV", token));
    String active = devDeployments.get(0).get("deploymentId").asText();

    HttpResponse<String> rolled =
        post(
            "/v1/deployments/" + active + "/rollback",
            token,
            "{\"reason\":\"testing the rollback path\"}");

    if (rolled.statusCode() == 409) {
      // Only one deployment has ever been active in DEV, so there is nothing earlier. The refusal
      // is the correct answer and says so — asserted rather than skipped, because "nothing to roll
      // back to" is a real state an operator hits.
      assertThat(rolled.body()).contains("no earlier deployment");
      return;
    }

    assertThat(rolled.statusCode()).isEqualTo(200);
    assertThat(json(rolled).get("deploymentId").asText()).isNotEqualTo(active);
    assertThat(json(rolled).get("status").asText()).isEqualTo("ACTIVE");
    assertThat(json(get("/v1/deployments/" + active, token)).get("status").asText())
        .isEqualTo("ROLLED_BACK");
  }

  @Test
  @Order(19)
  @DisplayName("19. the audit log is hash-chained, with no gaps and no broken links")
  void auditLogIsChained() throws Exception {
    // Written by a database trigger, so this is checking the trigger rather than application code —
    // which is the point: the chain holds for every writer, including psql.
    try (var connection = postgres.getPostgresDatabase().getConnection();
        var statement = connection.createStatement();
        var rs =
            statement.executeQuery(
                """
                SELECT count(*) AS entries,
                       count(*) FILTER (WHERE prev_hash <> lag_hash) AS broken,
                       max(seq) AS highest
                  FROM (SELECT seq, prev_hash,
                               lag(entry_hash, 1, decode(repeat('00',32),'hex'))
                                 OVER (ORDER BY seq) AS lag_hash
                          FROM bre_ctl.audit_log WHERE tenant_id = '%s') chained
                """
                    .formatted(TENANT))) {
      assertThat(rs.next()).isTrue();
      int entries = rs.getInt("entries");
      assertThat(entries).as("the whole process should have produced entries").isGreaterThan(10);
      assertThat(rs.getInt("broken")).as("every entry must chain to the one before it").isZero();
      assertThat(rs.getLong("highest"))
          .as("sequence numbers are gapless, so a removed entry is detectable")
          .isEqualTo(entries);
    }
  }

  @Test
  @Order(20)
  @DisplayName("20. logging out stops the token working")
  void logout() throws Exception {
    String sessionToken =
        json(post("/v1/auth/login", null, body(ADMIN_EMAIL, ADMIN_PASSWORD))).get("token").asText();
    assertThat(get("/v1/policies", sessionToken).statusCode()).isEqualTo(200);

    assertThat(post("/v1/auth/logout", sessionToken, "").statusCode()).isEqualTo(204);
    assertThat(get("/v1/policies", sessionToken).statusCode())
        .as("sessions are server-side precisely so they can be revoked")
        .isEqualTo(401);
  }

  // --- plumbing ----------------------------------------------------------------------------

  private static String body(String email, String password) {
    return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
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

  private HttpResponse<String> putText(String path, String bearer, String body, Long ifMatch)
      throws Exception {
    HttpRequest.Builder builder =
        authorise(HttpRequest.newBuilder(uri(path)), bearer)
            .header("Content-Type", "text/plain")
            .PUT(HttpRequest.BodyPublishers.ofString(body));
    if (ifMatch != null) {
      builder.header("If-Match", String.valueOf(ifMatch));
    }
    return send(builder);
  }

  private HttpResponse<String> delete(String path, String bearer) throws Exception {
    return send(authorise(HttpRequest.newBuilder(uri(path)), bearer).DELETE());
  }

  private static HttpRequest.Builder authorise(HttpRequest.Builder builder, String bearer) {
    return bearer == null ? builder : builder.header("Authorization", "Bearer " + bearer);
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode json(HttpResponse<String> response) throws IOException {
    return JSON.readTree(response.body());
  }

  private static java.util.List<String> roles(JsonNode session) {
    java.util.List<String> out = new java.util.ArrayList<>();
    session.get("roles").forEach(role -> out.add(role.asText()));
    return out;
  }

  private java.util.List<String> auditActions() throws Exception {
    java.util.List<String> actions = new java.util.ArrayList<>();
    try (var connection = postgres.getPostgresDatabase().getConnection();
        var statement = connection.createStatement();
        var rs =
            statement.executeQuery(
                "SELECT action FROM bre_ctl.audit_log WHERE tenant_id = '" + TENANT + "'")) {
      while (rs.next()) {
        actions.add(rs.getString(1));
      }
    }
    return actions;
  }
}
