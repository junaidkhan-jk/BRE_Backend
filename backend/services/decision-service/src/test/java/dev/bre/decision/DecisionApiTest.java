package dev.bre.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.bre.bundle.Bundle;
import dev.bre.bundle.BundleSigner;
import dev.bre.bundle.PointerManifest;
import dev.bre.decision.policy.FileBundleStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The walking skeleton, end to end: REST in, a RESOLVED policy, the engine, a durable record,
 * {@code GET} back out.
 *
 * <p>This is M2's exit criterion in test form. It starts the real application on a real port and
 * talks to it over real HTTP with the JDK's own client — nothing is mocked, because what is worth
 * proving is that the pieces fit.
 *
 * <p><b>The policy is no longer on the request.</b> The test seeds signed bundles and a signed
 * Active Pointer Manifest on disk before the context starts, exactly as a registry would deliver
 * them, and the caller names a policy code. That was the largest deviation in the Decision API —
 * accepting policy inline meant the thing being evaluated was whatever the caller sent, which is
 * neither signed, nor content-addressed, nor replayable.
 *
 * <p>The JDK client rather than Spring's: Spring Boot 4 moved MockMvc support into a separate
 * module and removed {@code TestRestTemplate}, and {@code java.net.http.HttpClient} needs no
 * dependency to do the same job.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DecisionApiTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final KeyPair BUNDLE_KEY = BundleSigner.generateKeyPair();
  private static final KeyPair MANIFEST_KEY = BundleSigner.generateKeyPair();

  @TempDir static Path policyDir;

  private static String foirBundleHash;
  private static String brokenBundleHash;
  private static String retiredBundleHash;

  /** Seeds the bundles and the manifest before Spring starts, as a registry would. */
  @DynamicPropertySource
  static void policyDistribution(DynamicPropertyRegistry registry) throws IOException {
    FileBundleStore store = new FileBundleStore(policyDir.resolve("bundles"));

    foirBundleHash =
        seed(
            store,
            rule("MFI_FOIR_CAP", "member.emi / member.income <= 0.5", "POL_FOIR_EXCEEDED"),
            rule("MFI_MIN_AGE", "member.age >= 18", "POL_UNDERAGE"));
    brokenBundleHash = seed(store, rule("BROKEN", "a < b < c", "POL_BROKEN"));
    retiredBundleHash =
        seed(store, rule("MFI_FOIR_CAP", "member.emi / member.income <= 0.9", "POL_FOIR_EXCEEDED"));

    Path manifest = policyDir.resolve("manifest.json");
    Files.writeString(manifest, manifestDocument());

    registry.add("bre.policy.bundles", () -> policyDir.resolve("bundles").toString());
    registry.add("bre.policy.manifest", manifest::toString);
    registry.add("bre.policy.manifest-key", () -> encoded(MANIFEST_KEY));
    registry.add("bre.policy.trusted-keys.reg-test-2026", () -> encoded(BUNDLE_KEY));
    registry.add("bre.policy.grandfather-window-days", () -> "90");
  }

  private static String encoded(KeyPair pair) {
    return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
  }

  private static ObjectNode rule(String code, String check, String reasonCode) {
    ObjectNode rule = JSON.createObjectNode();
    rule.put("code", code).put("name", code).put("scope", "APPLICATION").put("check", check);
    rule.putObject("onFail").put("status", "FAIL").put("reasonCode", reasonCode);
    rule.putObject("onMissing").put("policy", "REFER");
    return rule;
  }

  private static String seed(FileBundleStore store, ObjectNode... rules) throws IOException {
    ObjectNode ir = JSON.createObjectNode();
    ir.put("irVersion", 1);
    for (ObjectNode rule : rules) {
      ir.withArray("rules").add(rule);
    }
    Bundle bundle =
        BundleSigner.sign(
            JSON.createObjectNode().put("policyCode", "MFI_JLG_CREDIT_UW"),
            ir,
            JSON.createObjectNode(),
            "reg-test-2026",
            Bundle.Role.ORIGIN,
            BUNDLE_KEY.getPrivate());
    store.put(bundle.bundleHash(), bundle.toJson());
    return bundle.bundleHash();
  }

  private static String manifestDocument() {
    ObjectNode root = JSON.createObjectNode();
    root.put("format", PointerManifest.FORMAT)
        .put("cellId", 1)
        .put("manifestVersion", 1L)
        .put("issuedAt", "2026-10-01T00:00:00Z");

    for (String policyCode : new String[] {"MFI_JLG_CREDIT_UW", "BROKEN_POLICY"}) {
      ObjectNode entry = root.withArray("entries").addObject();
      boolean broken = "BROKEN_POLICY".equals(policyCode);
      entry
          .put("tenantId", "T-001")
          .put("environment", "PROD")
          .put("policyCode", policyCode)
          .put("timeZone", "Asia/Kolkata")
          .put("pinningAllowed", true);
      entry
          .putObject("champion")
          .put("deploymentId", "dep-1")
          .put("bundleHash", broken ? brokenBundleHash : foirBundleHash);
      entry.putArray("challengers");
      entry
          .putArray("servable")
          .addObject()
          .put("bundleHash", broken ? brokenBundleHash : foirBundleHash)
          .put("deploymentId", "dep-1")
          .putNull("championUntil");
      if (!broken) {
        entry
            .withArray("servable")
            .addObject()
            .put("bundleHash", retiredBundleHash)
            .put("deploymentId", "dep-0")
            .put("championUntil", "2026-08-14T05:00:00Z");
      }
    }
    return PointerManifest.sign(root, "manifest-cell1", MANIFEST_KEY.getPrivate()).toString();
  }

  @LocalServerPort private int port;

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private static String request(String requestId, String income, String emi) {
    return """
        { "tenantId": "T-001", "requestId": "%s", "asOf": "2026-10-01",
          "environment": "PROD", "policyCode": "MFI_JLG_CREDIT_UW",
          "facts": { "member": { "income": "%s", "emi": "%s", "age": 34 } } }
        """
        .formatted(requestId, income, emi);
  }

  private HttpResponse<String> post(String body) throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/decisions"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String decisionId, String tenantId)
      throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/v1/decisions/" + decisionId))
            .header("X-Tenant-Id", tenantId)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode body(HttpResponse<String> response) throws IOException {
    return JSON.readTree(response.body());
  }

  @Test
  @DisplayName("a resolved policy approves, and the decision is readable afterwards")
  void approveThenRead() throws Exception {
    HttpResponse<String> posted = post(request("REQ-1", "20000", "5000"));
    assertThat(posted.statusCode()).isEqualTo(201);

    JsonNode decision = body(posted);
    assertThat(decision.get("decision").asText()).isEqualTo("APPROVE");
    assertThat(decision.get("stpEligible").asBoolean()).isTrue();
    assertThat(decision.get("asOf").asText()).isEqualTo("2026-10-01");
    assertThat(decision.get("results").get(0).get("status").asText()).isEqualTo("PASS");
    assertThat(decision.get("results").get(1).get("status").asText()).isEqualTo("PASS");

    String decisionId = decision.get("decisionId").asText();
    HttpResponse<String> read = get(decisionId, "T-001");
    assertThat(read.statusCode()).isEqualTo(200);
    assertThat(body(read).get("decision").asText()).isEqualTo("APPROVE");
  }

  @Test
  @DisplayName("a failing rule declines and carries its reason code")
  void failingRuleDeclines() throws Exception {
    JsonNode decision = body(post(request("REQ-2", "10000", "8000")));
    assertThat(decision.get("decision").asText()).isEqualTo("DECLINE");
    assertThat(decision.get("stpEligible").asBoolean()).isFalse();
    assertThat(decision.get("results").get(0).get("status").asText()).isEqualTo("FAIL");
    assertThat(decision.get("results").get(0).get("reasonCode").asText())
        .isEqualTo("POL_FOIR_EXCEEDED");
  }

  @Test
  @DisplayName("a missing fact refers rather than approving — the fail-closed path (D9)")
  void missingFactRefers() throws Exception {
    String withoutIncome =
        """
        { "tenantId": "T-001", "requestId": "REQ-3", "asOf": "2026-10-01",
          "environment": "PROD", "policyCode": "MFI_JLG_CREDIT_UW",
          "facts": { "member": { "emi": "5000", "age": 34 } } }
        """;

    JsonNode decision = body(post(withoutIncome));
    assertThat(decision.get("decision").asText())
        .as("an absent income must not approve by skipping the cap it cannot evaluate")
        .isEqualTo("REFER");
    assertThat(decision.get("results").get(0).get("status").asText()).isEqualTo("DATA_MISSING");
  }

  @Test
  @DisplayName("division by zero is an ERROR on the result and refers, never a 500 (D-07)")
  void divisionByZeroIsReportedOnTheResult() throws Exception {
    HttpResponse<String> posted = post(request("REQ-4", "0", "5000"));
    assertThat(posted.statusCode())
        .as("§3.11: a runtime error is reported on the result, not thrown out of the API")
        .isEqualTo(201);

    JsonNode decision = body(posted);
    assertThat(decision.get("decision").asText()).isEqualTo("REFER");
    assertThat(decision.get("results").get(0).get("status").asText()).isEqualTo("ERROR");
    assertThat(decision.get("results").get(0).get("error").asText()).isEqualTo("EVAL_DIV_ZERO");
  }

  @Test
  @DisplayName("the same requestId returns the same decision, not a second one")
  void idempotency() throws Exception {
    String first =
        body(post(request("REQ-IDEMPOTENT", "20000", "5000"))).get("decisionId").asText();
    String second =
        body(post(request("REQ-IDEMPOTENT", "20000", "5000"))).get("decisionId").asText();
    assertThat(second)
        .as("a retrying caller must see one decision, not two with different ids")
        .isEqualTo(first);
  }

  @Test
  @DisplayName("a decision is not readable under another tenant (D1)")
  void tenantIsolation() throws Exception {
    String decisionId = body(post(request("REQ-5", "20000", "5000"))).get("decisionId").asText();
    assertThat(get(decisionId, "T-OTHER").statusCode())
        .as("a correct id under the wrong tenant is a 404, which is also what leaks least")
        .isEqualTo(404);
  }

  @Test
  @DisplayName("a malformed expression in a bundle is problem+json with a position, not a 500")
  void invalidExpression() throws Exception {
    String against =
        """
        { "tenantId": "T-001", "requestId": "REQ-6", "environment": "PROD",
          "policyCode": "BROKEN_POLICY", "facts": {} }
        """;

    HttpResponse<String> response = post(against);
    assertThat(response.statusCode()).isEqualTo(422);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .contains("application/problem+json");
    assertThat(body(response).get("detail").asText()).contains("not associative");
    assertThat(body(response).get("line").asInt()).isEqualTo(1);
  }

  @Test
  @DisplayName("a missing tenantId is rejected at the boundary, naming the field not the data")
  void validationNamesFieldsNotValues() throws Exception {
    String request =
        """
        { "requestId": "REQ-7", "environment": "PROD", "policyCode": "MFI_JLG_CREDIT_UW",
          "facts": { "member": { "income": "20000" } } }
        """;

    HttpResponse<String> response = post(request);
    assertThat(response.statusCode()).isEqualTo(400);

    JsonNode problem = body(response);
    assertThat(problem.get("detail").asText()).contains("tenantId");
    assertThat(problem.get("detail").asText())
        .as("a validation message must never quote the submitted facts (§9.6)")
        .doesNotContain("20000");
  }

  // --- the resolution path, over HTTP ------------------------------------------------------

  @Test
  @DisplayName("a policy this cell does not serve is refused, not decided against something else")
  void unservedPolicyIsRefused() throws Exception {
    String unknown =
        """
        { "tenantId": "T-001", "requestId": "REQ-8", "environment": "PROD",
          "policyCode": "NO_SUCH_POLICY", "facts": {} }
        """;

    HttpResponse<String> response = post(unknown);
    assertThat(response.statusCode()).isEqualTo(422);
    assertThat(body(response).get("reason").asText()).isEqualTo("NOT_SERVED");
  }

  @Test
  @DisplayName("a pinned bundle inside the window is honoured and decides against that policy")
  void pinnedBundleIsHonoured() throws Exception {
    String pinned =
        """
        { "tenantId": "T-001", "requestId": "REQ-9", "asOf": "2026-10-01",
          "environment": "PROD", "policyCode": "MFI_JLG_CREDIT_UW",
          "bundleHash": "%s",
          "facts": { "member": { "income": "10000", "emi": "8000", "age": 34 } } }
        """
            .formatted(retiredBundleHash);

    JsonNode decision = body(post(pinned));
    assertThat(decision.get("decision").asText())
        .as("the retired policy caps FOIR at 0.9, so 0.8 passes where the champion's 0.5 fails")
        .isEqualTo("APPROVE");
  }

  @Test
  @DisplayName("a pin for a bundle that was never champion is refused")
  void unservablePinIsRefused() throws Exception {
    String pinned =
        """
        { "tenantId": "T-001", "requestId": "REQ-10", "environment": "PROD",
          "policyCode": "MFI_JLG_CREDIT_UW",
          "bundleHash": "sha256:0000000000000000000000000000000000000000000000000000000000000000",
          "facts": { "member": { "income": "20000", "emi": "5000", "age": 34 } } }
        """;

    HttpResponse<String> response = post(pinned);
    assertThat(response.statusCode()).isEqualTo(422);
    assertThat(body(response).get("reason").asText()).isEqualTo("PIN_REFUSED");
  }
}
