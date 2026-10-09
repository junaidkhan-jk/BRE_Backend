package dev.bre.decision.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The Decision API's wire shapes (LLD §6.2), as records.
 *
 * <p>Validation sits on the request types rather than in the handler, so a malformed request is
 * rejected at the trust boundary before any of it reaches the engine.
 */
public final class DecisionApi {

  private DecisionApi() {}

  /**
   * A decision request.
   *
   * @param tenantId the tenant. Multi-tenant from day one (D1) — it appears on every row, cache
   *     key, event, metric and log line, so it is required, never defaulted.
   * @param requestId the caller's idempotency key. Evaluation is deterministic and the sink
   *     de-duplicates on (tenantId, requestId), so a retry is harmless.
   * @param asOf the evaluation date. Optional: when absent the service uses the date it received
   *     the request. The engine never reads a clock itself (D8) — this is the only place a "today"
   *     enters, and the resolved value is stored on the decision record.
   * @param facts the fact snapshot. Facts in, decision out (D2): the engine fetches nothing.
   * @param environment the environment to decide in, e.g. {@code PROD}
   * @param policyCode the policy to decide against. The service resolves it to a signed,
   *     content-addressed bundle through the Active Pointer Manifest (§8.1); the caller names the
   *     policy, never its contents.
   * @param bundleHash an optional pin. Honoured only while that bundle is in the entry's {@code
   *     servable} set and inside the tenant's grandfather window (§8.1), measured against the time
   *     the request arrived — not against {@code asOf}, which the caller supplies.
   */
  public record DecisionRequest(
      @NotBlank String tenantId,
      @NotBlank String requestId,
      LocalDate asOf,
      @NotNull Map<String, Object> facts,
      @NotBlank String environment,
      @NotBlank String policyCode,
      String bundleHash) {}

  /**
   * A decision response.
   *
   * @param decisionId the decision's identifier, which {@code GET /v1/decisions/{id}} resolves
   * @param tenantId the tenant the decision was made for
   * @param requestId the caller's idempotency key, echoed back
   * @param asOf the resolved evaluation date — stored, so a replay is exact
   * @param decision APPROVE, APPROVE_WITH_DEVIATION, REFER or DECLINE
   * @param stpEligible whether this could go straight through
   * @param results one entry per rule, in declaration order
   */
  public record DecisionResponse(
      String decisionId,
      String tenantId,
      String requestId,
      LocalDate asOf,
      String decision,
      boolean stpEligible,
      List<RuleOutcome> results) {}

  /**
   * One rule's outcome.
   *
   * @param code the rule's identifier
   * @param status PASS, FAIL, DATA_MISSING, NOT_APPLICABLE, DEVIATION or ERROR
   * @param reasonCode the reason, when the rule declared one and did not pass
   * @param error the runtime error code, when the status is ERROR
   */
  public record RuleOutcome(String code, String status, String reasonCode, String error) {}
}
