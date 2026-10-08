package dev.bre.engine.decide;

import dev.bre.engine.bel.parse.Parser;
import dev.bre.engine.decide.Outcomes.Decision;
import dev.bre.engine.decide.Outcomes.Status;
import dev.bre.engine.eval.EvalException;
import dev.bre.engine.eval.EvaluationContext;
import dev.bre.engine.eval.Evaluator;
import dev.bre.engine.eval.Value;
import dev.bre.engine.eval.Value.BoolV;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Evaluates a list of rules and resolves one decision (LLD §4.6 and §4.8).
 *
 * <p><b>Fail-closed by construction (D9).</b> The precedence below is ordered so that {@code FAIL}
 * declines, and {@code ERROR} or {@code DATA_MISSING} refer — neither can ever produce {@code
 * APPROVE}. That is not a convention to be remembered at each call site; there is no path through
 * {@link #decide} that turns an error or a gap into an approval, and the fail-closed property test
 * asserts it over random policies.
 *
 * <p>This is the walking skeleton's subset: rules only, one entity, no decision tables, no
 * scorecards, no flow or per-entity aggregation. Those are M1-07 and M1-08.
 */
public final class PolicyEvaluator {

  private PolicyEvaluator() {}

  /**
   * The outcome of one rule.
   *
   * @param code the rule's identifier
   * @param status the rule's status
   * @param reasonCode the reason attached to a non-PASS outcome, if the rule declared one
   * @param error the runtime error code, when the status is ERROR
   */
  public record RuleResult(
      String code, Status status, Optional<String> reasonCode, Optional<String> error) {}

  /**
   * A whole decision.
   *
   * @param decision the overall decision
   * @param results one entry per rule, in declaration order
   * @param stpEligible whether this could go straight through — false once anything deviates
   */
  public record PolicyResult(Decision decision, List<RuleResult> results, boolean stpEligible) {
    /** Creates a result, copying the list. */
    public PolicyResult {
      results = List.copyOf(results);
    }
  }

  /**
   * Evaluates every rule and resolves the decision.
   *
   * @param rules the rules to evaluate, in declaration order
   * @param context the facts, parameters, lists and {@code asOf}
   * @return the per-rule results and the overall decision
   */
  public static PolicyResult evaluate(List<RuleDef> rules, EvaluationContext context) {
    List<RuleResult> results = new ArrayList<>(rules.size());
    for (RuleDef rule : rules) {
      results.add(evaluateRule(rule, context));
    }
    return new PolicyResult(decide(results), results, isStpEligible(results));
  }

  private static RuleResult evaluateRule(RuleDef rule, EvaluationContext context) {
    try {
      // The guard first. A FALSE `when` means the rule does not apply; an UNKNOWN `when` goes to
      // onMissing and must never become NOT_APPLICABLE (S1-01) — treating "we don't know whether
      // this rule applies" as "it doesn't apply" is a fail-open.
      if (rule.when().isPresent()) {
        Value guard = evaluate(rule.when().get(), context);
        if (guard.isMissing()) {
          return applyOnMissing(rule);
        }
        if (!((BoolV) guard).value()) {
          return new RuleResult(
              rule.code(), Status.NOT_APPLICABLE, Optional.empty(), Optional.empty());
        }
      }

      Value check = evaluate(rule.check(), context);
      if (check.isMissing()) {
        return applyOnMissing(rule);
      }
      Status status = ((BoolV) check).value() ? Status.PASS : Status.FAIL;
      return new RuleResult(
          rule.code(),
          status,
          status == Status.PASS ? Optional.empty() : rule.reasonCode(),
          Optional.empty());

    } catch (EvalException runtimeError) {
      // §3.11: a runtime error is reported on the result, not thrown out of the API. It carries
      // the error code; the message carries a fact path, never a value.
      return new RuleResult(
          rule.code(), Status.ERROR, rule.reasonCode(), Optional.of(runtimeError.code().name()));
    }
  }

  private static RuleResult applyOnMissing(RuleDef rule) {
    Status status =
        switch (rule.onMissing()) {
          case DATA_MISSING -> Status.DATA_MISSING;
          case FAIL -> Status.FAIL;
          case NOT_APPLICABLE -> Status.NOT_APPLICABLE;
        };
    return new RuleResult(
        rule.code(),
        status,
        status == Status.NOT_APPLICABLE ? Optional.empty() : rule.reasonCode(),
        Optional.empty());
  }

  private static Value evaluate(String expression, EvaluationContext context) {
    return Evaluator.evaluate(Parser.parseExpression(expression), context);
  }

  /**
   * Decision precedence (LLD §4.8, vectors P-01 to P-03).
   *
   * <p>The order is the fail-closed one: a FAIL declines even when an ERROR is also present (P-01),
   * and an ERROR or a gap refers rather than approving (P-03). Nothing here can reach APPROVE while
   * a non-PASS status that is not a DEVIATION remains.
   */
  private static Decision decide(List<RuleResult> results) {
    boolean anyFail = false;
    boolean anyRefer = false;
    boolean anyDeviation = false;

    for (RuleResult result : results) {
      switch (result.status()) {
        case FAIL -> anyFail = true;
        case ERROR, DATA_MISSING -> anyRefer = true;
        case DEVIATION -> anyDeviation = true;
        case PASS, NOT_APPLICABLE -> {
          // nothing to carry
        }
      }
    }

    if (anyFail) {
      return Decision.DECLINE;
    }
    if (anyRefer) {
      return Decision.REFER;
    }
    return anyDeviation ? Decision.APPROVE_WITH_DEVIATION : Decision.APPROVE;
  }

  /** Straight-through processing is off the moment anything deviates (P-02). */
  private static boolean isStpEligible(List<RuleResult> results) {
    return results.stream()
        .allMatch(r -> r.status() == Status.PASS || r.status() == Status.NOT_APPLICABLE);
  }
}
