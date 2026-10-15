package dev.bre.engine.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Effective-dated parameters (§8.4, FR-MODEL-007, AC-MODEL-003).
 *
 * <p>The behaviour this pins down is the one that makes a replay honest. A lender's indebtedness
 * cap changes on the date a regulator says it changes, and both figures stay true — the old one for
 * every application decided before that date. Before this existed, a parameter was one value, so
 * re-deciding a March application in May would have used May's cap and produced a different answer
 * to the same question.
 */
class ParameterTableTest {

  private static final LocalDate MARCH = LocalDate.parse("2026-03-15");
  private static final LocalDate APRIL_FIRST = LocalDate.parse("2026-04-01");
  private static final LocalDate APRIL = LocalDate.parse("2026-04-15");

  private static ParameterTable cap() {
    return ParameterTable.dated(
        Map.of(
            "INDEBTEDNESS_CAP",
            List.of(
                new ParameterTable.Window(
                    LocalDate.parse("2025-04-01"),
                    Optional.of(APRIL_FIRST),
                    new Value.DecimalV(new BigDecimal("200000.00"))),
                new ParameterTable.Window(
                    APRIL_FIRST,
                    Optional.empty(),
                    new Value.DecimalV(new BigDecimal("300000.00"))))));
  }

  @Test
  @DisplayName("the value is the one effective on asOf, not the latest one")
  void theValueFollowsAsOf() {
    assertThat(cap().valueAt("INDEBTEDNESS_CAP", MARCH))
        .as("a March application is decided under March's cap, forever")
        .isEqualTo(new Value.DecimalV(new BigDecimal("200000.00")));
    assertThat(cap().valueAt("INDEBTEDNESS_CAP", APRIL))
        .isEqualTo(new Value.DecimalV(new BigDecimal("300000.00")));
  }

  @Test
  @DisplayName("effectiveTo is exclusive, so consecutive windows meet without overlapping")
  void effectiveToIsExclusive() {
    // The one off-by-one that matters: on the changeover date itself the NEW value applies. A
    // closed upper bound would make both windows cover 1 April, and which one won would depend on
    // iteration order.
    assertThat(cap().valueAt("INDEBTEDNESS_CAP", APRIL_FIRST))
        .isEqualTo(new Value.DecimalV(new BigDecimal("300000.00")));
    assertThat(cap().valueAt("INDEBTEDNESS_CAP", APRIL_FIRST.minusDays(1)))
        .isEqualTo(new Value.DecimalV(new BigDecimal("200000.00")));
  }

  @Test
  @DisplayName("a parameter the policy never declared reads as Missing, and fails closed")
  void undeclaredIsMissing() {
    assertThat(cap().valueAt("NO_SUCH_PARAMETER", MARCH)).isEqualTo(Value.Missing.INSTANCE);
    assertThat(ParameterTable.EMPTY.valueAt("ANYTHING", MARCH)).isEqualTo(Value.Missing.INSTANCE);
  }

  @Test
  @DisplayName("a declared parameter with no window covering asOf is an ERROR, not Missing")
  void notEffectiveIsAnError() {
    // Missing means "we do not know this applicant's income" and fails closed to a REFER, which is
    // right for absent data. A threshold with no value on the date being decided is a defect in
    // the policy's own configuration, and saying so precisely is the difference between an author
    // fixing their parameter set and an author hunting for a fact that was never the problem.
    ParameterTable future =
        ParameterTable.dated(
            Map.of(
                "FUTURE_CAP",
                List.of(
                    new ParameterTable.Window(APRIL_FIRST, Optional.empty(), new Value.IntV(5)))));

    assertThatThrownBy(() -> future.valueAt("FUTURE_CAP", MARCH))
        .isInstanceOf(EvalException.class)
        .hasFieldOrPropertyWithValue("code", EvalErrorCode.EVAL_PARAM_NOT_EFFECTIVE);
  }

  @Test
  @DisplayName("an undated parameter applies on every date")
  void undatedAppliesAlways() {
    ParameterTable flat = ParameterTable.undated(Map.of("MIN_AGE", new Value.IntV(18)));

    assertThat(flat.valueAt("MIN_AGE", MARCH)).isEqualTo(new Value.IntV(18));
    assertThat(flat.valueAt("MIN_AGE", LocalDate.parse("1990-01-01")))
        .as("declared without dates means it has always applied — not that it applies nowhere")
        .isEqualTo(new Value.IntV(18));
  }

  @Test
  @DisplayName("the context resolves param.X against the decision's own asOf")
  void theContextUsesItsOwnAsOf() {
    // The property that actually matters: nothing has to remember to pass a date. The decision's
    // asOf is already in the context, so every `param.X` in every expression resolves against it.
    EvaluationContext march =
        EvaluationContext.of(new Value.RecordV(Map.of()), cap(), Map.of(), MARCH);
    EvaluationContext april =
        EvaluationContext.of(new Value.RecordV(Map.of()), cap(), Map.of(), APRIL);

    assertThat(march.parameter("INDEBTEDNESS_CAP"))
        .isEqualTo(new Value.DecimalV(new BigDecimal("200000.00")));
    assertThat(april.parameter("INDEBTEDNESS_CAP"))
        .isEqualTo(new Value.DecimalV(new BigDecimal("300000.00")));
  }

  @Test
  @DisplayName("change dates are listed for the static checks' parameter epochs")
  void changeDates() {
    // §4.13.1 analyses a decision table once per parameter epoch — every distinct combination of
    // effective values over the next year. These are the dates where a combination changes.
    assertThat(cap().changeDates(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")))
        .containsExactly(APRIL_FIRST);
    assertThat(
            ParameterTable.undated(Map.of("MIN_AGE", new Value.IntV(18)))
                .changeDates(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")))
        .as("a parameter that never changes contributes no epoch boundary")
        .isEmpty();
  }
}
