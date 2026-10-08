package dev.bre.engine.decide;

import dev.bre.engine.eval.Comparisons;
import dev.bre.engine.eval.Decimals;
import dev.bre.engine.eval.EvalErrorCode;
import dev.bre.engine.eval.EvalException;
import dev.bre.engine.eval.EvaluationContext;
import dev.bre.engine.eval.Value;
import dev.bre.engine.eval.Value.DecimalV;
import dev.bre.engine.eval.Value.IntV;
import dev.bre.engine.ir.Ir.AggregateSpec;
import dev.bre.engine.ir.Ir.RuntimeStatus;
import java.math.BigDecimal;
import java.util.List;

/**
 * Combines per-entity statuses into one step status (LLD §4.7).
 *
 * <p>Four modes, and the two interesting ones both turn on what happens to entities that did not
 * apply:
 *
 * <ul>
 *   <li><b>ALL_MUST_PASS drops NOT_APPLICABLE before testing</b> (A-01a), so a member whose {@code
 *       when} was FALSE does not block the step. But when only gaps remain it keeps the gap rather
 *       than converting it to a FAIL (A-01c) — a REFER and a DECLINE are different answers to the
 *       lender.
 *   <li><b>THRESHOLD excludes NOT_APPLICABLE from the denominator</b> (S7-04), so 2 deviations
 *       among 10 members of whom 2 did not apply is 2/8, not 2/10. And the result is never weaker
 *       than WORST (S7-05): a single FAIL still fails the step however small its share.
 * </ul>
 */
final class Aggregation {

  private Aggregation() {}

  /**
   * Aggregates the entity results for one step.
   *
   * @param spec how to aggregate
   * @param results one result per entity, after coverage has been applied
   * @param context the evaluation context, to resolve a {@code param.X} threshold
   * @return the step's status
   */
  static RuntimeStatus aggregate(
      AggregateSpec spec, List<ComponentResult> results, EvaluationContext context) {
    if (results.isEmpty()) {
      // No entities in scope: the step did not apply to anything.
      return RuntimeStatus.NOT_APPLICABLE;
    }
    return switch (spec.kind()) {
      case NONE -> results.get(0).effectiveStatus();
      case WORST -> worst(results);
      case ALL_MUST_PASS -> allMustPass(results);
      case THRESHOLD -> threshold(spec, results, context);
    };
  }

  private static RuntimeStatus worst(List<ComponentResult> results) {
    RuntimeStatus status = RuntimeStatus.NOT_APPLICABLE;
    for (ComponentResult result : results) {
      // EFFECTIVE, not raw: §4.9 resolves coverage per entity before aggregation, so an approved
      // deviation contributes a PASS here (S8-01) while the raw DEVIATION stays on the record.
      status = Severity.worst(status, result.effectiveStatus());
    }
    return status;
  }

  private static RuntimeStatus allMustPass(List<ComponentResult> results) {
    boolean anyRelevant = false;
    RuntimeStatus worstGap = null;
    boolean anyBreach = false;

    for (ComponentResult result : results) {
      RuntimeStatus status = result.effectiveStatus();
      if (status == RuntimeStatus.NOT_APPLICABLE) {
        continue; // A-01a: NOT_APPLICABLE leaves the set before the all-PASS test.
      }
      anyRelevant = true;
      if (status == RuntimeStatus.DATA_MISSING || status == RuntimeStatus.ERROR) {
        worstGap = worstGap == null ? status : Severity.worst(worstGap, status);
      } else if (status != RuntimeStatus.PASS) {
        anyBreach = true; // DEVIATION, REFER or FAIL — the rule is strict, so none of them pass.
      }
    }

    if (!anyRelevant) {
      return RuntimeStatus.NOT_APPLICABLE;
    }
    if (anyBreach) {
      return RuntimeStatus.FAIL; // A-01b
    }
    if (worstGap != null) {
      return worstGap; // A-01c: keep the gap; do not convert it into a DECLINE.
    }
    return RuntimeStatus.PASS;
  }

  private static RuntimeStatus threshold(
      AggregateSpec spec, List<ComponentResult> results, EvaluationContext context) {
    List<RuntimeStatus> counted = spec.statuses();

    long numerator = 0;
    long denominator = 0;
    for (ComponentResult result : results) {
      if (result.effectiveStatus() == RuntimeStatus.NOT_APPLICABLE) {
        continue; // S7-04: out of the denominator entirely.
      }
      denominator++;
      // A covered deviation is no longer a deviation for threshold purposes (S7-03).
      if (counted.contains(result.effectiveStatus())) {
        numerator++;
      }
    }

    RuntimeStatus worst = worst(results);
    if (denominator == 0) {
      return worst;
    }

    Value measured =
        spec.metric().orElseThrow() == AggregateSpec.Metric.COUNT
            ? new IntV(numerator)
            // PERCENT is a whole-number percent (S7-06), computed at the division scale so that
            // 1 of 6 is 16.6666666667 and not a rounded 17.
            : Decimals.divide(
                Decimals.multiply(new IntV(numerator), new IntV(100)), new IntV(denominator), null);

    Value limit = resolveThreshold(spec, context);
    boolean met = compare(spec.op().orElseThrow(), measured, limit);

    if (!met) {
      return worst; // S7-02, S7-06: below the threshold, the step is simply WORST.
    }

    RuntimeStatus result = toRuntimeStatus(spec);
    // S7-05: the threshold result never weakens WORST. One FAIL among ten still fails the step,
    // even though the threshold it crossed only asked for a REFER.
    return Severity.worst(worst, result);
  }

  private static Value resolveThreshold(AggregateSpec spec, EvaluationContext context) {
    String value = spec.value().orElseThrow();
    if (value.startsWith("param.")) {
      Value bound = context.parameter(value.substring("param.".length()));
      if (bound.isMissing()) {
        // §3.11: a referenced parameter with no effective value is a runtime error, not a zero.
        throw new EvalException(EvalErrorCode.EVAL_PARAM_NOT_EFFECTIVE, value, "Integer");
      }
      return bound;
    }
    return new DecimalV(new BigDecimal(value));
  }

  private static boolean compare(String op, Value measured, Value limit) {
    int order = Comparisons.order(measured, limit);
    return switch (op) {
      case ">=" -> order >= 0;
      case ">" -> order > 0;
      case "<=" -> order <= 0;
      case "<" -> order < 0;
      case "=" -> order == 0;
      default -> throw new EvalException(EvalErrorCode.EVAL_INTERNAL, op, "ThresholdOp");
    };
  }

  private static RuntimeStatus toRuntimeStatus(AggregateSpec spec) {
    return switch (spec.result().orElseThrow()) {
      case PASS -> RuntimeStatus.PASS;
      case DEVIATION -> RuntimeStatus.DEVIATION;
      case REFER -> RuntimeStatus.REFER;
      case FAIL -> RuntimeStatus.FAIL;
    };
  }
}
