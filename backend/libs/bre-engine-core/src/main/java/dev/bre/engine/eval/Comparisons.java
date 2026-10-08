package dev.bre.engine.eval;

import dev.bre.engine.bel.ast.Expr;
import dev.bre.engine.eval.Value.BoolV;
import dev.bre.engine.eval.Value.DateTimeV;
import dev.bre.engine.eval.Value.DateV;
import dev.bre.engine.eval.Value.DecimalV;
import dev.bre.engine.eval.Value.IntV;
import dev.bre.engine.eval.Value.Missing;
import dev.bre.engine.eval.Value.TextV;

/**
 * Comparison semantics (LLD §3.4, §3.6), in one place.
 *
 * <p>Shared by expression evaluation and by decision-table cells. Two implementations of "are these
 * equal" would be a defect waiting for the day they disagree: a table cell and the {@code check}
 * expression beside it must decide {@code 1.0 = 1.00} the same way, or a policy means two different
 * things depending on where the author wrote it.
 *
 * <p>The rules: numbers compare by value and ignore scale ({@code compareTo}); text compares by
 * equality only, never by order (ordering text is the compile error BEL-010); date-times compare on
 * the instant; and any missing operand makes the comparison missing.
 */
public final class Comparisons {

  private Comparisons() {}

  /**
   * Applies a comparison operator.
   *
   * @param op the operator
   * @param left the left operand
   * @param right the right operand
   * @return TRUE, FALSE, or {@link Missing} when either operand is missing
   */
  public static Value compare(Expr.CompareOp op, Value left, Value right) {
    if (left.isMissing() || right.isMissing()) {
      return Missing.INSTANCE;
    }
    if (op == Expr.CompareOp.EQ || op == Expr.CompareOp.NE) {
      boolean equal = areEqual(left, right);
      return BoolV.of(op == Expr.CompareOp.EQ ? equal : !equal);
    }
    int order = order(left, right);
    return BoolV.of(
        switch (op) {
          case LT -> order < 0;
          case LE -> order <= 0;
          case GT -> order > 0;
          case GE -> order >= 0;
          default -> throw new IllegalStateException("handled above: " + op);
        });
  }

  /**
   * Returns whether two present values are equal.
   *
   * @param left the left operand
   * @param right the right operand
   * @return true when they are equal
   */
  public static boolean areEqual(Value left, Value right) {
    if (isNumeric(left) && isNumeric(right)) {
      // compareTo, so scale is ignored: 1.0 = 1.00 is TRUE (§3.6, D-05).
      return Decimals.compare(left, right) == 0;
    }
    if (left instanceof TextV a && right instanceof TextV b) {
      return a.value().equals(b.value());
    }
    if (left instanceof BoolV a && right instanceof BoolV b) {
      return a.value() == b.value();
    }
    return left.equals(right);
  }

  /**
   * Orders two present values.
   *
   * @param left the left operand
   * @param right the right operand
   * @return negative, zero or positive
   * @throws EvalException {@link EvalErrorCode#EVAL_INTERNAL} when the types are not orderable,
   *     which the type checker should already have rejected (BEL-010, BEL-021)
   */
  public static int order(Value left, Value right) {
    if (isNumeric(left) && isNumeric(right)) {
      return Decimals.compare(left, right);
    }
    if (left instanceof DateV a && right instanceof DateV b) {
      return a.value().compareTo(b.value());
    }
    if (left instanceof DateTimeV a && right instanceof DateTimeV b) {
      // Compared on the instant, so two offsets for the same moment are equal (§3.4).
      return a.value().toInstant().compareTo(b.value().toInstant());
    }
    throw new EvalException(EvalErrorCode.EVAL_INTERNAL, null, left.typeName());
  }

  /** Returns whether a value is Integer or Decimal. */
  public static boolean isNumeric(Value value) {
    return value instanceof IntV || value instanceof DecimalV;
  }
}
