package dev.bre.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bre.engine.bel.parse.Parser;
import dev.bre.engine.eval.EvaluationContext;
import dev.bre.engine.eval.Evaluator;
import dev.bre.engine.eval.Value;
import java.time.LocalDate;
import java.util.Map;

/**
 * Runs <b>expression</b> vectors against the real engine: parse the {@code expr}, evaluate it
 * against the vector's facts, and render the result in the vector's {@code expect} shape.
 *
 * <p>Policy vectors — the ones carrying an IR fragment and expecting a {@code status}, {@code
 * decision} or {@code steps} — still report as pending. They need the IR, the component model and
 * the aggregation rules (M1-07 to M1-09), none of which exist yet. Reporting them as pending rather
 * than failing keeps the distinction visible: a pending vector is work not done, a failing one is
 * work done wrong.
 */
final class BelExpressionEngine implements EngineUnderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public ActualOutcome evaluate(ConformanceVector vector) {
    if (vector.shape() == ConformanceVector.Shape.POLICY) {
      throw new EngineUnderTest.NotImplemented(
          vector.id() + " is a policy vector; the IR and component model land in M1-07..M1-09");
    }

    String source = vector.expr().orElseThrow();
    Value result =
        Evaluator.evaluate(
            Parser.parseExpression(source),
            EvaluationContext.of(
                JsonFacts.toFacts(vector.facts()),
                JsonFacts.toBindings(vector.params()),
                JsonFacts.toBindings(vector.lists()),
                LocalDate.parse(vector.asOf())));

    Map<String, Object> value = JsonFacts.toEnvelope(result);
    return new ActualOutcome(MAPPER.valueToTree(Map.of("value", value)));
  }
}
