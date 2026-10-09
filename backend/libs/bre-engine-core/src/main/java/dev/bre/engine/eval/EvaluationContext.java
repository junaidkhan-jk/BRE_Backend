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
 * @param fuel the decision's fuel budget (§3.10), shared by every component
 * @param variables the policy's declared variables, resolved lazily and memoised per entity
 * @param listCap the list-size cap (§3.10), which bounds the only unbounded work BEL can do
 * @param recorder the explanation recorder (§4.12). {@code TraceRecorder.NONE} on the decision
 *     path: an explanation is regenerated on demand by re-running with one attached, so a decision
 *     never pays for a trace nobody asked for.
 */
public record EvaluationContext(
    RecordV facts,
    Map<String, Value> parameters,
    Map<String, Value> lists,
    LocalDate asOf,
    Value it,
    FuelMeter fuel,
    VariableResolver variables,
    ListCap listCap,
    dev.bre.engine.trace.TraceRecorder recorder) {

  /** Creates a context, copying the maps so the context is genuinely immutable. */
  public EvaluationContext {
    Objects.requireNonNull(facts, "facts");
    Objects.requireNonNull(asOf, "asOf");
    Objects.requireNonNull(it, "it");
    parameters = Map.copyOf(parameters);
    lists = Map.copyOf(lists);
    fuel = fuel == null ? FuelMeter.unlimited() : fuel;
    variables = variables == null ? VariableResolver.none() : variables;
    listCap = listCap == null ? ListCap.defaults() : listCap;
    recorder = recorder == null ? dev.bre.engine.trace.TraceRecorder.NONE : recorder;
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
    return of(facts, parameters, lists, asOf, FuelMeter.unlimited());
  }

  /**
   * Creates a context for a top-level expression with an explicit fuel budget.
   *
   * @param facts the fact snapshot
   * @param parameters the {@code param.} namespace
   * @param lists the {@code list.} namespace
   * @param asOf the evaluation date
   * @param fuel the decision's fuel budget
   * @return the context
   */
  public static EvaluationContext of(
      RecordV facts,
      Map<String, Value> parameters,
      Map<String, Value> lists,
      LocalDate asOf,
      FuelMeter fuel) {
    return new EvaluationContext(
        facts,
        parameters,
        lists,
        asOf,
        Missing.INSTANCE,
        fuel,
        VariableResolver.none(),
        ListCap.defaults(),
        dev.bre.engine.trace.TraceRecorder.NONE);
  }

  /**
   * Returns a copy that records an explanation (§4.12).
   *
   * @param newRecorder the recorder
   * @return the context, recording
   */
  public EvaluationContext recording(dev.bre.engine.trace.TraceRecorder newRecorder) {
    return new EvaluationContext(
        facts, parameters, lists, asOf, it, fuel, variables, listCap, newRecorder);
  }

  /**
   * Returns a copy with the policy's variables available to expressions.
   *
   * @param resolver the variable resolver
   * @return the derived context
   */
  public EvaluationContext withVariables(VariableResolver resolver) {
    return new EvaluationContext(
        facts, parameters, lists, asOf, it, fuel, resolver, listCap, recorder);
  }

  /**
   * Returns a copy of this context with {@code it} bound to one element, for evaluating a filter
   * predicate.
   *
   * @param element the element to bind
   * @return the derived context
   */
  public EvaluationContext withIt(Value element) {
    return new EvaluationContext(
        facts, parameters, lists, asOf, element, fuel, variables, listCap, recorder);
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
