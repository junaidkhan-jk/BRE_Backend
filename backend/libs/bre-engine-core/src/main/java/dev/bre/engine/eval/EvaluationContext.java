package dev.bre.engine.eval;

import dev.bre.engine.eval.Value.DateV;
import dev.bre.engine.eval.Value.Missing;
import dev.bre.engine.eval.Value.RecordV;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/**
 * Everything an expression is allowed to see while it evaluates.
 *
 * <p>This is deliberately a closed set. The engine is a pure function of {@code (facts, bundle,
 * asOf)} (D2), so there is no clock, no I/O, no service handle and no ambient state here — if a
 * value is not in this context, an expression cannot reach it. That is what makes replay exact.
 *
 * @param facts the fact snapshot, as a record whose top-level fields are the fact roots
 * @param parameters policy parameters, bound under the {@code param.} namespace
 * @param lists named lists, bound under the {@code list.} namespace
 * @param asOf the evaluation date — the engine's only notion of "today" (§3.7)
 * @param it the current element inside a filter, or {@link Missing} outside one
 */
public record EvaluationContext(
    RecordV facts,
    Map<String, Value> parameters,
    Map<String, Value> lists,
    LocalDate asOf,
    Value it) {

  /** Creates a context, copying the maps so the context is genuinely immutable. */
  public EvaluationContext {
    Objects.requireNonNull(facts, "facts");
    Objects.requireNonNull(asOf, "asOf");
    Objects.requireNonNull(it, "it");
    parameters = Map.copyOf(parameters);
    lists = Map.copyOf(lists);
  }

  /**
   * Creates a context for a top-level expression, with no {@code it} binding.
   *
   * @param facts the fact snapshot
   * @param parameters the {@code param.} namespace
   * @param lists the {@code list.} namespace
   * @param asOf the evaluation date
   * @return the context
   */
  public static EvaluationContext of(
      RecordV facts, Map<String, Value> parameters, Map<String, Value> lists, LocalDate asOf) {
    return new EvaluationContext(facts, parameters, lists, asOf, Missing.INSTANCE);
  }

  /**
   * Returns a copy of this context with {@code it} bound to one element, for evaluating a filter
   * predicate.
   *
   * @param element the element to bind
   * @return the derived context
   */
  public EvaluationContext withIt(Value element) {
    return new EvaluationContext(facts, parameters, lists, asOf, element);
  }

  /** Returns {@code asOf} as a BEL value. */
  public Value asOfValue() {
    return new DateV(asOf);
  }

  /**
   * Looks up a parameter.
   *
   * @param name the parameter name
   * @return its value, or {@link Missing} when it is not bound
   */
  public Value parameter(String name) {
    return parameters.getOrDefault(name, Missing.INSTANCE);
  }

  /**
   * Looks up a named list.
   *
   * @param name the list name
   * @return its value, or {@link Missing} when it is not bound
   */
  public Value list(String name) {
    return lists.getOrDefault(name, Missing.INSTANCE);
  }
}
