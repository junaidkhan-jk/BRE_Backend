package dev.bre.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.bre.engine.bel.parse.Parser;
import dev.bre.engine.decide.ComponentResult;
import dev.bre.engine.decide.FlowEvaluator;
import dev.bre.engine.eval.EvaluationContext;
import dev.bre.engine.eval.Evaluator;
import dev.bre.engine.eval.Value;
import dev.bre.engine.ir.Ir;
import dev.bre.irjson.IrCodec;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.Map;

/**
 * Runs a conformance vector against the engine — both shapes.
 *
 * <p>An <b>expression</b> vector is parsed and evaluated, and its value rendered in the vector's
 * typed envelope. A <b>policy</b> vector's IR fragment goes through {@link IrCodec} and the whole
 * flow runs.
 *
 * <p>The comparison is <b>projected onto the keys the vector actually asserts</b>, and that choice
 * deserves explaining. The §13 table's policy rows assert different things from one another: some
 * name only a {@code status}, others a {@code decision}, others a list of {@code steps}. Demanding
 * that every vector describe the entire decision would mean inventing expectations the
 * specification never stated — and a vector asserting more than the LLD says is no longer a
 * transcription of it. So the engine produces its full outcome and this projects it down to the
 * keys the vector names. A vector that asserts nothing would therefore pass vacuously, which is why
 * the loader requires a non-empty {@code expect}.
 */
final class BreEngine implements EngineUnderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public ActualOutcome evaluate(ConformanceVector vector) {
    return vector.shape() == ConformanceVector.Shape.EXPRESSION
        ? evaluateExpression(vector)
        : evaluatePolicy(vector);
  }

  private ActualOutcome evaluateExpression(ConformanceVector vector) {
    Value result =
        Evaluator.evaluate(Parser.parseExpression(vector.expr().orElseThrow()), contextOf(vector));
    ObjectNode actual = JsonNodeFactory.instance.objectNode();
    actual.set("value", MAPPER.valueToTree(JsonFacts.toEnvelope(result)));
    return new ActualOutcome(actual);
  }

  private ActualOutcome evaluatePolicy(ConformanceVector vector) {
    JsonNode policyNode = vector.policy().orElseThrow();
    Ir.PolicyIr policy = IrCodec.readPolicy(policyNode);
    FlowEvaluator.DecisionResult decision = FlowEvaluator.evaluate(policy, contextOf(vector));

    ObjectNode outcome = fullOutcome(decision);
    bucketOf(policyNode, vector).ifPresent(bucket -> outcome.put("bucket", bucket));
    return new ActualOutcome(project(outcome, vector.expect()));
  }

  /**
   * The champion/challenger bucket, for a policy that declares a challenger (§4.11, C-01).
   *
   * <p>Read straight off the IR JSON rather than through the codec: the challenger list is
   * control-plane metadata the evaluator has no use for, and modelling it in the IR records only to
   * satisfy one vector family would put it on the hot path for nothing.
   */
  private java.util.Optional<Integer> bucketOf(JsonNode policyNode, ConformanceVector vector) {
    JsonNode challengers = policyNode.get("challengers");
    if (challengers == null || !challengers.isArray() || challengers.isEmpty()) {
      return java.util.Optional.empty();
    }
    JsonNode meta = policyNode.get("policy");
    if (meta == null || !meta.hasNonNull("tenantId")) {
      return java.util.Optional.empty();
    }
    JsonNode application = vector.facts().get("application");
    if (application == null || !application.hasNonNull("applicationRef")) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(
        dev.bre.engine.decide.TrafficSplitter.bucketOf(
            meta.get("tenantId").asText(),
            application.get("applicationRef").asText(),
            challengers.get(0).get("experimentId").asText()));
  }

  private EvaluationContext contextOf(ConformanceVector vector) {
    return EvaluationContext.of(
        JsonFacts.toFacts(vector.facts()),
        JsonFacts.toBindings(vector.params()),
        JsonFacts.toBindings(vector.lists()),
        LocalDate.parse(vector.asOf()));
  }

  /** Everything the engine knows about a decision, before projection. */
  private ObjectNode fullOutcome(FlowEvaluator.DecisionResult decision) {
    ObjectNode outcome = JsonNodeFactory.instance.objectNode();
    outcome.put("decision", decision.decision().name());
    outcome.put("stpEligible", decision.stpEligible());

    // A single-component policy — which most §13 rule rows are — reports its one step's status
    // and reason at the top level, because that is how the table states them.
    if (decision.steps().size() == 1) {
      FlowEvaluator.StepResult only = decision.steps().get(0);
      outcome.put("status", only.status().name());
      if (!only.entities().isEmpty()) {
        outcome.put("effectiveStatus", only.entities().get(0).effectiveStatus().name());
      }
      if (!only.entities().isEmpty()) {
        ComponentResult entity = only.entities().get(0);
        entity.reasonCode().ifPresent(reason -> outcome.put("reasonCode", reason));
        entity.error().ifPresent(error -> outcome.put("error", error));
        if (!entity.outputs().isEmpty()) {
          outcome.set("outputs", outputsOf(entity));
        }
      }
    }

    ArrayNode steps = outcome.putArray("steps");
    for (FlowEvaluator.StepResult step : decision.steps()) {
      ObjectNode node = steps.addObject();
      node.put("ref", step.ref());
      node.put("status", step.status().name());
      // The step's reason is its first non-passing entity's: a §13 row such as S12-01 states the
      // reason at step level, because a single-entity step has only one.
      step.entities().stream()
          .filter(entity -> entity.reasonCode().isPresent())
          .findFirst()
          .flatMap(ComponentResult::reasonCode)
          .ifPresent(reason -> node.put("reasonCode", reason));
      step.entities().stream()
          .filter(entity -> entity.error().isPresent())
          .findFirst()
          .flatMap(ComponentResult::error)
          .ifPresent(error -> node.put("error", error));

      ArrayNode entities = node.putArray("entities");
      for (ComponentResult entity : step.entities()) {
        ObjectNode entityNode = entities.addObject();
        entity.entityType().ifPresent(type -> entityNode.put("entityType", type));
        entity.entityRef().ifPresent(ref -> entityNode.put("entityRef", ref));
        entityNode.put("status", entity.status().name());
        entityNode.put("effectiveStatus", entity.effectiveStatus().name());
        entity.reasonCode().ifPresent(reason -> entityNode.put("reasonCode", reason));
        entity.deviationType().ifPresent(type -> entityNode.put("deviationType", type));
        // A measure is written bare, not in the typed envelope: the §13 rows state it as
        // "216000" and "D", because a measure is one scalar whose type the outcome already fixed.
        entity.measure().ifPresent(measure -> entityNode.put("measure", JsonFacts.plain(measure)));
        entity.worseWhen().ifPresent(worse -> entityNode.put("worseWhen", worse.name()));
        entity.requiredLevel().ifPresent(level -> entityNode.put("requiredLevel", level));
        entity.annotation().ifPresent(annotation -> entityNode.put("annotation", annotation));
        entity.approvalRef().ifPresent(ref -> entityNode.put("approvalRef", ref));
        entity.error().ifPresent(error -> entityNode.put("error", error));
        if (!entity.outputs().isEmpty()) {
          entityNode.set("outputs", outputsOf(entity));
        }
        if (!entity.adverseReasons().isEmpty()) {
          ArrayNode reasons = entityNode.putArray("adverseReasons");
          entity.adverseReasons().forEach(reasons::add);
        }
      }
    }
    return outcome;
  }

  private ObjectNode outputsOf(ComponentResult entity) {
    ObjectNode outputs = JsonNodeFactory.instance.objectNode();
    for (Map.Entry<String, Value> output : entity.outputs().entrySet()) {
      outputs.set(output.getKey(), MAPPER.valueToTree(JsonFacts.toEnvelope(output.getValue())));
    }
    return outputs;
  }

  /** Finds the actual element whose key field equals the expected element's, or null. */
  private static JsonNode matchByKey(JsonNode actualArray, JsonNode expectedElement, String key) {
    if (!expectedElement.hasNonNull(key)) {
      return null;
    }
    String wanted = expectedElement.get(key).asText();
    for (JsonNode candidate : actualArray) {
      if (candidate.hasNonNull(key) && candidate.get(key).asText().equals(wanted)) {
        return candidate;
      }
    }
    return null;
  }

  /**
   * Keeps only the keys the expectation names, recursively.
   *
   * <p>An object is narrowed to the expected keys. An array's elements are matched by identity
   * where they have one — {@code ref} for a step, {@code entityRef} for an entity — so a vector
   * asserting one step is compared against that step wherever it sits in the flow.
   */
  private static JsonNode project(JsonNode actual, JsonNode expected) {
    if (expected == null || actual == null) {
      return actual;
    }
    if (expected.isObject() && actual.isObject()) {
      ObjectNode projected = JsonNodeFactory.instance.objectNode();
      Iterator<String> names = expected.fieldNames();
      while (names.hasNext()) {
        String name = names.next();
        if (actual.has(name)) {
          projected.set(name, project(actual.get(name), expected.get(name)));
        }
      }
      return projected;
    }
    if (expected.isArray() && actual.isArray()) {
      ArrayNode projected = JsonNodeFactory.instance.arrayNode();
      for (JsonNode expectedElement : expected) {
        // Matched by identity where the element has one — `ref` for a step, `entityRef` for an
        // entity — and positionally otherwise. A §13 row naming one step asserts what THAT step
        // looked like, not that the flow had only one; comparing positionally would check the
        // expectation against whichever step happened to come first.
        JsonNode match = matchByKey(actual, expectedElement, "ref");
        if (match == null) {
          match = matchByKey(actual, expectedElement, "entityRef");
        }
        if (match == null) {
          int index = projected.size();
          match = index < actual.size() ? actual.get(index) : null;
        }
        if (match != null) {
          projected.add(project(match, expectedElement));
        }
      }
      return projected;
    }
    return actual;
  }
}
