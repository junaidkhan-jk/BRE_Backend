package dev.bre.conformance.failclosed;

import dev.bre.conformance.failclosed.FailClosedModel.Decision;
import dev.bre.conformance.failclosed.FailClosedModel.Engine;
import dev.bre.conformance.failclosed.FailClosedModel.Facts;
import dev.bre.conformance.failclosed.FailClosedModel.Rule;
import dev.bre.engine.decide.FlowEvaluator;
import dev.bre.engine.eval.EvaluationContext;
import dev.bre.engine.eval.Value;
import dev.bre.engine.ir.Ir;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The real engine, behind the toy {@link Engine} interface the fail-closed property is stated over.
 *
 * <p>This is what turns LLD §13 property 2 from a statement about a model into a statement about
 * the product. The stub engines in {@link StubEngines} proved the <em>harness</em> can catch a
 * fail-open engine; this proves the engine is not one.
 *
 * <p>The translation is deliberately the dullest possible: each toy rule becomes one BEL rule
 * reading one application-scope fact, and the whole policy becomes one non-knockout stage with
 * WORST aggregation. Nothing here may contain logic that could itself fail closed and mask a
 * fail-open engine behind it — if this adapter started interpreting statuses, a bug in it would
 * look like a passing property.
 *
 * <p>The toy model's {@code onMissing} is restricted to REFER and FAIL, which is exactly the
 * precondition §13 property 2 states: a policy using {@code NOT_APPLICABLE}, {@code isMissing} or
 * {@code coalesce} can legitimately approve on less data, so it is outside the property.
 */
final class RealEngine implements Engine {

  /** Fixed, because the engine must not read a clock (D8) and the property must be reproducible. */
  private static final LocalDate AS_OF = LocalDate.parse("2026-10-01");

  /** Shared: a stateless adapter, and the property runs it a million times. */
  static final RealEngine INSTANCE = new RealEngine();

  private RealEngine() {}

  @Override
  public Decision decide(List<Rule> policy, Facts facts) {
    FlowEvaluator.DecisionResult result =
        FlowEvaluator.evaluate(toPolicyIr(policy), toContext(facts));
    return switch (result.decision()) {
      case APPROVE -> Decision.APPROVE;
      case APPROVE_WITH_DEVIATION -> Decision.APPROVE_WITH_DEVIATION;
      case REFER -> Decision.REFER;
      case DECLINE -> Decision.DECLINE;
    };
  }

  private static Ir.PolicyIr toPolicyIr(List<Rule> policy) {
    List<Ir.RuleDef> rules = new ArrayList<>(policy.size());
    List<Ir.StepDef> steps = new ArrayList<>(policy.size());

    for (int i = 0; i < policy.size(); i++) {
      Rule rule = policy.get(i);
      // Distinct codes: two generated rules may read the same fact, and a flow step resolves its
      // component by code — colliding codes would silently run one rule twice.
      String code = "R" + i;
      rules.add(
          new Ir.RuleDef(
              code,
              code,
              Ir.Scope.APPLICATION,
              Optional.empty(),
              "application." + rule.factName() + " >= " + rule.threshold(),
              Ir.Outcome.fail("POL_" + code),
              new Ir.OnMissing(
                  switch (rule.onMissing()) {
                    case REFER -> Ir.MissingPolicy.REFER;
                    case FAIL -> Ir.MissingPolicy.FAIL;
                  },
                  Optional.empty(),
                  Optional.of("DATA_MISSING_" + code)),
              Map.of(),
              Optional.empty()));
      steps.add(Ir.StepDef.of(code, Ir.AggregateSpec.of(Ir.AggregateSpec.Kind.WORST)));
    }

    return new Ir.PolicyIr(
        1,
        List.of(),
        rules,
        List.of(),
        List.of(),
        new Ir.FlowDef(List.of(new Ir.StageDef("ELIGIBILITY", false, steps))),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Ir.PolicyIr.DEFAULT_CUSTOMER_REASON_LIMIT);
  }

  private static EvaluationContext toContext(Facts facts) {
    // An absent key is a missing fact — there is no explicit null, which matches both the vector
    // format and the fact model. Putting a null here instead would be testing something else.
    Map<String, Value> application = new LinkedHashMap<>();
    facts.values().forEach((name, value) -> application.put(name, new Value.IntV(value)));

    return EvaluationContext.of(
        new Value.RecordV(Map.of("application", new Value.RecordV(application))),
        Map.of(),
        Map.of(),
        AS_OF);
  }
}
