package dev.bre.conformance.failclosed;

import dev.bre.conformance.failclosed.FailClosedModel.Decision;
import dev.bre.conformance.failclosed.FailClosedModel.Engine;
import dev.bre.conformance.failclosed.FailClosedModel.Facts;
import dev.bre.conformance.failclosed.FailClosedModel.OnMissing;
import dev.bre.conformance.failclosed.FailClosedModel.Rule;
import dev.bre.conformance.failclosed.FailClosedModel.Status;
import java.util.List;

/**
 * Two stub engines: one that honours the fail-closed model (D9) and one that does not.
 *
 * <p>The fail-open stub is the point of this file. A property test asserted only against a correct
 * implementation passes whether or not the property is actually being checked. {@code
 * FailClosedPropertyTest} runs the same property against {@link #failOpen()} and requires it to
 * FAIL, which is the only way to know the harness works before there is an engine to catch.
 */
public final class StubEngines {

  private StubEngines() {}

  /**
   * A compliant engine. Missing data can never produce APPROVE: {@code ERROR} and {@code
   * DATA_MISSING} are fail-closed outcomes (D9), and the overall decision is the worst rule status
   * mapped through precedence.
   */
  public static Engine failClosed() {
    return (policy, facts) -> decide(policy, facts, false);
  }

  /**
   * A deliberately defective engine with the fail-open bug Draft 1 contained: a missing fact is
   * treated as "nothing to check", so the rule is skipped and the application sails through to
   * APPROVE. This is the exact defect D9 closes.
   */
  public static Engine failOpen() {
    return (policy, facts) -> decide(policy, facts, true);
  }

  private static Decision decide(List<Rule> policy, Facts facts, boolean treatMissingAsSkip) {
    Status worst = Status.NOT_APPLICABLE;
    for (Rule rule : policy) {
      Status status = evaluate(rule, facts, treatMissingAsSkip);
      if (status.severity() > worst.severity()) {
        worst = status;
      }
    }
    return toDecision(worst);
  }

  private static Status evaluate(Rule rule, Facts facts, boolean treatMissingAsSkip) {
    return facts
        .get(rule.factName())
        .map(value -> value >= rule.threshold() ? Status.PASS : Status.FAIL)
        .orElseGet(
            () -> {
              if (treatMissingAsSkip) {
                // THE BUG: a missing fact silently removes the check.
                return Status.NOT_APPLICABLE;
              }
              return rule.onMissing() == OnMissing.FAIL ? Status.FAIL : Status.DATA_MISSING;
            });
  }

  /** Decision precedence, per 00 §3.5 and LLD §4.8, reduced to the statuses this model produces. */
  private static Decision toDecision(Status worst) {
    return switch (worst) {
      case NOT_APPLICABLE, PASS -> Decision.APPROVE;
      case DEVIATION -> Decision.APPROVE_WITH_DEVIATION;
      case DATA_MISSING, ERROR -> Decision.REFER;
      case FAIL -> Decision.DECLINE;
    };
  }
}
