package dev.bre.engine.trace;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bre.engine.bel.parse.Compiled;
import dev.bre.engine.eval.EvaluationContext;
import dev.bre.engine.eval.Evaluator;
import dev.bre.engine.eval.Value;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The explanation trace (LLD §4.12). */
class TraceRecorderTest {

  private static final LocalDate AS_OF = LocalDate.parse("2026-10-01");

  private static EvaluationContext context(TraceRecorder recorder) {
    Value.RecordV member =
        new Value.RecordV(
            Map.of(
                "income", Value.DecimalV.of("45000.00"),
                "emi", Value.DecimalV.of("18000.00"),
                "score", new Value.IntV(712)));
    return EvaluationContext.of(
            new Value.RecordV(Map.of("member", member)), Map.of(), Map.of(), AS_OF)
        .recording(recorder);
  }

  private static ExprTrace traceOf(String expression, TraceRecorder recorder) {
    Evaluator.evaluate(Compiled.expression(expression), context(recorder));
    return recorder.trace().orElseThrow();
  }

  @Nested
  @DisplayName("what a trace shows")
  class Structure {

    /** The point of an explanation: the arithmetic is checkable by eye, step by step. */
    @Test
    void everySubExpressionCarriesItsValue() {
      ExprTrace trace = traceOf("member.emi / member.income <= 0.5", TraceRecorder.unmasked());

      assertThat(trace.value()).isEqualTo("true");
      assertThat(trace.type()).isEqualTo("Boolean");
      assertThat(trace.children()).isNotEmpty();

      List<String> values = new ArrayList<>();
      collect(trace, values);
      assertThat(values)
          .as("the borrower's figures and the derived ratio all appear")
          .anyMatch(v -> v.contains("18000"))
          .anyMatch(v -> v.contains("45000"));
    }

    private void collect(ExprTrace node, List<String> into) {
      into.add(node.value());
      node.children().forEach(child -> collect(child, into));
    }

    @Test
    void aMissingValueReadsAsMissingNotAsZero() {
      ExprTrace trace = traceOf("member.noSuchField > 0", TraceRecorder.unmasked());

      assertThat(trace.toString())
          .as("absence must never render as a number somebody could mistake for data")
          .contains(ExprTrace.MISSING);
    }

    @Test
    void theSourceAndSpanAreRecorded() {
      ExprTrace trace = traceOf("member.score >= 650", TraceRecorder.unmasked());

      assertThat(trace.src()).isNotBlank();
      assertThat(trace.span().start()).isGreaterThanOrEqualTo(0);
      assertThat(trace.span().end()).isGreaterThanOrEqualTo(trace.span().start());
    }
  }

  @Nested
  @DisplayName("§9.4 masking")
  class Masking {

    /** The default for anyone without unmask rights: structure visible, figures not. */
    @Test
    void aMaskingRecorderHidesFactValuesButKeepsTheShape() {
      ExprTrace trace = traceOf("member.emi / member.income <= 0.5", TraceRecorder.masking());

      assertThat(trace.value())
          .as("the OUTCOME is not a fact value and stays visible")
          .isEqualTo("true");
      assertThat(trace.containsMasked()).isTrue();
      assertThat(trace.toString()).doesNotContain("18000").doesNotContain("45000");
    }

    @Test
    void anUnmaskedRecorderShowsTheValues() {
      ExprTrace trace = traceOf("member.income > 0", TraceRecorder.unmasked());

      assertThat(trace.containsMasked()).isFalse();
      assertThat(trace.toString()).contains("45000");
    }

    /** Masking happens at RECORD time, so a masked value is never in the object at all. */
    @Test
    void aMaskedValueIsNeverPresentInTheTraceObject() {
      ExprTrace trace = traceOf("member.income > 0", TraceRecorder.masking());

      List<String> all = new ArrayList<>();
      walk(trace, all);
      assertThat(all)
          .as("not merely hidden at render time — it was never recorded")
          .noneMatch(v -> v.contains("45000"));
    }

    private void walk(ExprTrace node, List<String> into) {
      into.add(node.value());
      node.children().forEach(child -> walk(child, into));
    }
  }

  @Nested
  @DisplayName("§4.12 limits")
  class Limits {

    @Test
    void aLongListIsTruncatedAndSaysSo() {
      List<Value> many = new ArrayList<>();
      for (int i = 0; i < 50; i++) {
        many.add(new Value.IntV(i));
      }
      TraceRecorder recorder = TraceRecorder.unmasked();
      EvaluationContext ctx =
          EvaluationContext.of(
                  new Value.RecordV(Map.of("xs", new Value.ListV(many))), Map.of(), Map.of(), AS_OF)
              .recording(recorder);
      Evaluator.evaluate(Compiled.expression("count(xs)"), ctx);

      ExprTrace trace = recorder.trace().orElseThrow();
      List<ExprTrace> nodes = new ArrayList<>();
      flatten(trace, nodes);
      assertThat(nodes)
          .anySatisfy(
              node ->
                  node.note()
                      .ifPresent(
                          note ->
                              assertThat(note)
                                  .contains("first " + ExprTrace.MAX_LIST_ELEMENTS)
                                  .contains("50")));
    }

    private void flatten(ExprTrace node, List<ExprTrace> into) {
      into.add(node);
      node.children().forEach(child -> flatten(child, into));
    }

    @Test
    void theLimitsAreTheSpecifiedOnes() {
      assertThat(ExprTrace.MAX_LIST_ELEMENTS).isEqualTo(20);
      assertThat(ExprTrace.MAX_DEPTH).isEqualTo(64);
    }
  }

  @Nested
  @DisplayName("the decision path pays nothing")
  class NoOp {

    @Test
    void theDefaultRecorderRecordsNothing() {
      assertThat(TraceRecorder.NONE.isRecording()).isFalse();

      EvaluationContext plain =
          EvaluationContext.of(Value.RecordV.EMPTY, Map.of(), Map.of(), AS_OF);
      assertThat(plain.recorder().isRecording())
          .as("a decision never pays for a trace nobody asked for")
          .isFalse();

      Evaluator.evaluate(Compiled.expression("1 + 1"), plain);
      assertThat(TraceRecorder.NONE.trace()).isEmpty();
    }

    /** Recording must not change the answer. That is what makes replay meaningful. */
    @Test
    void recordingDoesNotChangeTheResult() {
      for (String expression :
          List.of(
              "member.emi / member.income <= 0.5",
              "member.score >= 650 and member.income > 0",
              "if member.score >= 650 then 1 else 0",
              "member.noSuchField > 0")) {
        Value plain =
            Evaluator.evaluate(
                Compiled.expression(expression),
                EvaluationContext.of(
                    context(TraceRecorder.NONE).facts(), Map.of(), Map.of(), AS_OF));
        Value recorded =
            Evaluator.evaluate(Compiled.expression(expression), context(TraceRecorder.unmasked()));

        assertThat(recorded).as("%s", expression).isEqualTo(plain);
      }
    }

    /** A failing evaluation must still leave the recorder balanced, not half-open. */
    @Test
    void anErrorDoesNotCorruptTheTrace() {
      TraceRecorder recorder = TraceRecorder.unmasked();
      try {
        Evaluator.evaluate(Compiled.expression("member.income / 0"), context(recorder));
      } catch (RuntimeException expected) {
        // EVAL_DIV_ZERO
      }
      assertThat(recorder.trace()).as("the root frame was closed on the way out").isPresent();
    }
  }
}
