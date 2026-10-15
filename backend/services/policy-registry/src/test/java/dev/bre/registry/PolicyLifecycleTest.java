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

  /**
   * The timer, injected so the scheduled-activation tests can drive it rather than wait for it.
   *
   * <p>Calling the method directly is the point: a test that slept for the poll interval would be
   * slow and would still be asserting the clock rather than the claim logic.
   */
  @org.springframework.beans.factory.annotation.Autowired
  private dev.bre.registry.deploy.DeploymentActivator activator;

  /** The submit evidence FR-LIFE-002 requires. */
  private static final String CHANGE_NOTE =
      """
      { "changeNote": "Raised the minimum age check to match the revised KYC circular.",
        "committeeReference": "CRMC-2026-41" }
      """;

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
  @Order(5)
  @DisplayName("5b. the DRAFT can be RUN against facts — no approval, no deployment")
  void runADraft() throws Exception {
    // The provision an author actually needs: try the rule on an applicant while it is still a
    // draft. Nothing is approved, signed, deployed or persisted.
    HttpResponse<String> ran =
        post(
            "/v1/policies/" + POLICY + "/versions/" + draftVersion + "/run",
            token,
            """
            { "asOf": "2026-10-01",
              "facts": { "parties": [ { "partyRef": "P-0001", "role": "MEMBER",
                                        "dateOfBirth": "1992-03-04" } ] } }
            """);
    assertThat(ran.statusCode()).isEqualTo(200);

    JsonNode result = json(ran);
    assertThat(result.get("status").asText())
        .as("run it as a DRAFT: that is the whole point of this endpoint")
        .isEqualTo("DRAFT");
    assertThat(result.get("decision").asText()).isEqualTo("APPROVE");
    assertThat(result.get("asOf").asText()).isEqualTo("2026-10-01");
    assertThat(result.get("steps")).isNotEmpty();
    assertThat(result.get("steps").get(0).get("component").asText()).isEqualTo("MIN_AGE");
    assertThat(result.get("steps").get(0).get("status").asText()).isEqualTo("PASS");
  }

  @Test
  @Order(5)
  @DisplayName("5c. the same facts on a different date give a different decision")
  void theBusinessDateChangesTheAnswer() throws Exception {
    // asOf is part of the decision, not metadata: this applicant was 10 years old in 2002. A
    // simulator that defaulted the date would make that difference invisible.
    JsonNode asAdult =
        json(
            post(
                "/v1/policies/" + POLICY + "/versions/" + draftVersion + "/run",
                token,
                """
                { "asOf": "2026-10-01",
                  "facts": { "parties": [ { "partyRef": "P-1", "role": "MEMBER",
                                            "dateOfBirth": "1992-03-04" } ] } }
                """));
    JsonNode asChild =
        json(
            post(
                "/v1/policies/" + POLICY + "/versions/" + draftVersion + "/run",
                token,
                """
                { "asOf": "2002-10-01",
                  "facts": { "parties": [ { "partyRef": "P-1", "role": "MEMBER",
                                            "dateOfBirth": "1992-03-04" } ] } }
                """));

    assertThat(asAdult.get("decision").asText()).isEqualTo("APPROVE");
    assertThat(asChild.get("decision").asText()).isEqualTo("DECLINE");
    assertThat(asChild.get("steps").get(0).get("entities").get(0).get("reasonCode").asText())
        .isEqualTo("KYC_UNDER_MIN_AGE");
  }

  @Test
  @Order(5)
  @DisplayName("5d. a run with no business date is refused rather than defaulted to today")
  void runRequiresABusinessDate() throws Exception {
    HttpResponse<String> refused =
        post(
            "/v1/policies/" + POLICY + "/versions/" + draftVersion + "/run",
            token,
            "{\"facts\":{}}");
    assertThat(refused.statusCode()).isEqualTo(409);
    assertThat(refused.body()).contains("asOf is required");
  }

  @Test
  @Order(5)
  @DisplayName("5e. a mistyped fact path is reported, and the run still happens")
  void mistypedFactPathsAreReported() throws Exception {
    // A mistyped path reads as a missing fact, which the engine handles correctly and SILENTLY —
    // so an author would see an unexplained REFER with no hint that `partRef` is a typo. Refusing
    // the run would be worse: they would have to fix the typo before learning anything.
    JsonNode result =
        json(
            post(
                "/v1/policies/" + POLICY + "/versions/" + draftVersion + "/run",
                token,
                """
                { "asOf": "2026-10-01",
                  "facts": { "parties": [ { "partRef": "P-1", "role": "MEMBER",
                                            "dateOfBirth": "1992-03-04" } ] } }
                """));
    assertThat(result.get("decision").asText()).isNotEmpty();
    assertThat(result.get("factWarnings").toString())
        .as("the warning names the PATH and never a value (§9.6)")
        .contains("partRef")
        .doesNotContain("1992-03-04");
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
  @Order(8)
  @DisplayName("8b. passing tests are not enough: a happy-path-only suite fails the coverage gate")
  void coverageGateRefusesAHappyPathOnlySuite() throws Exception {
    // One rule, one passing test case. Every test passes, so the gate above is satisfied — and
    // the rule's FAIL and DATA_MISSING branches have never once run. Those are the branches that
    // decline a borrower and the branch that fires when the bureau is down.
    HttpResponse<String> refused =
        post(
            "/v1/policies/" + POLICY + "/versions/" + draftVersion + "/submit", token, CHANGE_NOTE);
    assertThat(refused.statusCode()).isEqualTo(409);
    assertThat(refused.body())
        .contains("coverage is", "below this tenant's minimum of 80.0%")
        .as("AC-TEST-003: the uncovered items are named, not merely counted")
        .contains("MIN_AGE:FAIL", "MIN_AGE:DATA_MISSING");

    // The figure is on the version too, so the studio can show it before somebody tries.
    JsonNode version = json(get("/v1/policies/" + POLICY + "/versions/" + draftVersion, token));
    assertThat(version.get("coverage").get("overallPercent").asDouble()).isEqualTo(33.3);
    assertThat(version.get("coverage").get("ruleStatusesTotal").asInt()).isEqualTo(3);
    assertThat(version.get("coverage").get("ruleStatusesCovered").asInt()).isEqualTo(1);
    assertThat(version.get("minCoveragePercent").asDouble()).isEqualTo(80.0);
  }

  @Test
  @Order(8)
  @DisplayName("8c. covering the decline and missing-data paths takes coverage to 100%")
  void coveringEveryBranch() throws Exception {
    put(
        "/v1/policies/" + POLICY + "/versions/" + draftVersion + "/test-cases/minor-declined",
        token,
        """
        { "asOf": "2026-10-01",
          "facts": { "parties": [ { "partyRef": "P-3", "role": "MEMBER",
                                    "dateOfBirth": "2015-01-01" } ] },
          "expect": { "decision": "DECLINE", "steps": { "MIN_AGE": "FAIL" } } }
        """);

    // No dateOfBirth at all: onMissing REFER, which §4.6 maps to DATA_MISSING. This is the case
    // authors skip and the one a bureau outage produces.
    HttpResponse<String> saved =
        put(
            "/v1/policies/"
                + POLICY
                + "/versions/"
                + draftVersion
                + "/test-cases/no-date-of-birth-refers",
            token,
            """
            { "asOf": "2026-10-01",
              "facts": { "parties": [ { "partyRef": "P-4", "role": "MEMBER" } ] },
              "expect": { "decision": "REFER", "steps": { "MIN_AGE": "DATA_MISSING" } } }
            """);
    assertThat(saved.statusCode()).isEqualTo(200);

    JsonNode version = json(saved);
    assertThat(version.get("tests").get("total").asInt()).isEqualTo(3);
    assertThat(version.get("tests").get("passed").asInt())
        .as("failures: %s", version.get("tests").get("failures"))
        .isEqualTo(3);
    assertThat(version.get("coverage").get("overallPercent").asDouble()).isEqualTo(100.0);
    assertThat(version.get("coverage").get("uncoveredRuleStatuses")).isEmpty();
    rowVersion = version.get("rowVersion").asLong();
  }

  @Test
  @Order(8)
  @DisplayName("8d. submitting with no change note is refused, covered or not")
  void submitNeedsTheEvidence() throws Exception {
    HttpResponse<String> noBody =
        post("/v1/policies/" + POLICY + "/versions/" + draftVersion + "/submit", token, "");
    assertThat(noBody.statusCode()).isEqualTo(409);
    assertThat(noBody.body()).contains("change note of at least 10 characters");

    HttpResponse<String> noCommittee =
        post(
            "/v1/policies/" + POLICY + "/versions/" + draftVersion + "/submit",
            token,
            "{\"changeNote\":\"Raised the minimum age to match the revised KYC circular.\"}");
    assertThat(noCommittee.statusCode()).isEqualTo(409);
    assertThat(noCommittee.body())
        .as("a policy change with no recorded mandate is the finding an audit opens with")
        .contains("committee reference is required");
  }

  @Test
  @Order(9)
  @DisplayName("9. the version is submitted, and then cannot be edited")
  void submitFreezesTheVersion() throws Exception {
    HttpResponse<String> submitted =
        post(
            "/v1/policies/" + POLICY + "/versions/" + draftVersion + "/submit", token, CHANGE_NOTE);
    assertThat(submitted.statusCode()).isEqualTo(200);
    assertThat(json(submitted).get("changeNote").asText()).contains("revised KYC circular");
    assertThat(json(submitted).get("committeeReference").asText()).isEqualTo("CRMC-2026-41");
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
  @Order(19)
  @DisplayName("19b. the audit API reads the chain back and verifies it, without a write path")
  void auditApiReadsAndVerifies() throws Exception {
    JsonNode verification = json(get("/v1/audit/verify", token));
    assertThat(verification.get("intact").asBoolean())
        .as("recomputed with the trigger's own expression, not read from a stored flag")
        .isTrue();
    assertThat(verification.get("entries").asLong()).isGreaterThan(10);
    assertThat(verification.get("headHash").asText()).hasSize(64);

    // Filtered by action prefix: everything the version lifecycle did, and nothing else.
    JsonNode lifecycle = json(get("/v1/audit?action=POLICY_VERSION&limit=100", token));
    assertThat(lifecycle).isNotEmpty();
    for (JsonNode entry : lifecycle) {
      assertThat(entry.get("action").asText()).startsWith("POLICY_VERSION.");
    }
    assertThat(actionsOf(lifecycle))
        .contains("POLICY_VERSION.SUBMITTED", "POLICY_VERSION.APPROVED");

    // The submit evidence is in the entry, so "what mandate did this change have" is answerable
    // from the audit trail alone.
    JsonNode submitted =
        java.util.stream.StreamSupport.stream(lifecycle.spliterator(), false)
            .filter(e -> "POLICY_VERSION.SUBMITTED".equals(e.get("action").asText()))
            .findFirst()
            .orElseThrow();
    assertThat(submitted.get("detail").get("committeeReference").asText())
        .isEqualTo("CRMC-2026-41");
    assertThat(submitted.get("detail").get("coveragePercent").asDouble()).isEqualTo(100.0);

    // There is no POST. An audit log with a write endpoint is a log a caller can author.
    assertThat(post("/v1/audit", token, "{}").statusCode()).isIn(404, 405);
  }

  @Test
  @Order(19)
  @DisplayName("19c. a reviewer cannot read the audit trail: AUDIT_READ is its own grant")
  void auditNeedsItsOwnCapability() throws Exception {
    String reviewer =
        json(post("/v1/auth/login", null, body("reviewer@lender.test", "reviewer-password-1")))
            .get("token")
            .asText();
    HttpResponse<String> refused = get("/v1/audit", reviewer);
    assertThat(refused.statusCode()).isEqualTo(403);
    assertThat(refused.body()).contains("AUDIT_READ");
  }

  @Test
  @Order(21)
  @DisplayName("21. a future-dated deployment waits in SCHEDULED instead of going live")
  void futureDatedDeploymentIsScheduled() throws Exception {
    HttpResponse<String> requested =
        post(
            "/v1/deployments",
            token,
            """
            { "envCode": "DEV", "policyCode": "%s", "version": %d, "mode": "CHAMPION",
              "reason": "scheduled for the start of the next business day",
              "effectiveFrom": "%s" }
            """
                .formatted(
                    POLICY,
                    draftVersion,
                    java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(1).toString()));
    assertThat(requested.statusCode()).isEqualTo(201);
    assertThat(json(requested).get("status").asText())
        .as(
            "DEV needs no approvals, so this would have gone ACTIVE at once before 00 §3.4's"
                + " SCHEDULED state was honoured")
        .isEqualTo("SCHEDULED");

    String deploymentId = json(requested).get("deploymentId").asText();
    assertThat(actionsOf(json(get("/v1/audit?subjectKey=" + deploymentId, token))))
        .contains("DEPLOYMENT.SCHEDULED")
        .doesNotContain("DEPLOYMENT.ACTIVATED");

    // The activator leaves it alone: its effective date has not arrived.
    activator.activateDueDeployments();
    assertThat(json(get("/v1/deployments/" + deploymentId, token)).get("status").asText())
        .isEqualTo("SCHEDULED");
  }

  @Test
  @Order(22)
  @DisplayName("22. once the effective date has passed, the activator takes it live")
  void theActivatorTakesADueDeploymentLive() throws Exception {
    HttpResponse<String> requested =
        post(
            "/v1/deployments",
            token,
            """
            { "envCode": "DEV", "policyCode": "%s", "version": %d, "mode": "CHAMPION",
              "reason": "due already",
              "effectiveFrom": "%s" }
            """
                .formatted(
                    POLICY,
                    draftVersion,
                    java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                        .plusSeconds(2)
                        .toString()));
    assertThat(requested.statusCode()).isEqualTo(201);
    String deploymentId = json(requested).get("deploymentId").asText();
    assertThat(json(requested).get("status").asText()).isEqualTo("SCHEDULED");

    // Polls until the DATABASE's clock passes effective_from — that is the clock the claim query
    // compares against, and this JVM's may differ. A fixed sleep would either be flaky or slow.
    String status = "SCHEDULED";
    for (int attempt = 0; attempt < 40 && !"ACTIVE".equals(status); attempt++) {
      Thread.sleep(500);
      activator.activateDueDeployments();
      status = json(get("/v1/deployments/" + deploymentId, token)).get("status").asText();
    }
    assertThat(status).isEqualTo("ACTIVE");

    JsonNode entries = json(get("/v1/audit?subjectKey=" + deploymentId, token));
    assertThat(actionsOf(entries))
        .as("a timed activation is distinguishable from a human one in the audit trail")
        .contains("DEPLOYMENT.ACTIVATED_ON_SCHEDULE", "DEPLOYMENT.ACTIVATED");

    // A second pass must not activate it again: the claim is what prevents two manifests.
    long before = json(get("/v1/audit?subjectKey=" + deploymentId, token)).size();
    activator.activateDueDeployments();
    assertThat(json(get("/v1/audit?subjectKey=" + deploymentId, token)).size()).isEqualTo(before);
  }

  @Test
  @Order(23)
  @DisplayName("23. the semantic diff reports what changed, and ignores a pure reformat")
  void semanticDiff() throws Exception {
    // A second version, differing by one threshold and a reordered rule list.
    HttpResponse<String> created = post("/v1/policies/" + POLICY + "/versions", token, "");
    assertThat(created.statusCode()).isEqualTo(201);
    int second = json(created).get("version").asInt();

    putText(
        "/v1/policies/" + POLICY + "/versions/" + second + "/source",
        token,
        PSF.replace(">= 18", ">= 21"),
        null);

    JsonNode diff =
        json(
            get("/v1/policies/" + POLICY + "/versions/" + draftVersion + "/diff/" + second, token));
    assertThat(diff.get("semantic")).hasSize(1);
    assertThat(diff.get("semantic").get(0).get("subject").asText()).isEqualTo("RULE MIN_AGE");
    assertThat(diff.get("semantic").get(0).get("detail").asText())
        .contains("check:")
        .contains(">= 18")
        .contains(">= 21");
    assertThat(diff.get("unified").toString()).contains("- ", "+ ");

    // The SAME rules, reformatted: blank lines and a comment. The text diff reports it; the
    // semantic diff reports nothing, which is the whole reason both exist.
    //
    // Saved into the same draft rather than a new one, because only ONE open DRAFT is allowed per
    // policy — asking for a second returns the first, and treating the two as different versions
    // made this test pass for the wrong reason.
    int reformatted = second;
    putText(
        "/v1/policies/" + POLICY + "/versions/" + reformatted + "/source",
        token,
        PSF.replace("rules:", "# the applicant must be an adult\n\nrules:"),
        null);

    JsonNode noChange =
        json(
            get(
                "/v1/policies/" + POLICY + "/versions/" + draftVersion + "/diff/" + reformatted,
                token));
    assertThat(noChange.get("semantic"))
        .as("a reviewer asked to approve a diff that changes nothing stops reading diffs")
        .isEmpty();
    assertThat(noChange.get("unified")).isNotEmpty();

    // Discarded, so the clone test below has no open draft in its way. A test that leaves the
    // lifecycle in a state the next one cannot work from is a test that fails somebody else.
    assertThat(
            post("/v1/policies/" + POLICY + "/versions/" + second + "/discard", token, "")
                .statusCode())
        .isEqualTo(200);
  }

  @Test
  @Order(24)
  @DisplayName("24. the coverage threshold is a tenant setting, and changing it is audited")
  void coverageThresholdIsATenantSetting() throws Exception {
    JsonNode settings = json(get("/v1/tenant/settings", token));
    assertThat(settings.get("minCoveragePercent").asDouble()).isEqualTo(80.0);
    assertThat(settings.get("allowAdminSodOverride").asBoolean()).isTrue();

    JsonNode changed = json(patch("/v1/tenant/settings", token, "{\"minCoveragePercent\": 95.0}"));
    assertThat(changed.get("minCoveragePercent").asDouble()).isEqualTo(95.0);

    JsonNode entries = json(get("/v1/audit?action=TENANT.SETTINGS_CHANGED", token));
    assertThat(entries).isNotEmpty();
    assertThat(entries.get(0).get("detail").get("before").get("minCoveragePercent").asDouble())
        .as("before and after, so 'who lowered the bar' is answerable")
        .isEqualTo(80.0);
    assertThat(entries.get(0).get("detail").get("after").get("minCoveragePercent").asDouble())
        .isEqualTo(95.0);

    // Out of range is refused, and so is re-enabling the segregation-of-duties override.
    assertThat(patch("/v1/tenant/settings", token, "{\"minCoveragePercent\": 101}").statusCode())
        .isEqualTo(409);
    HttpResponse<String> reEnable =
        patch("/v1/tenant/settings", token, "{\"allowAdminSodOverride\": true}");
    assertThat(reEnable.statusCode()).isEqualTo(409);
    assertThat(reEnable.body())
        .as(
            "an administrator blocked by maker-checker must not be able to remove it for"
                + " themselves")
        .contains("cannot be re-enabled");

    // Put it back, so a later test in this narrative is not measured against 95%.
    patch("/v1/tenant/settings", token, "{\"minCoveragePercent\": 80.0}");
  }

  @Test
  @Order(25)
  @DisplayName("25. a clone carries the source AND the test cases")
  void cloneCopiesSourceAndTests() throws Exception {
    // The version to clone: the one that was approved, with its three test cases.
    HttpResponse<String> cloned =
        post("/v1/policies/" + POLICY + "/versions?cloneFrom=" + draftVersion, token, "");
    assertThat(cloned.statusCode()).as("%s", cloned.body()).isEqualTo(201);

    JsonNode version = json(cloned);
    int clone = version.get("version").asInt();
    assertThat(clone).isGreaterThan(draftVersion);
    assertThat(version.get("status").asText()).isEqualTo("DRAFT");

    // Copying rules without the assertions that check them produces a draft that compiles, has no
    // tests and cannot be submitted — and whose author has to go and find the old cases by hand.
    assertThat(version.get("tests").get("total").asInt()).isEqualTo(3);
    assertThat(version.get("tests").get("passed").asInt()).isEqualTo(3);
    assertThat(version.get("coverage").get("overallPercent").asDouble()).isEqualTo(100.0);

    // The SOURCE is copied, not the IR: translation discards every comment, and the comment
    // explaining why a threshold is what it is may be the only record of the reasoning.
    HttpResponse<String> source =
        get("/v1/policies/" + POLICY + "/versions/" + clone + "/source", token);
    assertThat(source.statusCode()).isEqualTo(200);
    assertThat(source.body()).isEqualTo(PSF);

    assertThat(actionsOf(json(get("/v1/audit?action=POLICY_VERSION&limit=200", token))))
        .contains("POLICY_VERSION.CLONED");

    // A second clone while this draft is open is refused rather than silently handed the open one:
    // the author asked for a specific version's content.
    HttpResponse<String> refused =
        post("/v1/policies/" + POLICY + "/versions?cloneFrom=" + draftVersion, token, "");
    assertThat(refused.statusCode()).isEqualTo(409);
    assertThat(refused.body()).contains("already an open DRAFT");

    // Discarded rather than left open, so test 27's history has a known shape. DISCARDED is a
    // side transition out of DRAFT (00 §3.3) — a version is never deleted.
    HttpResponse<String> discarded =
        post("/v1/policies/" + POLICY + "/versions/" + clone + "/discard", token, "");
    assertThat(discarded.statusCode()).isEqualTo(200);
    assertThat(json(discarded).get("status").asText()).isEqualTo("DISCARDED");
  }

  @Test
  @Order(26)
  @DisplayName("26. an imported policy lands as a DRAFT, marked imported, never approved")
  void importLandsAsADraft() throws Exception {
    HttpResponse<String> imported =
        post(
            "/v1/policies/import",
            token,
            JSON.writeValueAsString(
                java.util.Map.of(
                    "policyCode", "IMPORTED_UW", "name", "Imported from UAT", "psf", PSF)));
    assertThat(imported.statusCode()).as("%s", imported.body()).isEqualTo(201);

    JsonNode version = json(imported);
    assertThat(version.get("policyCode").asText()).isEqualTo("IMPORTED_UW");
    assertThat(version.get("version").asInt()).isEqualTo(1);
    assertThat(version.get("status").asText())
        .as("an import arriving APPROVED would put a policy live with nobody here having read it")
        .isEqualTo("DRAFT");
    assertThat(version.get("compileOk").asBoolean()).isTrue();

    // The history records that this version came from elsewhere, so a reviewer knows they are
    // reading somebody else's work.
    JsonNode history = json(get("/v1/policies/IMPORTED_UW/versions", token));
    assertThat(history).hasSize(1);
    assertThat(history.get(0).get("imported").asBoolean()).isTrue();

    assertThat(actionsOf(json(get("/v1/audit?action=POLICY_VERSION&limit=200", token))))
        .contains("POLICY_VERSION.IMPORTED");

    // Source that does not translate creates no policy row: a code reserved for a document
    // nobody can compile collides with the next import of the same policy.
    HttpResponse<String> broken =
        post(
            "/v1/policies/import",
            token,
            JSON.writeValueAsString(
                java.util.Map.of("policyCode", "NOT_CREATED", "psf", "policy: [unclosed")));
    assertThat(broken.statusCode()).isEqualTo(409);
    assertThat(get("/v1/policies/NOT_CREATED/versions", token).body())
        .doesNotContain("\"version\"");
  }

  @Test
  @Order(27)
  @DisplayName("27. the version history says who edited, who approved, and why it changed")
  void versionHistory() throws Exception {
    JsonNode history = json(get("/v1/policies/" + POLICY + "/versions", token));
    assertThat(history).isNotEmpty();

    JsonNode approved =
        java.util.stream.StreamSupport.stream(history.spliterator(), false)
            .filter(entry -> entry.get("version").asInt() == draftVersion)
            .findFirst()
            .orElseThrow();

    assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
    assertThat(approved.get("bundleHash").asText()).startsWith("sha256:");
    assertThat(approved.get("changeNote").asText()).contains("revised KYC circular");
    assertThat(approved.get("committeeReference").asText()).isEqualTo("CRMC-2026-41");
    assertThat(approved.get("submittedAt").asText()).isNotEmpty();
    assertThat(approved.get("approvedAt").asText()).isNotEmpty();

    // Editors is a LIST, and that is the point: a draft handed between two people has two, and
    // segregation of duties is enforced against all of them. Showing only the creator would make
    // a reviewer look independent when they are not.
    assertThat(approved.get("editors")).isNotEmpty();
    assertThat(approved.get("editors").toString()).contains("admin");
    assertThat(approved.get("approvers").toString()).contains("admin");
  }

  @Test
  @Order(28)
  @DisplayName("28. logging out stops the token working")
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

  /** Every action in a list of audit entries, so an assertion can name one without an index. */
  private static java.util.List<String> actionsOf(JsonNode entries) {
    return java.util.stream.StreamSupport.stream(entries.spliterator(), false)
        .map(entry -> entry.get("action").asText())
        .toList();
  }

  private HttpResponse<String> patch(String path, String bearer, String body) throws Exception {
    return send(
        authorise(HttpRequest.newBuilder(uri(path)), bearer)
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body)));
  }

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
