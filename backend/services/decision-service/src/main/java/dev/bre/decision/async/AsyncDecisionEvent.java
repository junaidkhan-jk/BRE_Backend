package dev.bre.decision.async;

import dev.bre.decision.api.DecisionApi.DecisionRequest;
import java.util.Map;

/**
 * The payload of {@code bre.decision.requested.v1} (M4-06, 00 §3.9).
 *
 * <p>An explicit wire type for the same reason {@code DecisionEvent} is one: this topic is a
 * published contract that other systems produce to, and a record shaped for the code that happens
 * to read it today is not a contract anybody agreed to.
 *
 * <p><b>The decision id is on the REQUEST, which is the unusual part.</b> It is minted when the 202
 * is returned, not when the decision is made, because the caller is handed it to GET later. A
 * worker minting its own would give the caller an identifier for a decision that never exists under
 * it — a 404 that looks like data loss.
 *
 * @param decisionId the identifier the decision will be recorded under, minted at accept
 * @param tenantId the tenant
 * @param requestId the caller's idempotency key; a redelivery re-emits the original decision
 * @param asOf the business date, or null for today in the tenant's zone
 * @param environment which environment to decide in
 * @param policyCode which policy
 * @param bundleHash a pinned bundle, or null to resolve the active one
 * @param facts the fact snapshot
 */
public record AsyncDecisionEvent(
    String decisionId,
    String tenantId,
    String requestId,
    String asOf,
    String environment,
    String policyCode,
    String bundleHash,
    Map<String, Object> facts) {

  /** The topic async requests arrive on. */
  public static final String TOPIC = "bre.decision.requested.v1";

  /** The topic decisions are published to when they are made. */
  public static final String COMPLETED_TOPIC = "bre.decision.completed.v1";

  /**
   * The partition key: {@code tenantId:applicationRef} (00 §3.9).
   *
   * <p>Per-application ordering is the guarantee that matters. Two requests for one application
   * decided out of order would let a consumer act on an approval that a later decline had already
   * superseded — a wrong answer, not merely a late one.
   *
   * @return the key
   */
  public String partitionKey() {
    Object application = facts == null ? null : nested(facts, "application", "applicationRef");
    return tenantId + ":" + (application == null ? requestId : application);
  }

  private static Object nested(Map<String, Object> map, String outer, String inner) {
    Object child = map.get(outer);
    return child instanceof Map<?, ?> childMap ? childMap.get(inner) : null;
  }

  /**
   * The request this event asks for.
   *
   * @return the decision request
   */
  public DecisionRequest toRequest() {
    return new DecisionRequest(
        tenantId,
        requestId,
        asOf == null || asOf.isBlank() ? null : java.time.LocalDate.parse(asOf),
        facts,
        environment,
        policyCode,
        bundleHash);
  }
}
