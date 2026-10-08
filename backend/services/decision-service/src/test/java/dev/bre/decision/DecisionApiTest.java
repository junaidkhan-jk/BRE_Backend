package dev.bre.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The walking skeleton, end to end: REST in, engine, durable record, {@code GET} back out.
 *
 * <p>This is M2's exit criterion in test form. It starts the real application on a real port and
 * talks to it over real HTTP with the JDK's own client — nothing is mocked, because what is worth
 * proving here is that the pieces fit, not that each one works alone.
 *
 * <p>The JDK client rather than Spring's test client on purpose: Spring Boot 4 moved MockMvc test
 * support into a separate module and removed {@code TestRestTemplate}, and {@code
 * java.net.http.HttpClient} needs no dependency at all to do the same job.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DecisionApiTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @LocalServerPort private int port;

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private static String policyRequest(String requestId, String income, String emi) {
    return """
        {
          "tenantId": "T-001",
          "requestId": "%s",
          "asOf": "2026-10-01",
          "facts": { "member": { "income": "%s", "emi": "%s", "age": 34 } },
          "policy": { "rules": [
            { "code": "MFI_FOIR_CAP",
              "check": "member.emi / member.income <= 0.5",
              "reasonCode": "POL_FOIR_EXCEEDED" },
            { "code": "MFI_MIN_AGE",
              "check": "member.age >= 18",
              "reasonCode": "POL_UNDERAGE" }
          ] }
        }
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
  @DisplayName("a passing policy approves, and the decision is readable afterwards")
  void approveThenRead() throws Exception {
    HttpResponse<String> posted = post(policyRequest("REQ-1", "20000", "5000"));
    assertThat(posted.statusCode()).isEqualTo(201);

    JsonNode decision = body(posted);
    assertThat(decision.get("decision").asText()).isEqualTo("APPROVE");
    assertThat(decision.get("stpEligible").asBoolean()).isTrue();
    assertThat(decision.get("asOf").asText()).isEqualTo("2026-10-01");
    assertThat(decision.get("results").get(0).get("status").asText()).isEqualTo("PASS");
    assertThat(decision.get("results").get(1).get("status").asText()).isEqualTo("PASS");

    // The durable record is readable — the half of the skeleton a POST alone cannot show.
    String decisionId = decision.get("decisionId").asText();
    HttpResponse<String> read = get(decisionId, "T-001");
    assertThat(read.statusCode()).isEqualTo(200);
    assertThat(body(read).get("decisionId").asText()).isEqualTo(decisionId);
    assertThat(body(read).get("decision").asText()).isEqualTo("APPROVE");
  }

  @Test
  @DisplayName("a failing rule declines and carries its reason code")
  void failingRuleDeclines() throws Exception {
    JsonNode decision = body(post(policyRequest("REQ-2", "10000", "8000")));
    assertThat(decision.get("decision").asText()).isEqualTo("DECLINE");
    assertThat(decision.get("stpEligible").asBoolean()).isFalse();
    assertThat(decision.get("results").get(0).get("status").asText()).isEqualTo("FAIL");
    assertThat(decision.get("results").get(0).get("reasonCode").asText())
        .isEqualTo("POL_FOIR_EXCEEDED");
  }

  @Test
  @DisplayName("a missing fact refers rather than approving — the fail-closed path (D9)")
  void missingFactRefers() throws Exception {
    String request =
        """
        {
          "tenantId": "T-001",
          "requestId": "REQ-3",
          "asOf": "2026-10-01",
          "facts": { "member": { "emi": "5000" } },
          "policy": { "rules": [
            { "code": "MFI_FOIR_CAP",
              "check": "member.emi / member.income <= 0.5",
              "reasonCode": "POL_FOIR_EXCEEDED" }
          ] }
        }
        """;

    JsonNode decision = body(post(request));
    assertThat(decision.get("decision").asText())
        .as("an absent income must not approve by skipping the cap it cannot evaluate")
        .isEqualTo("REFER");
    assertThat(decision.get("results").get(0).get("status").asText()).isEqualTo("DATA_MISSING");
  }

  @Test
  @DisplayName("division by zero is an ERROR on the result and refers, never a 500 (D-07)")
  void divisionByZeroIsReportedOnTheResult() throws Exception {
    HttpResponse<String> posted = post(policyRequest("REQ-4", "0", "5000"));
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
        body(post(policyRequest("REQ-IDEMPOTENT", "20000", "5000"))).get("decisionId").asText();
    String second =
        body(post(policyRequest("REQ-IDEMPOTENT", "20000", "5000"))).get("decisionId").asText();
    assertThat(second)
        .as("a retrying caller must see one decision, not two with different ids")
        .isEqualTo(first);
  }

  @Test
  @DisplayName("a decision is not readable under another tenant (D1)")
  void tenantIsolation() throws Exception {
    String decisionId =
        body(post(policyRequest("REQ-5", "20000", "5000"))).get("decisionId").asText();
    assertThat(get(decisionId, "T-OTHER").statusCode())
        .as("a correct id under the wrong tenant is a 404, which is also what leaks least")
        .isEqualTo(404);
  }

  @Test
  @DisplayName("a malformed expression is problem+json with a position, not a 500")
  void invalidExpression() throws Exception {
    String request =
        """
        {
          "tenantId": "T-001",
          "requestId": "REQ-6",
          "facts": {},
          "policy": { "rules": [ { "code": "BROKEN", "check": "a < b < c" } ] }
        }
        """;

    HttpResponse<String> response = post(request);
    assertThat(response.statusCode()).isEqualTo(422);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .contains("application/problem+json");

    JsonNode problem = body(response);
    assertThat(problem.get("detail").asText()).contains("not associative");
    assertThat(problem.get("line").asInt()).isEqualTo(1);
  }

  @Test
  @DisplayName("a missing tenantId is rejected at the boundary, naming the field not the data")
  void validationNamesFieldsNotValues() throws Exception {
    String request =
        """
        {
          "requestId": "REQ-7",
          "facts": { "member": { "income": "20000" } },
          "policy": { "rules": [ { "code": "R", "check": "true" } ] }
        }
        """;

    HttpResponse<String> response = post(request);
    assertThat(response.statusCode()).isEqualTo(400);

    JsonNode problem = body(response);
    assertThat(problem.get("detail").asText()).contains("tenantId");
    assertThat(problem.get("detail").asText())
        .as("a validation message must never quote the submitted facts (§9.6)")
        .doesNotContain("20000");
  }
}
