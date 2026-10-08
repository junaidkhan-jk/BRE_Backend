package dev.bre.engine.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.bre.engine.eval.Value.DecimalV;
import dev.bre.engine.eval.Value.IntV;
import dev.bre.engine.eval.Value.Missing;
import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Decimal semantics, LLD §3.6. These are the arithmetic halves of the {@code D-} conformance
 * vectors — the vectors themselves assert the same facts through BEL expressions once the parser
 * and evaluator land in M1-02 and M1-05.
 */
class DecimalsTest {

  private static DecimalV dec(String plain) {
    return DecimalV.of(plain);
  }

  private static String canonical(Value value) {
    return ((DecimalV) value).toCanonicalString();
  }

  @Nested
  @DisplayName("exact arithmetic, not binary floating point")
  class ExactArithmetic {

    @Test
    @DisplayName("D-01: 0.1 + 0.2 equals 0.3")
    void pointOnePlusPointTwoIsPointThree() {
      Value sum = Decimals.add(dec("0.1"), dec("0.2"));
      assertThat(Decimals.compare(sum, dec("0.3")))
          .as("the defect this closes is 0.1 + 0.2 == 0.30000000000000004 in binary floating point")
          .isZero();
    }

    @Test
    @DisplayName("D-05: equality ignores scale, so 1.0 equals 1.00")
    void equalityIgnoresScale() {
      assertThat(Decimals.compare(dec("1.0"), dec("1.00"))).isZero();
    }

    @Test
    @DisplayName("Integer + Integer stays Integer; a mixed operand widens to Decimal")
    void integerArithmeticStaysIntegral() {
      assertThat(Decimals.add(new IntV(2), new IntV(3))).isEqualTo(new IntV(5));
      assertThat(Decimals.add(new IntV(2), dec("0.5"))).isInstanceOf(DecimalV.class);
    }

    @Test
    @DisplayName("Integer overflow is EVAL_OVERFLOW, not a silent wrap")
    void integerOverflowIsAnError() {
      assertThatExceptionOfType(EvalException.class)
          .isThrownBy(() -> Decimals.add(new IntV(Long.MAX_VALUE), new IntV(1)))
          .satisfies(e -> assertThat(e.code()).isEqualTo(EvalErrorCode.EVAL_OVERFLOW));
    }
  }

  @Nested
  @DisplayName("division: scale 10 HALF_EVEN, and zero is an ERROR")
  class Division {

    @ParameterizedTest(name = "{0} / {1} = {2}")
    @CsvSource({
      "2, 3, 0.6666666667", // D-03a
      "4200.03, 8400, 0.5000035714", // D-04
      "1, 3, 0.3333333333",
    })
    void divisionUsesScaleTenHalfEven(String left, String right, String expected) {
      assertThat(canonical(Decimals.divide(dec(left), dec(right), null))).isEqualTo(expected);
    }

    @Test
    @DisplayName("D-03b/c: 1 / 3 * 3 is 0.9999999999, so it does not equal 1")
    void divisionIsNotAssociativeWithMultiplication() {
      Value third = Decimals.divide(dec("1"), dec("3"), null);
      Value roundTripped = Decimals.multiply(third, dec("3"));

      assertThat(canonical(roundTripped))
          .as("the engine does not pretend the lost digit came back")
          .isEqualTo("0.9999999999");
      assertThat(Decimals.compare(roundTripped, dec("1"))).isNotZero();
    }

    @Test
    @DisplayName("D-07: division by zero is EVAL_DIV_ZERO, never missing")
    void divisionByZeroIsAnErrorNotMissing() {
      assertThatExceptionOfType(EvalException.class)
          .isThrownBy(() -> Decimals.divide(dec("5000"), dec("0"), "member.income"))
          .satisfies(
              e -> {
                assertThat(e.code()).isEqualTo(EvalErrorCode.EVAL_DIV_ZERO);
                assertThat(e.factPath()).contains("member.income");
              });
    }

    @Test
    @DisplayName("the error message carries the fact path and type, never the value")
    void errorMessageCarriesNoFactValue() {
      EvalException thrown =
          catchEvalException(() -> Decimals.divide(dec("5000"), dec("0"), "member.income"));

      assertThat(thrown.getMessage())
          .as("a fact value in an exception message leaks PII into every stack trace downstream")
          .contains("member.income")
          .contains("Decimal")
          .doesNotContain("5000");
    }

    @Test
    @DisplayName("division always produces Decimal, even for two Integers")
    void divisionAlwaysWidens() {
      assertThat(Decimals.divide(new IntV(6), new IntV(3), null)).isInstanceOf(DecimalV.class);
    }
  }

  @Nested
  @DisplayName("round(x, s, MODE)")
  class Rounding {

    @ParameterizedTest(name = "round({0}, {1}, {2}) = {3}")
    @CsvSource({
      "4444444.44, -3, DOWN, 4444000", // D-06a
      "2.5, 0, HALF_EVEN, 2", // D-06b
      "2.5, 0, HALF_UP, 3", // D-06c
      "-2.5, 0, FLOOR, -3", // D-06d
      "0.500004, 2, HALF_UP, 0.5", // D-02b
    })
    void roundingFollowsTheMode(String input, int scale, RoundingMode mode, String expected) {
      assertThat(canonical(Decimals.round(dec(input), scale, mode))).isEqualTo(expected);
    }

    @Test
    @DisplayName("D-06a: a negative scale never leaves the result in exponent form")
    void negativeScaleIsNormalisedOutOfExponentForm() {
      String rounded = canonical(Decimals.round(dec("4444444.44"), -3, RoundingMode.DOWN));
      assertThat(rounded).isEqualTo("4444000").doesNotContain("E").doesNotContain("e");
    }

    @Test
    @DisplayName("D-02a: comparisons use the unrounded value unless the author rounds")
    void comparisonsUseTheUnroundedValue() {
      assertThat(Decimals.compare(dec("0.500004"), dec("0.5")))
          .as("0.500004 <= 0.5 is FALSE; the FOIR cap is not met")
          .isPositive();
      assertThat(
              Decimals.compare(
                  Decimals.round(dec("0.500004"), 2, RoundingMode.HALF_UP), dec("0.5")))
          .as("round(0.500004, 2, HALF_UP) <= 0.5 is TRUE once the author rounds explicitly")
          .isZero();
    }

    @Test
    @DisplayName("a scale outside [-12, 12] is rejected")
    void scaleIsBounded() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> Decimals.round(dec("1"), 13, RoundingMode.HALF_UP));
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> Decimals.round(dec("1"), -13, RoundingMode.HALF_UP));
    }
  }

  @Nested
  @DisplayName("missing propagates strictly through every operator (§3.5)")
  class MissingPropagation {

    @Test
    void everyArithmeticOperatorReturnsMissing() {
      Value missing = Missing.INSTANCE;
      assertThat(Decimals.add(dec("1"), missing)).isEqualTo(missing);
      assertThat(Decimals.subtract(missing, dec("1"))).isEqualTo(missing);
      assertThat(Decimals.multiply(dec("1"), missing)).isEqualTo(missing);
      assertThat(Decimals.divide(dec("1"), missing, null)).isEqualTo(missing);
      assertThat(Decimals.negate(missing)).isEqualTo(missing);
      assertThat(Decimals.round(missing, 2, RoundingMode.HALF_UP)).isEqualTo(missing);
    }

    @Test
    @DisplayName("a missing divisor is missing, not EVAL_DIV_ZERO")
    void aMissingDivisorDoesNotBecomeADivisionError() {
      assertThat(Decimals.divide(dec("1"), Missing.INSTANCE, null)).isEqualTo(Missing.INSTANCE);
    }
  }

  @Test
  @DisplayName("§3.6: results serialise as stripTrailingZeros().toPlainString()")
  void canonicalSerialisationStripsTrailingZeros() {
    assertThat(dec("212500.0000000000").toCanonicalString()).isEqualTo("212500");
    assertThat(dec("1.50").toCanonicalString()).isEqualTo("1.5");
    assertThat(dec("0").toCanonicalString()).isEqualTo("0");
  }

  private static EvalException catchEvalException(Runnable action) {
    try {
      action.run();
      throw new AssertionError("expected an EvalException");
    } catch (EvalException e) {
      return e;
    }
  }
}
