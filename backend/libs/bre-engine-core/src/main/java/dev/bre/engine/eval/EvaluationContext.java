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
 * @param parameters policy parameters, bound under the {@code param.} namespace, each with its
 *     effective-dated values — a parameter is a table, not a value (§8.4)
 * @param lists named lists, bound under the {@code list.} namespace
 * @param asOf the evaluation date — the engine's only notion of "today" (§3.7)
 * @param it the current element inside a filter, or {@link Missing} outside one
 * @param fuel the decision's fuel budget (§3.10), shared by every component
 * @param variables the policy's declared variables, resolved lazily and memoised per entity
 * @param listCap the list-size cap (§3.10), which bounds the only unbounded work BEL can do
 * @param recorder the explanation recorder (§4.12). {@code TraceRecorder.NONE} on the decision
 *     path: an explanation is regenerated on demand by re-running with one attached, so a decision
 *     never pays for a trace nobody asked for.
 * @param probe the coverage probe (FR-TEST-003). {@code CoverageProbe.NONE} on the decision path,
 *     for the same reason as the recorder: which rows a run exercised is a studio question.
 */
public record EvaluationContext(
    RecordV facts,
    ParameterTable parameters,
    Map<String, Value> lists,
    LocalDate asOf,
    Value it,
    FuelMeter fuel,
    VariableResolver variables,
    ListCap listCap,
    dev.bre.engine.trace.TraceRecorder recorder,
    dev.bre.engine.cover.CoverageProbe probe) {

  /** Creates a context, copying the maps so the context is genuinely immutable. */
  public EvaluationContext {
    Objects.requireNonNull(facts, "facts");
    Objects.requireNonNull(asOf, "asOf");
    Objects.requireNonNull(it, "it");
    parameters = parameters == null ? ParameterTable.EMPTY : parameters;
    lists = Map.copyOf(lists);
    fuel = fuel == null ? FuelMeter.unlimited() : fuel;
    variables = variables == null ? VariableResolver.none() : variables;
    listCap = listCap == null ? ListCap.defaults() : listCap;
    recorder = recorder == null ? dev.bre.engine.trace.TraceRecorder.NONE : recorder;
    probe = probe == null ? dev.bre.engine.cover.CoverageProbe.NONE : probe;
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
    return of(facts, ParameterTable.undated(parameters), lists, asOf, FuelMeter.unlimited());
  }

  /**
   * Creates a context with effective-dated parameters.
   *
   * <p>The overload above exists for every caller whose parameters are undated — a test fixture, a
   * conformance vector, a policy that declares none. This is the one the bundle path uses, because
   * a bundle carries the whole table (§8.4).
   *
   * @param facts the fact snapshot
   * @param parameters the {@code param.} namespace, with effective windows
   * @param lists the {@code list.} namespace
   * @param asOf the evaluation date, which is also what selects each parameter's value
   * @return the context
   */
  public static EvaluationContext of(
      RecordV facts, ParameterTable parameters, Map<String, Value> lists, LocalDate asOf) {
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
    return of(facts, ParameterTable.undated(parameters), lists, asOf, fuel);
  }

  /**
   * Creates a context with effective-dated parameters and an explicit fuel budget.
   *
   * @param facts the fact snapshot
   * @param parameters the {@code param.} namespace, with effective windows
   * @param lists the {@code list.} namespace
   * @param asOf the evaluation date
   * @param fuel the decision's fuel budget
   * @return the context
   */
  public static EvaluationContext of(
      RecordV facts,
      ParameterTable parameters,
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
        dev.bre.engine.trace.TraceRecorder.NONE,
        dev.bre.engine.cover.CoverageProbe.NONE);
  }

  /**
   * Returns a copy that records an explanation (§4.12).
   *
   * @param newRecorder the recorder
   * @return the context, recording
   */
  public EvaluationContext recording(dev.bre.engine.trace.TraceRecorder newRecorder) {
    return new EvaluationContext(
        facts, parameters, lists, asOf, it, fuel, variables, listCap, newRecorder, probe);
  }

  /**
   * Returns a copy that measures coverage (FR-TEST-003).
   *
   * <p>The probe accumulates across every context derived from this one — per entity, per filter
   * element — because it is the same object, not a copy. That is deliberate: coverage is a property
   * of the whole test suite, not of one entity's evaluation.
   *
   * @param newProbe the probe
   * @return the context, measuring
   */
  public EvaluationContext measuring(dev.bre.engine.cover.CoverageProbe newProbe) {
    return new EvaluationContext(
        facts, parameters, lists, asOf, it, fuel, variables, listCap, recorder, newProbe);
  }

  /**
   * Returns a copy with the policy's variables available to expressions.
   *
   * @param resolver the variable resolver
   * @return the derived context
   */
  public EvaluationContext withVariables(VariableResolver resolver) {
    return new EvaluationContext(
        facts, parameters, lists, asOf, it, fuel, resolver, listCap, recorder, probe);
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
        facts, parameters, lists, asOf, element, fuel, variables, listCap, recorder, probe);
  }

  /** Returns {@code asOf} as a BEL value. */
  public Value asOfValue() {
    return new DateV(asOf);
  }

  /**
   * Looks up a parameter's value on this decision's business date.
   *
   * <p>Resolved against {@code asOf}, not against today and not against whichever value happened to
   * be current when the bundle was built. A replay of a decision from March must use March's
   * threshold even if the figure changed in April, and that is the whole reason a parameter is a
   * table here.
   *
   * @param name the parameter name
   * @return its value on {@code asOf}, or {@link Missing} when the parameter is not bound at all
   * @throws EvalException {@code EVAL_PARAM_NOT_EFFECTIVE} when it is bound but no value is
   *     effective on that date
   */
  public Value parameter(String name) {
    return parameters.valueAt(name, asOf);
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
