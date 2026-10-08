package dev.bre.engine.eval;

import dev.bre.engine.bel.ast.Expr;
import dev.bre.engine.bel.parse.UnaryTest;
import dev.bre.engine.eval.Value.BoolV;
import dev.bre.engine.eval.Value.ListV;

/**
 * Matches a value against a decision-table cell or scorecard bin (LLD §3.3).
 *
 * <p>The whole of §3.3's missing-input column lives in {@link #matches}, and it is the part that
 * decides whether a gap in the data quietly takes a catch-all row:
 *
 * <ul>
 *   <li><b>{@code -} does not match a missing input.</b> A dash means "any value"; a missing input
 *       has no value. So a table whose only catch-all is {@code -} falls through to {@code
 *       onMissing} (S5-01) rather than silently taking the last row.
 *   <li><b>{@code missing} matches only a missing input</b> (S5-02), which is how an author says
 *       "and here is what to do when we do not know".
 *   <li><b>A comma list matches a missing input only if one member is {@code missing}</b> (S5-03).
 * </ul>
 */
public final class UnaryTests {

  private UnaryTests() {}

  /**
   * Returns whether a value matches a test.
   *
   * @param test the parsed cell
   * @param input the value under test, possibly missing
   * @param context the evaluation context, for the test's right-hand sides
   * @return true when the cell matches
   */
  public static boolean matches(UnaryTest test, Value input, EvaluationContext context) {
    return switch (test) {
      case UnaryTest.AnyPresent ignored -> !input.isMissing();
      case UnaryTest.IsMissing ignored -> input.isMissing();
      case UnaryTest.AnyOf anyOf -> {
        for (UnaryTest alternative : anyOf.alternatives()) {
          if (matches(alternative, input, context)) {
            yield true;
          }
        }
        yield false;
      }
      case UnaryTest.Compare compare -> {
        // Every other test form fails to match a missing input (§3.3).
        if (input.isMissing()) {
          yield false;
        }
        yield isTrue(compareTo(compare, input, context));
      }
      case UnaryTest.Range range -> {
        if (input.isMissing()) {
          yield false;
        }
        yield isTrue(inRange(range, input, context));
      }
      case UnaryTest.In in -> {
        if (input.isMissing()) {
          yield false;
        }
        yield isTrue(membership(in, input, context));
      }
    };
  }

  /** An unknown comparison is not a match: §3.3 gives no third outcome for a cell. */
  private static boolean isTrue(Value value) {
    return value instanceof BoolV bool && bool.value();
  }

  private static Value compareTo(
      UnaryTest.Compare compare, Value input, EvaluationContext context) {
    Value operand = Evaluator.evaluate(compare.operand(), context);
    if (operand.isMissing()) {
      return Value.Missing.INSTANCE;
    }
    return Comparisons.compare(compare.op(), input, operand);
  }

  private static Value inRange(UnaryTest.Range range, Value input, EvaluationContext context) {
    Value low = Evaluator.evaluate(range.low(), context);
    Value high = Evaluator.evaluate(range.high(), context);
    if (low.isMissing() || high.isMissing()) {
      return Value.Missing.INSTANCE;
    }
    Value aboveLow =
        Comparisons.compare(
            range.lowInclusive() ? Expr.CompareOp.GE : Expr.CompareOp.GT, input, low);
    Value belowHigh =
        Comparisons.compare(
            range.highInclusive() ? Expr.CompareOp.LE : Expr.CompareOp.LT, input, high);
    if (aboveLow.isMissing() || belowHigh.isMissing()) {
      return Value.Missing.INSTANCE;
    }
    return BoolV.of(((BoolV) aboveLow).value() && ((BoolV) belowHigh).value());
  }

  private static Value membership(UnaryTest.In in, Value input, EvaluationContext context) {
    Value target = Evaluator.evaluate(in.target(), context);
    if (target.isMissing()) {
      return Value.Missing.INSTANCE;
    }
    if (!(target instanceof ListV list)) {
      return Value.Missing.INSTANCE;
    }

    boolean sawMissingElement = false;
    for (Value element : list.elements()) {
      if (element.isMissing()) {
        sawMissingElement = true;
        continue;
      }
      Value equal = Comparisons.compare(Expr.CompareOp.EQ, input, element);
      if (isTrue(equal)) {
        return BoolV.of(!in.negated());
      }
    }
    if (sawMissingElement) {
      return Value.Missing.INSTANCE;
    }
    return BoolV.of(in.negated());
  }
}
