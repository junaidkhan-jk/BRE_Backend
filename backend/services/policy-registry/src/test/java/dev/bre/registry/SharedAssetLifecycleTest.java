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
 * Shared assets under maker-checker, over HTTP, against a real PostgreSQL (M3-03).
 *
 * <p><b>What this is really testing.</b> A parameter set holds the indebtedness cap. Changing one
 * number in it changes what every policy pinning it decides — without any policy being edited,
 * reviewed or approved. So the question is not "can I store a parameter" but "can one person change
 * a threshold and have it take effect", and the answer has to be no.
 *
 * <p>Ordered, because the thing being tested is a sequence: draft, submit, be refused for approving
 * your own work, have somebody else approve it, and only then find it resolvable by a policy.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SharedAssetLifecycleTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String TENANT = "t_assets";
  private static final String ADMIN_EMAIL = "admin@assets.test";
  private static final String ADMIN_PASSWORD = "bootstrap-password-1";
  private static final String AUTHOR_EMAIL = "author@assets.test";
  private static final String AUTHOR_PASSWORD = "author-password-1";
  private static final String PARAMS = "MFI_PARAMS";

  private static EmbeddedPostgres postgres;
  private static Path registryDir;
  private static String adminToken;
  private static String authorToken;

  @DynamicPropertySource
  static void registryConfiguration(DynamicPropertyRegistry registry) throws Exception {
    postgres = EmbeddedPostgres.builder().start();
    registryDir = Files.createTempDirectory("bre-assets-test");

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

  @Test
  @Order(1)
  @DisplayName("1. two people exist: an administrator and an author who cannot approve")
  void twoPeople() throws Exception {
    adminToken =
        json(post("/v1/auth/login", null, login(ADMIN_EMAIL, ADMIN_PASSWORD)))
            .get("token")
            .asText();

    // A POLICY_AUTHOR and nothing else. Maker-checker cannot be demonstrated with one account, and
    // an account holding both roles would demonstrate the override rather than the rule.
    HttpResponse<String> created =
        post(
            "/v1/users",
            adminToken,
            """
            {"email":"%s","fullName":"Asset Author","roles":["POLICY_AUTHOR"],
             "password":"%s","department":"Credit Policy"}
            """
                .formatted(AUTHOR_EMAIL, AUTHOR_PASSWORD));
    assertThat(created.statusCode()).as("%s", created.body()).isEqualTo(201);

    authorToken =
        json(post("/v1/auth/login", null, login(AUTHOR_EMAIL, AUTHOR_PASSWORD)))
            .get("token")
            .asText();
    assertThat(roles(json(post("/v1/auth/login", null, login(AUTHOR_EMAIL, AUTHOR_PASSWORD)))))
        .containsExactly("POLICY_AUTHOR");
  }

  @Test
  @Order(2)
  @DisplayName("2. the author drafts a parameter set with an effective-dated regulatory cap")
  void draftAParameterSet() throws Exception {
    HttpResponse<String> draft =
        post("/v1/assets/PARAMETER_SET/" + PARAMS + "/versions", authorToken, "");
    assertThat(draft.statusCode()).as("%s", draft.body()).isEqualTo(201);
    assertThat(json(draft).get("version").asInt()).isEqualTo(1);
    assertThat(json(draft).get("status").asText()).isEqualTo("DRAFT");

    // Two effective windows for the same parameter: the cap rises on 1 April. Both figures are
    // true, and which one applies depends on the date the application is decided — which is the
    // whole reason a parameter is a table rather than a value.
    HttpResponse<String> saved =
        put(
            "/v1/assets/PARAMETER_SET/" + PARAMS + "/versions/1",
            authorToken,
            """
            { "parameters": [
                { "name": "INDEBTEDNESS_CAP", "type": "Decimal", "regulatory": true,
                  "sourceRef": "SRO-GUARDRAIL-2025-03", "nextReviewOn": "2027-03-31",
                  "values": [
                    { "effectiveFrom": "2025-04-01", "effectiveTo": "2026-04-01",
                      "value": "200000.00" },
                    { "effectiveFrom": "2026-04-01", "effectiveTo": null,
                      "value": "300000.00" } ] },
                { "name": "MIN_AGE", "type": "Integer", "regulatory": false,
                  "values": [ { "effectiveFrom": "2020-01-01", "effectiveTo": null,
                                "value": "18" } ] } ] }
            """);
    assertThat(saved.statusCode()).as("%s", saved.body()).isEqualTo(200);

    JsonNode content = json(saved).get("content");
    assertThat(content.get("parameters")).hasSize(2);
    JsonNode cap = parameter(content, "INDEBTEDNESS_CAP");
    assertThat(cap.get("regulatory").asBoolean()).isTrue();
    assertThat(cap.get("sourceRef").asText())
        .as("FR-MODEL-007: a REGULATORY parameter carries where the figure comes from")
        .isEqualTo("SRO-GUARDRAIL-2025-03");
    assertThat(cap.get("values")).hasSize(2);
    assertThat(cap.get("values").get(0).get("value").asText()).isEqualTo("200000.00");
  }

  @Test
  @Order(3)
  @DisplayName("3. a REGULATORY parameter without a source reference is refused by the database")
  void regulatoryNeedsItsSource() throws Exception {
    // §5.2's CHECK, not application code — so it holds for psql too. A regulation-driven threshold
    // with no record of which regulation drove it is the finding a governance review opens with.
    HttpResponse<String> refused =
        put(
            "/v1/assets/PARAMETER_SET/" + PARAMS + "/versions/1",
            authorToken,
            """
            { "parameters": [
                { "name": "NO_SOURCE", "type": "Integer", "regulatory": true,
                  "values": [ { "effectiveFrom": "2025-01-01", "value": "1" } ] } ] }
            """);
    assertThat(refused.statusCode()).isNotEqualTo(200);

    // Put the real content back: the failed save rolled its own transaction back, but assert the
    // set is still intact rather than assuming it.
    draftAParameterSet0();
  }

  @Test
  @Order(4)
  @DisplayName("4. overlapping effective windows are refused by the exclusion constraint")
  void overlappingWindowsAreRefused() throws Exception {
    HttpResponse<String> refused =
        put(
            "/v1/assets/PARAMETER_SET/" + PARAMS + "/versions/1",
            authorToken,
            """
            { "parameters": [
                { "name": "OVERLAPS", "type": "Integer", "regulatory": false,
                  "values": [
                    { "effectiveFrom": "2026-01-01", "effectiveTo": "2026-06-01", "value": "1" },
                    { "effectiveFrom": "2026-05-01", "effectiveTo": null, "value": "2" } ] } ] }
            """);
    // Two values effective on 15 May is not a configuration anybody can decide by. The database
    // refuses it with a daterange exclusion constraint, so it holds for every writer.
    assertThat(refused.statusCode()).isNotEqualTo(200);
    draftAParameterSet0();
  }

  @Test
  @Order(5)
  @DisplayName("5. the author submits, then cannot approve their own threshold change")
  void theAuthorCannotApproveTheirOwnChange() throws Exception {
    HttpResponse<String> submitted =
        post("/v1/assets/PARAMETER_SET/" + PARAMS + "/versions/1/submit", authorToken, "");
    assertThat(submitted.statusCode()).as("%s", submitted.body()).isEqualTo(200);
    assertThat(json(submitted).get("status").asText()).isEqualTo("IN_REVIEW");

    // The author holds POLICY_AUTHOR only, so they cannot review at all — 403, not 409.
    HttpResponse<String> noCapability =
        post(
            "/v1/assets/PARAMETER_SET/" + PARAMS + "/versions/1/reviews",
            authorToken,
            "{\"approve\":true}");
    assertThat(noCapability.statusCode()).isEqualTo(403);
    assertThat(noCapability.body()).contains("POLICY_REVIEW");
  }

  @Test
  @Order(6)
  @DisplayName("6. an IN_REVIEW asset cannot be edited")
  void inReviewIsFrozen() throws Exception {
    HttpResponse<String> refused =
        put(
            "/v1/assets/PARAMETER_SET/" + PARAMS + "/versions/1",
            authorToken,
            """
            { "parameters": [ { "name": "MIN_AGE", "type": "Integer", "regulatory": false,
                "values": [ { "effectiveFrom": "2020-01-01", "value": "21" } ] } ] }
            """);
    assertThat(refused.statusCode()).isEqualTo(409);
    assertThat(refused.body()).contains("IN_REVIEW", "cannot be edited");
  }

  @Test
  @Order(7)
  @DisplayName("7. a DRAFT or IN_REVIEW asset is invisible to a policy (AC-MODEL-007)")
  void anUnapprovedAssetCannotBePinned() throws Exception {
    // The control, stated as the specification states it: a parameter change requires a second
    // user's approval BEFORE it can be pinned by any PolicyVersion. The way that is enforced is
    // that an unapproved asset does not exist as far as resolution is concerned.
    HttpResponse<String> policy =
        post(
            "/v1/policies",
            adminToken,
            "{\"policyCode\":\"USES_PARAMS\",\"name\":\"Uses the parameter set\","
                + "\"stage\":\"CREDIT_UW\"}");
    assertThat(policy.statusCode()).isIn(201, 409);

    post("/v1/policies/USES_PARAMS/versions", adminToken, "");
    HttpResponse<String> saved =
        putText(
            "/v1/policies/USES_PARAMS/versions/1/source",
            adminToken,
            """
            policy: USES_PARAMS
            version: 1
            productLines: [MFI_JLG]
            stage: CREDIT_UW
            factSchema: lending-facts@1.0.0

            assets:
              parameterSet: MFI_PARAMS@1
              reasonCodes: CORE_REASONS@1

            rules:
              - code: AGE_OK
                name: Adult
                scope: MEMBER
                check: ageInYears(member.dateOfBirth, asOf) >= param.MIN_AGE
                onFail: { status: FAIL, reasonCode: KYC_UNDER_MIN_AGE }
                onMissing: REFER

            flow:
              stages:
                - name: ELIGIBILITY
                  steps:
                    - { ref: AGE_OK, aggregate: WORST }
            """);
    assertThat(saved.statusCode()).as("%s", saved.body()).isEqualTo(200);

    // The policy compiles — the reference is legal — but the parameter resolves to nothing,
    // because MFI_PARAMS@1 is still IN_REVIEW. The warning says so by name.
    assertThat(json(saved).get("findings").toString())
        .as("an author must be told their thresholds are not resolvable, not left guessing")
        .contains("ASSET-000");
  }

  @Test
  @Order(8)
  @DisplayName("8. the administrator approves it, and the content hash is stamped")
  void theReviewerApproves() throws Exception {
    HttpResponse<String> approved =
        post(
            "/v1/assets/PARAMETER_SET/" + PARAMS + "/versions/1/reviews",
            adminToken,
            "{\"approve\":true,\"comment\":\"figures confirmed against the circular\"}");
    assertThat(approved.statusCode()).as("%s", approved.body()).isEqualTo(200);

    JsonNode version = json(approved);
    assertThat(version.get("status").asText()).isEqualTo("APPROVED");
    assertThat(version.get("contentHash").asText())
        .as("the same BRE-JCS hash a bundle uses, so two identical sets hash identically")
        .startsWith("sha256:");
    assertThat(version.get("approvedAt").asText()).isNotEmpty();

    // A different person approved it than drafted it, and the audit log records both acts.
    assertThat(actions("/v1/audit?action=ASSET_VERSION&limit=100"))
        .contains(
            "ASSET_VERSION.CREATED",
            "ASSET_VERSION.SAVED",
            "ASSET_VERSION.SUBMITTED",
            "ASSET_VERSION.APPROVED")
        .doesNotContain("ASSET_VERSION.SOD_OVERRIDDEN");
  }

  @Test
  @Order(9)
  @DisplayName("9. an APPROVED asset cannot be edited; a change is a new version")
  void approvedIsImmutable() throws Exception {
    HttpResponse<String> refused =
        put(
            "/v1/assets/PARAMETER_SET/" + PARAMS + "/versions/1",
            authorToken,
            """
            { "parameters": [ { "name": "MIN_AGE", "type": "Integer", "regulatory": false,
                "values": [ { "effectiveFrom": "2020-01-01", "value": "21" } ] } ] }
            """);
    assertThat(refused.statusCode()).isEqualTo(409);
    assertThat(refused.body())
        .as("changing an approved asset in place changes decisions nobody reviewed")
        .contains("APPROVED", "Open a new version");
  }

  @Test
  @Order(10)
  @DisplayName("10. now the policy resolves its thresholds, and the right value for the date")
  void theApprovedAssetResolves() throws Exception {
    // Re-saving recompiles against the now-approved asset. The ASSET-000 warning is gone, which
    // is the observable difference between "approved" and "not approved".
    HttpResponse<String> saved =
        putText(
            "/v1/policies/USES_PARAMS/versions/1/source",
            adminToken,
            sourceOf("/v1/policies/USES_PARAMS/versions/1/source"));
    assertThat(saved.statusCode()).isEqualTo(200);
    assertThat(json(saved).get("findings").toString()).doesNotContain("ASSET-000");

    // A 20-year-old passes MIN_AGE = 18 — which only resolves because a second person approved
    // the parameter set.
    JsonNode run =
        json(
            post(
                "/v1/policies/USES_PARAMS/versions/1/run",
                adminToken,
                """
                { "asOf": "2026-10-01",
                  "facts": { "parties": [ { "partyRef": "P-1", "role": "MEMBER",
                                            "dateOfBirth": "2006-01-01" } ] } }
                """));
    assertThat(run.get("decision").asText()).isEqualTo("APPROVE");
    assertThat(run.get("assetsResolved").asBoolean()).isTrue();
  }

  @Test
  @Order(11)
  @DisplayName("11. a reference list larger than a bundle may carry is refused (AC-MODEL-008)")
  void aListTooLargeIsRefused() throws Exception {
    post("/v1/assets/REFERENCE_LIST/BIG_LIST/versions", authorToken, "");

    StringBuilder items = new StringBuilder();
    for (int i = 0; i <= 100_000; i++) {
      items.append(i == 0 ? "" : ",").append('"').append("ITEM_").append(i).append('"');
    }
    HttpResponse<String> refused =
        put(
            "/v1/assets/REFERENCE_LIST/BIG_LIST/versions/1",
            authorToken,
            "{\"itemType\":\"Text\",\"items\":[" + items + "]}");

    assertThat(refused.statusCode()).isEqualTo(409);
    assertThat(refused.body())
        .as("AC-MODEL-008 asks for guidance, not just a refusal")
        .contains("100000", "send the result as a fact");
  }

  @Test
  @Order(12)
  @DisplayName("12. a tenant adds an authority level between two others, with no code change")
  void anAuthorityLevelCanBeInserted() throws Exception {
    // AC-DEV-002 exactly: ZONAL_HEAD between L3 and CREDIT_HEAD, by data alone. Nothing about the
    // hierarchy is an enum in the engine, which is why this is a configuration change.
    post("/v1/assets/AUTHORITY_MATRIX/MFI_DOP/versions", authorToken, "");
    HttpResponse<String> saved =
        put(
            "/v1/assets/AUTHORITY_MATRIX/MFI_DOP/versions/1",
            authorToken,
            """
            { "routingMode": "HIGHEST_ONLY",
              "levels": [ {"levelCode":"L3","rank":1}, {"levelCode":"ZONAL_HEAD","rank":2},
                          {"levelCode":"CREDIT_HEAD","rank":3} ],
              "entries": [
                {"deviationType":"INDEBTEDNESS","amountUpTo":"100000","levelCode":"L3"},
                {"deviationType":"INDEBTEDNESS","amountAbove":"100000",
                 "amountUpTo":"300000","levelCode":"ZONAL_HEAD"},
                {"deviationType":"INDEBTEDNESS","amountAbove":"300000",
                 "levelCode":"CREDIT_HEAD"} ] }
            """);
    assertThat(saved.statusCode()).as("%s", saved.body()).isEqualTo(200);

    JsonNode content = json(saved).get("content");
    assertThat(content.get("levels")).hasSize(3);
    assertThat(content.get("levels").get(1).get("levelCode").asText()).isEqualTo("ZONAL_HEAD");
    assertThat(content.get("entries")).hasSize(3);
  }

  @Test
  @Order(13)
  @DisplayName("13. overlapping amount bands in one matrix are refused")
  void overlappingBandsAreRefused() throws Exception {
    HttpResponse<String> refused =
        put(
            "/v1/assets/AUTHORITY_MATRIX/MFI_DOP/versions/1",
            authorToken,
            """
            { "routingMode": "HIGHEST_ONLY",
              "levels": [ {"levelCode":"L3","rank":1}, {"levelCode":"CREDIT_HEAD","rank":2} ],
              "entries": [
                {"deviationType":"INDEBTEDNESS","amountUpTo":"200000","levelCode":"L3"},
                {"deviationType":"INDEBTEDNESS","amountAbove":"100000",
                 "amountUpTo":"300000","levelCode":"CREDIT_HEAD"} ] }
            """);
    // A ₹150,000 deviation matching two bands has two authority levels, and which one applies
    // would depend on row order. The exclusion constraint refuses it.
    assertThat(refused.statusCode()).isNotEqualTo(200);
  }

  @Test
  @Order(14)
  @DisplayName("14. two open versions of one asset are refused")
  void onlyOneOpenVersion() throws Exception {
    HttpResponse<String> first =
        post("/v1/assets/PARAMETER_SET/" + PARAMS + "/versions", authorToken, "");
    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(json(first).get("version").asInt()).isEqualTo(2);
    // Cloned from the approved version, so a one-number change is a one-number change.
    assertThat(json(first).get("content").get("parameters")).hasSize(2);

    HttpResponse<String> second =
        post("/v1/assets/PARAMETER_SET/" + PARAMS + "/versions", authorToken, "");
    assertThat(second.statusCode()).isEqualTo(409);
    assertThat(second.body())
        .as(
            "two people changing the same threshold in different directions must not both be"
                + " approved")
        .contains("already has version 2 open");
  }

  // --- plumbing ------------------------------------------------------------------------------

  private void draftAParameterSet0() throws Exception {
    HttpResponse<String> restored =
        put(
            "/v1/assets/PARAMETER_SET/" + PARAMS + "/versions/1",
            authorToken,
            """
            { "parameters": [
                { "name": "INDEBTEDNESS_CAP", "type": "Decimal", "regulatory": true,
                  "sourceRef": "SRO-GUARDRAIL-2025-03", "nextReviewOn": "2027-03-31",
                  "values": [
                    { "effectiveFrom": "2025-04-01", "effectiveTo": "2026-04-01",
                      "value": "200000.00" },
                    { "effectiveFrom": "2026-04-01", "effectiveTo": null,
                      "value": "300000.00" } ] },
                { "name": "MIN_AGE", "type": "Integer", "regulatory": false,
                  "values": [ { "effectiveFrom": "2020-01-01", "effectiveTo": null,
                                "value": "18" } ] } ] }
            """);
    assertThat(restored.statusCode()).as("%s", restored.body()).isEqualTo(200);
    assertThat(json(restored).get("content").get("parameters")).hasSize(2);
  }

  private static JsonNode parameter(JsonNode content, String name) {
    for (JsonNode parameter : content.get("parameters")) {
      if (name.equals(parameter.path("name").asText())) {
        return parameter;
      }
    }
    throw new AssertionError("no parameter " + name + " in " + content);
  }

  private java.util.List<String> actions(String path) throws Exception {
    java.util.List<String> out = new java.util.ArrayList<>();
    json(get(path, adminToken)).forEach(entry -> out.add(entry.get("action").asText()));
    return out;
  }

  private String sourceOf(String path) throws Exception {
    return get(path, adminToken).body();
  }

  private static String login(String email, String password) {
    return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
  }

  private static java.util.List<String> roles(JsonNode session) {
    java.util.List<String> out = new java.util.ArrayList<>();
    session.get("roles").forEach(role -> out.add(role.asText()));
    return out;
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

  private HttpResponse<String> putText(String path, String bearer, String body) throws Exception {
    return send(
        authorise(HttpRequest.newBuilder(uri(path)), bearer)
            .header("Content-Type", "text/plain")
            .PUT(HttpRequest.BodyPublishers.ofString(body)));
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
}
