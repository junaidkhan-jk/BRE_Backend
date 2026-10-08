package dev.bre.engine.eval;

import dev.bre.engine.eval.Value.DecimalV;
import dev.bre.engine.eval.Value.IntV;
import dev.bre.engine.eval.Value.Missing;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Decimal and Integer arithmetic (LLD §3.6, M1-01).
 *
 * <p>Three rules decide everything here, and all three exist because money is not a {@code double}:
 *
 * <ol>
 *   <li>{@code + - *} are exact {@link BigDecimal} arithmetic, then rounded to {@link
 *       MathContext#DECIMAL128}. With INR amounts that rounding never triggers in practice; it
 *       bounds memory.
 *   <li>{@code /} is {@code divide(b, 10, HALF_EVEN)} then DECIMAL128. <b>{@code b = 0} is an ERROR
 *       ({@link EvalErrorCode#EVAL_DIV_ZERO}), not missing</b> — the one place in the language
 *       where absent-looking input is an error rather than a missing value.
 *   <li>Comparison is {@code compareTo}, so scale is ignored and {@code 1.0 = 1.00} is TRUE.
 * </ol>
 *
 * <p>Every operation propagates {@link Missing} strictly: if any operand is missing the result is
 * missing (§3.5). That is checked before anything else, so no arithmetic ever sees a missing
 * operand.
 */
public final class Decimals {

  /** Division scale, fixed by §3.6. {@code 2 / 3} is {@code 0.6666666667}. */
  public static final int DIVISION_SCALE = 10;

  /** Rounding for division and for the DECIMAL128 bound. */
  public static final RoundingMode DIVISION_ROUNDING = RoundingMode.HALF_EVEN;

  /** The scale bounds for {@code round(x, s, MODE)} (§3.6). */
  public static final int MIN_ROUND_SCALE = -12;

  /** The scale bounds for {@code round(x, s, MODE)} (§3.6). */
  public static final int MAX_ROUND_SCALE = 12;

  private Decimals() {}

  /**
   * Adds two numbers. {@code Integer + Integer} stays Integer; anything else widens to Decimal
   * (§3.4).
   *
   * @param left the left operand
   * @param right the right operand
   * @return the sum, or {@link Missing} if either operand is missing
   * @throws EvalException {@link EvalErrorCode#EVAL_OVERFLOW} on Integer overflow
   */
  public static Value add(Value left, Value right) {
    if (left.isMissing() || right.isMissing()) {
      return Missing.INSTANCE;
    }
    if (left instanceof IntV a && right instanceof IntV b) {
      try {
        return new IntV(Math.addExact(a.value(), b.value()));
      } catch (ArithmeticException overflow) {
        throw new EvalException(EvalErrorCode.EVAL_OVERFLOW, null, "Integer");
      }
    }
    return decimal(toDecimal(left).add(toDecimal(right), MathContext.DECIMAL128));
  }

  /**
   * Subtracts two numbers.
   *
   * @param left the left operand
   * @param right the right operand
   * @return the difference, or {@link Missing} if either operand is missing
   * @throws EvalException {@link EvalErrorCode#EVAL_OVERFLOW} on Integer overflow
   */
  public static Value subtract(Value left, Value right) {
    if (left.isMissing() || right.isMissing()) {
      return Missing.INSTANCE;
    }
    if (left instanceof IntV a && right instanceof IntV b) {
      try {
        return new IntV(Math.subtractExact(a.value(), b.value()));
      } catch (ArithmeticException overflow) {
        throw new EvalException(EvalErrorCode.EVAL_OVERFLOW, null, "Integer");
      }
    }
    return decimal(toDecimal(left).subtract(toDecimal(right), MathContext.DECIMAL128));
  }

  /**
   * Multiplies two numbers.
   *
   * @param left the left operand
   * @param right the right operand
   * @return the product, or {@link Missing} if either operand is missing
   * @throws EvalException {@link EvalErrorCode#EVAL_OVERFLOW} on Integer overflow
   */
  public static Value multiply(Value left, Value right) {
    if (left.isMissing() || right.isMissing()) {
      return Missing.INSTANCE;
    }
    if (left instanceof IntV a && right instanceof IntV b) {
      try {
        return new IntV(Math.multiplyExact(a.value(), b.value()));
      } catch (ArithmeticException overflow) {
        throw new EvalException(EvalErrorCode.EVAL_OVERFLOW, null, "Integer");
      }
    }
    return decimal(toDecimal(left).multiply(toDecimal(right), MathContext.DECIMAL128));
  }

  /**
   * Divides two numbers. The result is <b>always Decimal</b>, even for two Integers (§3.4).
   *
   * @param left the dividend
   * @param right the divisor
   * @param factPath the fact path of the divisor, for the error message; may be null
   * @return the quotient at scale 10 HALF_EVEN, or {@link Missing} if either operand is missing
   * @throws EvalException {@link EvalErrorCode#EVAL_DIV_ZERO} when the divisor is zero
   */
  public static Value divide(Value left, Value right, String factPath) {
    if (left.isMissing() || right.isMissing()) {
      return Missing.INSTANCE;
    }
    BigDecimal divisor = toDecimal(right);
    if (divisor.signum() == 0) {
      // Not missing. §3.6 is explicit, and D-07 pins the resulting decision to REFER.
      throw new EvalException(EvalErrorCode.EVAL_DIV_ZERO, factPath, "Decimal");
    }
    BigDecimal quotient = toDecimal(left).divide(divisor, DIVISION_SCALE, DIVISION_ROUNDING);
    return decimal(quotient.round(MathContext.DECIMAL128));
  }

  /**
   * Negates a number, preserving its type (§3.4).
   *
   * @param operand the operand
   * @return the negation, or {@link Missing} if the operand is missing
   * @throws EvalException {@link EvalErrorCode#EVAL_OVERFLOW} on Integer overflow
   */
  public static Value negate(Value operand) {
    if (operand.isMissing()) {
      return Missing.INSTANCE;
    }
    if (operand instanceof IntV a) {
      try {
        return new IntV(Math.negateExact(a.value()));
      } catch (ArithmeticException overflow) {
        throw new EvalException(EvalErrorCode.EVAL_OVERFLOW, null, "Integer");
      }
    }
    return decimal(toDecimal(operand).negate(MathContext.DECIMAL128));
  }

  /**
   * {@code round(x, s, MODE)} — {@code x.setScale(s, MODE)} (§3.6).
   *
   * <p>A negative scale rounds to tens, hundreds and so on: {@code round(4444444.44, -3, DOWN)} is
   * {@code 4444000}. The result is normalised so it never uses exponent form.
   *
   * @param operand the value to round
   * @param scale the target scale, in {@code [-12, 12]}
   * @param mode the rounding mode
   * @return the rounded Decimal, or {@link Missing} if the operand is missing
   * @throws IllegalArgumentException if the scale is out of range — a compile-time concern (the
   *     scale must be an Integer literal), so reaching here with a bad scale is an engine defect
   */
  public static Value round(Value operand, int scale, RoundingMode mode) {
    if (operand.isMissing()) {
      return Missing.INSTANCE;
    }
    if (scale < MIN_ROUND_SCALE || scale > MAX_ROUND_SCALE) {
      throw new IllegalArgumentException(
          "round scale must be in ["
              + MIN_ROUND_SCALE
              + ", "
              + MAX_ROUND_SCALE
              + "], got "
              + scale);
    }
    BigDecimal rounded = toDecimal(operand).setScale(scale, mode);
    return decimal(rounded);
  }

  /**
   * Compares two numbers by value, ignoring scale (§3.6). {@code 1.0} and {@code 1.00} compare
   * equal.
   *
   * @param left the left operand
   * @param right the right operand
   * @return negative, zero or positive as {@code left} is less than, equal to or greater than
   *     {@code right}
   * @throws IllegalArgumentException if either operand is missing — callers must check first, since
   *     a missing operand makes the whole comparison missing rather than any ordering
   */
  public static int compare(Value left, Value right) {
    if (left.isMissing() || right.isMissing()) {
      throw new IllegalArgumentException(
          "compare() requires present operands; a missing operand makes the comparison missing");
    }
    if (left instanceof IntV a && right instanceof IntV b) {
      return Long.compare(a.value(), b.value());
    }
    return toDecimal(left).compareTo(toDecimal(right));
  }

  /**
   * Widens a number to {@link BigDecimal}. {@code Integer → Decimal} is the only implicit
   * conversion in the language (§3.4).
   *
   * @param value an {@link IntV} or {@link DecimalV}
   * @return the value as a BigDecimal
   */
  public static BigDecimal toDecimal(Value value) {
    return switch (value) {
      case IntV i -> BigDecimal.valueOf(i.value());
      case DecimalV d -> d.value();
      default -> throw new EvalException(EvalErrorCode.EVAL_INTERNAL, null, value.typeName());
    };
  }

  private static DecimalV decimal(BigDecimal value) {
    return new DecimalV(value);
  }
}
