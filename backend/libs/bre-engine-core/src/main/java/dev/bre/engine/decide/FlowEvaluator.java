package dev.bre.engine.decide;

import dev.bre.engine.eval.EvaluationContext;
import dev.bre.engine.eval.Value;
import dev.bre.engine.eval.Value.ListV;
import dev.bre.engine.eval.Value.RecordV;
import dev.bre.engine.eval.Value.TextV;
import dev.bre.engine.ir.Ir;
import dev.bre.engine.ir.Ir.PolicyIr;
import dev.bre.engine.ir.Ir.RuntimeStatus;
import dev.bre.engine.ir.Ir.Scope;
import dev.bre.engine.ir.Ir.StageDef;
import dev.bre.engine.ir.Ir.StepDef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs a policy's flow and resolves the decision (LLD §4.5, §4.7, §4.8).
 *
 * <p>Three behaviours here are load-bearing:
 *
 * <ul>
 *   <li><b>Per-entity iteration (§4.5).</b> A MEMBER-scope component runs once per group member,
 *       with {@code member} bound to that member, and the results are aggregated by the step's
 *       aggregate spec. A GROUP or APPLICATION component runs once.
 *   <li><b>Knockout (S12-01).</b> A FAIL in a knockout stage marks every later step NOT_APPLICABLE
 *       with {@code SYS_SKIPPED_KNOCKOUT} and stops. The stage's own steps all run first — §4.8
 *       evaluates the whole stage before testing for FAIL — so a knockout does not hide a second
 *       finding in the same stage.
 *   <li><b>Fail-closed precedence (§4.8, D9).</b> FAIL declines; ERROR, DATA_MISSING and REFER
 *       refer; DEVIATION approves-with-deviation. No status that means "we could not tell" can
 *       reach APPROVE.
 * </ul>
 */
public final class FlowEvaluator {

  /** The reason attached to steps a knockout skipped (S12-01). */
  public static final String SKIPPED_KNOCKOUT = "SYS_SKIPPED_KNOCKOUT";

  private FlowEvaluator() {}

  /**
   * One step's outcome.
   *
   * @param ref the step's component code
   * @param status the aggregated status
   * @param entities the per-entity results
   */
  public record StepResult(String ref, RuntimeStatus status, List<ComponentResult> entities) {
    /** Creates a step result, copying the entity list. */
    public StepResult {
      entities = List.copyOf(entities);
    }
  }

  /**
   * A whole decision.
   *
   * @param decision the overall decision
   * @param stpEligible whether this could go straight through
   * @param steps one entry per flow step, in flow order
   */
  public record DecisionResult(
      Outcomes.Decision decision, boolean stpEligible, List<StepResult> steps) {
    /** Creates a decision, copying the step list. */
    public DecisionResult {
      steps = List.copyOf(steps);
    }

    /**
     * Returns a step's result by component code.
     *
     * @param ref the component code
     * @return the step, if the flow ran it
     */
    public Optional<StepResult> step(String ref) {
      return steps.stream().filter(step -> step.ref().equals(ref)).findFirst();
    }
  }

  /**
   * Evaluates a policy.
   *
   * @param policy the policy IR
   * @param context the evaluation context at application scope
   * @return the decision
   */
  public static DecisionResult evaluate(PolicyIr policy, EvaluationContext context) {
    Components components = Components.index(policy);
    List<StepResult> steps = new ArrayList<>();
    boolean knockedOut = false;

    for (StageDef stage : policy.flow().stages()) {
      if (knockedOut) {
        stage.steps().forEach(step -> steps.add(skipped(step)));
        continue;
      }

      List<StepResult> stageSteps = new ArrayList<>(stage.steps().size());
      for (StepDef step : stage.steps()) {
        stageSteps.add(runStep(step, components, context));
      }
      steps.addAll(stageSteps);

      // The whole stage runs before the knockout test (§4.8), so a knockout stage still reports
      // every finding it made rather than only the first.
      if (stage.knockout()
          && stageSteps.stream().anyMatch(step -> step.status() == RuntimeStatus.FAIL)) {
        knockedOut = true;
      }
    }

    return new DecisionResult(decide(steps), isStpEligible(steps), steps);
  }

  private static StepResult skipped(StepDef step) {
    return new StepResult(
        step.ref(),
        RuntimeStatus.NOT_APPLICABLE,
        List.of(ComponentResult.of(step.ref(), RuntimeStatus.NOT_APPLICABLE, SKIPPED_KNOCKOUT)));
  }

  private static StepResult runStep(
      StepDef step, Components components, EvaluationContext context) {
    Scope scope = components.scopeOf(step.ref());
    List<EvaluationContext> entities = entitiesInScope(scope, context);

    List<ComponentResult> results = new ArrayList<>(entities.size());
    for (EvaluationContext entity : entities) {
      results.add(components.evaluate(step.ref(), entity));
    }

    RuntimeStatus status = Aggregation.aggregate(step.aggregate(), results, context);
    return new StepResult(step.ref(), status, results);
  }

  /**
   * Enumerates the entities a scope iterates over (§4.5).
   *
   * <p>{@code members} is derived, not a fact root: §3.8 defines it as the parties with role
   * MEMBER, in {@code group.memberPartyRefs} order. Deriving it rather than reading a {@code
   * members[]} array is what keeps one party list as the single source of truth.
   *
   * <p>ponytail: PARTY, HOUSEHOLD and COLLATERAL scopes fall back to a single application-scope
   * entity. MEMBER is the scope the MFI MVP needs (D4) and the one the vectors exercise; the others
   * arrive with the retail and MSME catalogues in Phase 2, and each needs its own ordering rule
   * from §4.5 rather than a guess here.
   */
  private static List<EvaluationContext> entitiesInScope(Scope scope, EvaluationContext context) {
    if (scope != Scope.MEMBER) {
      return List.of(context);
    }

    List<Value> members = derivedMembers(context);
    if (members.isEmpty()) {
      return List.of();
    }

    List<EvaluationContext> entities = new ArrayList<>(members.size());
    for (Value member : members) {
      Map<String, Value> fields = new LinkedHashMap<>(context.facts().fields());
      fields.put("member", member);
      entities.add(
          new EvaluationContext(
              new RecordV(fields),
              context.parameters(),
              context.lists(),
              context.asOf(),
              context.it()));
    }
    return entities;
  }

  private static List<Value> derivedMembers(EvaluationContext context) {
    Value group = context.facts().field("group");
    Value parties = context.facts().field("parties");
    if (!(parties instanceof ListV partyList)) {
      return List.of();
    }

    // In group.memberPartyRefs order when that is given, so the same facts always produce the
    // same per-member ordering — which matters because a vector asserts per-entity results.
    List<Value> ordered = new ArrayList<>();
    if (group instanceof RecordV groupRecord
        && groupRecord.field("memberPartyRefs") instanceof ListV refs) {
      for (Value ref : refs.elements()) {
        partyList.elements().stream()
            .filter(party -> hasPartyRef(party, ref))
            .findFirst()
            .ifPresent(ordered::add);
      }
      if (!ordered.isEmpty()) {
        return ordered;
      }
    }

    // Otherwise every party whose role is MEMBER, in declaration order.
    for (Value party : partyList.elements()) {
      if (party instanceof RecordV record
          && record.field("role") instanceof TextV role
          && "MEMBER".equals(role.value())) {
        ordered.add(party);
      }
    }
    return ordered;
  }

  private static boolean hasPartyRef(Value party, Value ref) {
    if (!(party instanceof RecordV record) || !(ref instanceof TextV wanted)) {
      return false;
    }
    Value partyRef = record.field("partyRef");
    return partyRef instanceof TextV actual && actual.value().equals(wanted.value());
  }

  /**
   * Decision precedence (§4.8, vectors P-01 to P-03).
   *
   * <p>Ordered so that nothing uncertain can approve: a FAIL declines even alongside an ERROR
   * (P-01), and an ERROR or a gap refers (P-03). This is D9 expressed as control flow rather than
   * as a convention anyone has to remember.
   */
  private static Outcomes.Decision decide(List<StepResult> steps) {
    boolean anyFail = false;
    boolean anyRefer = false;
    boolean anyDeviation = false;

    for (StepResult step : steps) {
      switch (step.status()) {
        case FAIL -> anyFail = true;
        case ERROR, DATA_MISSING, REFER -> anyRefer = true;
        case DEVIATION -> anyDeviation = true;
        case PASS, NOT_APPLICABLE -> {
          // nothing to carry
        }
      }
    }

    if (anyFail) {
      return Outcomes.Decision.DECLINE;
    }
    if (anyRefer) {
      return Outcomes.Decision.REFER;
    }
    return anyDeviation ? Outcomes.Decision.APPROVE_WITH_DEVIATION : Outcomes.Decision.APPROVE;
  }

  /** Straight-through processing is off the moment anything deviates (P-02). */
  private static boolean isStpEligible(List<StepResult> steps) {
    return steps.stream()
        .allMatch(
            step ->
                step.status() == RuntimeStatus.PASS
                    || step.status() == RuntimeStatus.NOT_APPLICABLE);
  }

  /** Indexes a policy's components by code, so a flow step's {@code ref} resolves in one place. */
  private record Components(
      Map<String, Ir.RuleDef> rules,
      Map<String, Ir.DecisionTableDef> tables,
      Map<String, Ir.ScorecardDef> scorecards) {

    static Components index(PolicyIr policy) {
      Map<String, Ir.RuleDef> rules = new LinkedHashMap<>();
      policy.rules().forEach(rule -> rules.put(rule.code(), rule));
      Map<String, Ir.DecisionTableDef> tables = new LinkedHashMap<>();
      policy.decisionTables().forEach(table -> tables.put(table.code(), table));
      Map<String, Ir.ScorecardDef> scorecards = new LinkedHashMap<>();
      policy.scorecards().forEach(scorecard -> scorecards.put(scorecard.code(), scorecard));
      return new Components(rules, tables, scorecards);
    }

    Scope scopeOf(String ref) {
      if (rules.containsKey(ref)) {
        return rules.get(ref).scope();
      }
      if (tables.containsKey(ref)) {
        return tables.get(ref).scope();
      }
      if (scorecards.containsKey(ref)) {
        return scorecards.get(ref).scope();
      }
      throw new dev.bre.engine.eval.EvalException(
          dev.bre.engine.eval.EvalErrorCode.EVAL_INTERNAL, ref, "UnknownComponent");
    }

    ComponentResult evaluate(String ref, EvaluationContext context) {
      if (rules.containsKey(ref)) {
        return ComponentEvaluator.evaluateRule(rules.get(ref), context);
      }
      if (tables.containsKey(ref)) {
        return ComponentEvaluator.evaluateTable(tables.get(ref), context);
      }
      if (scorecards.containsKey(ref)) {
        return ComponentEvaluator.evaluateScorecard(scorecards.get(ref), context);
      }
      // Every flow ref names exactly one component; the compiler enforces it (§2.2).
      throw new dev.bre.engine.eval.EvalException(
          dev.bre.engine.eval.EvalErrorCode.EVAL_INTERNAL, ref, "UnknownComponent");
    }
  }
}
