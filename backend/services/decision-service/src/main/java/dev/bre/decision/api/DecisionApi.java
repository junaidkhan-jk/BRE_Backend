package dev.bre.decision.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
   * @param policy the rules to evaluate
   */
  public record DecisionRequest(
      @NotBlank String tenantId,
      @NotBlank String requestId,
      LocalDate asOf,
      @NotNull Map<String, Object> facts,
      @NotNull @Valid PolicyPayload policy) {}

  /**
   * An inline policy.
   *
   * <p>A real deployment resolves a signed, content-addressed bundle by hash and never accepts
   * policy on the request (D4, D5). Accepting it inline is what makes the walking skeleton testable
   * before the registry exists, and the registry is M3.
   *
   * @param rules the rules, in declaration order
   */
  public record PolicyPayload(@NotEmpty @Valid List<RulePayload> rules) {}

  /**
   * One rule.
   *
   * @param code the rule's identifier. The IR calls this {@code code}, not {@code id}.
   * @param check the BEL expression that must be TRUE to pass
   * @param when an optional BEL guard
   * @param onMissing DATA_MISSING (the default), FAIL or NOT_APPLICABLE
   * @param reasonCode the reason attached to a non-PASS outcome
   */
  public record RulePayload(
      @NotBlank String code,
      @NotBlank String check,
      String when,
      String onMissing,
      String reasonCode) {}

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
