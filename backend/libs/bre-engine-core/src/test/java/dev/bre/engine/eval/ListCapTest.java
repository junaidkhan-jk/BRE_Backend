package dev.bre.engine.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bre.engine.bel.parse.Parser;
import dev.bre.engine.eval.Value.ListV;
import dev.bre.engine.eval.Value.RecordV;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The list-size cap (LLD §3.10). */
class ListCapTest {

  private static final LocalDate AS_OF = LocalDate.parse("2026-10-01");

  /** A context with a deliberately tiny cap, so the tests stay fast and readable. */
  private static EvaluationContext contextWithCap(int cap, RecordV facts) {
    return new EvaluationContext(
        facts,
        ParameterTable.EMPTY,
        Map.of(),
        AS_OF,
        Value.Missing.INSTANCE,
        FuelMeter.unlimited(),
        VariableResolver.none(),
        new ListCap(cap),
        dev.bre.engine.trace.TraceRecorder.NONE,
        dev.bre.engine.cover.CoverageProbe.NONE);
  }

  private static ListV tradelines(int count, long balance) {
    List<Value> elements = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      elements.add(
          new RecordV(
              Map.of("balance", new Value.IntV(balance), "status", new Value.TextV("ACTIVE"))));
    }
    return new ListV(elements);
  }

  private static Value evaluate(String expression, EvaluationContext context) {
    return Evaluator.evaluate(Parser.parseExpression(expression), context);
  }

  @Test
  void theSpecifiedDefaultIsTenThousand() {
    assertThat(ListCap.DEFAULT_MAX_LIST_SIZE).isEqualTo(10_000);
    assertThat(ListCap.defaults().maxSize()).isEqualTo(10_000);
  }

  @Test
  void aContextWithNoExplicitCapGetsTheDefault() {
    EvaluationContext context = EvaluationContext.of(RecordV.EMPTY, Map.of(), Map.of(), AS_OF);
    assertThat(context.listCap().maxSize()).isEqualTo(ListCap.DEFAULT_MAX_LIST_SIZE);
  }

  @Test
  void aListAtTheCapIsAccepted() {
    ListCap cap = new ListCap(3);
    assertThat(cap.of(List.of(new Value.IntV(1), new Value.IntV(2), new Value.IntV(3))).elements())
        .hasSize(3);
  }

  @Test
  void aListOverTheCapIsAnErrorNamingBothSizes() {
    assertThatThrownBy(
            () ->
                new ListCap(2).of(List.of(new Value.IntV(1), new Value.IntV(2), new Value.IntV(3))))
        .isInstanceOf(EvalException.class)
        .hasMessageContaining("3")
        .hasMessageContaining("2")
        .satisfies(
            e ->
                assertThat(((EvalException) e).code())
                    .isEqualTo(EvalErrorCode.EVAL_LIST_TOO_LARGE));
  }

  /** §3.10: capped "whether it arrives as a fact or is produced at runtime" — the fact half. */
  @Test
  void anOversizedFactListIsRejectedWhenItIsRead() {
    RecordV facts =
        new RecordV(Map.of("member", new RecordV(Map.of("tradelines", tradelines(50, 100)))));

    assertThatThrownBy(() -> evaluate("sum(member.tradelines.balance)", contextWithCap(10, facts)))
        .isInstanceOf(EvalException.class)
        .satisfies(
            e ->
                assertThat(((EvalException) e).code())
                    .isEqualTo(EvalErrorCode.EVAL_LIST_TOO_LARGE));
  }

  @Test
  void anOversizedFactListIsRejectedByAFilterToo() {
    RecordV facts =
        new RecordV(Map.of("member", new RecordV(Map.of("tradelines", tradelines(50, 100)))));

    assertThatThrownBy(
            () ->
                evaluate(
                    "sum(member.tradelines[it.status = \"ACTIVE\"].balance)",
                    contextWithCap(10, facts)))
        .isInstanceOf(EvalException.class)
        .satisfies(
            e ->
                assertThat(((EvalException) e).code())
                    .isEqualTo(EvalErrorCode.EVAL_LIST_TOO_LARGE));
  }

  /**
   * The runtime half, and the case the cap mainly exists for. BEL has no loops, so a projection
   * that flattens nested lists is the only way bounded inputs produce an unbounded result.
   */
  @Test
  void aFlatteningProjectionIsCappedAsItGrows() {
    // Ten members, each with ten tradelines: twenty elements under the cap individually, one
    // hundred after flattening.
    List<Value> members = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      members.add(new RecordV(Map.of("tradelines", tradelines(10, 100))));
    }
    RecordV facts =
        new RecordV(Map.of("group", new RecordV(Map.of("members", new ListV(members)))));

    assertThatThrownBy(
            () -> evaluate("sum(group.members.tradelines.balance)", contextWithCap(50, facts)))
        .as("ten bounded lists of ten multiply past a cap of fifty")
        .isInstanceOf(EvalException.class)
        .satisfies(
            e ->
                assertThat(((EvalException) e).code())
                    .isEqualTo(EvalErrorCode.EVAL_LIST_TOO_LARGE));

    assertThat(evaluate("sum(group.members.tradelines.balance)", contextWithCap(200, facts)))
        .as("the same expression under a cap that permits it")
        .isEqualTo(new Value.IntV(10_000));
  }

  @Test
  void aListLiteralIsCapped() {
    RecordV facts = RecordV.EMPTY;
    assertThatThrownBy(() -> evaluate("count([1, 2, 3, 4])", contextWithCap(3, facts)))
        .isInstanceOf(EvalException.class)
        .satisfies(
            e ->
                assertThat(((EvalException) e).code())
                    .isEqualTo(EvalErrorCode.EVAL_LIST_TOO_LARGE));
  }

  /**
   * Not a truncation. A borrower whose exposure was summed over the first N of M tradelines would
   * be assessed on an understated total — the direction that approves a loan it should not.
   */
  @Test
  void theCapErrorsRatherThanSilentlyTruncating() {
    RecordV facts =
        new RecordV(Map.of("member", new RecordV(Map.of("tradelines", tradelines(12, 1000)))));

    assertThatThrownBy(() -> evaluate("sum(member.tradelines.balance)", contextWithCap(10, facts)))
        .isInstanceOf(EvalException.class);

    assertThat(evaluate("sum(member.tradelines.balance)", contextWithCap(12, facts)))
        .as("under a cap that permits the list, the total is the whole total")
        .isEqualTo(new Value.IntV(12_000));
  }

  @Test
  void aNonPositiveCapIsRejectedAtConstruction() {
    assertThatThrownBy(() -> new ListCap(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ListCap(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  /** §9.6: the diagnostic carries sizes, never elements — a list element is a fact value. */
  @Test
  void theMessageNamesSizesAndNeverAnElement() {
    RecordV facts =
        new RecordV(
            Map.of(
                "member",
                new RecordV(
                    Map.of(
                        "tradelines",
                        new ListV(
                            List.of(
                                new RecordV(Map.of("balance", new Value.TextV("SENSITIVE-7788"))),
                                new RecordV(
                                    Map.of("balance", new Value.TextV("SENSITIVE-9900")))))))));

    assertThatThrownBy(() -> evaluate("member.tradelines.balance", contextWithCap(1, facts)))
        .isInstanceOf(EvalException.class)
        .hasMessageNotContaining("SENSITIVE");
  }
}
