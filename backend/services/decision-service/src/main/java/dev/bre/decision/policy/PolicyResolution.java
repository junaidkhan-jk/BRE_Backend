package dev.bre.decision.policy;

import dev.bre.engine.ir.Ir;

/**
 * Which policy a decision was taken against, and why that one (LLD §8.1, §4.11).
 *
 * <p>Recorded on the decision, because "APPROVE" without the bundle hash is not an auditable
 * record: it cannot be replayed, and nobody can say later which rules were in force.
 *
 * @param policy the decoded policy
 * @param bundleHash the content address actually served
 * @param deploymentId the deployment that put it there
 * @param kind how this bundle came to be chosen
 * @param experimentId the experiment, when a challenger was served
 */
public record PolicyResolution(
    Ir.PolicyIr policy,
    String bundleHash,
    String deploymentId,
    Kind kind,
    java.util.Optional<String> experimentId) {

  /** Why this bundle and not another. */
  public enum Kind {
    /** The default for this tenant, environment and policy. */
    CHAMPION,
    /** A challenger whose bucket range contains this request (§4.11). */
    CHALLENGER,
    /** The caller pinned a specific bundle hash and it was honoured (§8.1). */
    PINNED
  }
}
