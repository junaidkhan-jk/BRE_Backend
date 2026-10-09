package dev.bre.engine.decide;

import dev.bre.engine.eval.EvalErrorCode;
import dev.bre.engine.eval.EvaluationContext;
import dev.bre.engine.eval.Value;
import dev.bre.engine.eval.Value.ListV;
import dev.bre.engine.eval.Value.RecordV;
import dev.bre.engine.eval.Value.TextV;
import dev.bre.engine.eval.VariableResolver;
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

  /** The reason every runtime error carries (§3.11). */
  public static final String SYS_ERROR = "SYS_ERROR";

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
      Outcomes.Decision decision,
      boolean stpEligible,
      List<StepResult> steps,
      List<RequiredApprovals.Item> requiredApprovals,
      List<Reasons.CustomerReason> customerReasons,
      List<String> internalReasons) {

    /** Creates a decision, copying every list. */
    public DecisionResult {
      steps = List.copyOf(steps);
      requiredApprovals = List.copyOf(requiredApprovals);
      customerReasons = List.copyOf(customerReasons);
      internalReasons = List.copyOf(internalReasons);
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
   * Evaluates a policy with no reason catalogue, so no customer reasons are produced.
   *
   * <p>Correct for a conformance fragment and for any caller that does not disclose reasons to an
   * applicant. It is not a shortcut for a real decline: without a catalogue nothing is marked
   * {@code adverse}, and §4.10 discloses nothing — see {@link ReasonCatalogue}.
   *
   * @param policy the policy IR
   * @param policyContext the evaluation context at application scope
   * @return the decision
   */
  public static DecisionResult evaluate(PolicyIr policy, EvaluationContext policyContext) {
    return evaluate(policy, policyContext, ReasonCatalogue.EMPTY, ReasonCatalogue.FALLBACK_LOCALE);
  }

  /**
   * Evaluates a policy.
   *
   * @param policy the policy IR
   * @param policyContext the evaluation context at application scope
   * @param catalogue the reason catalogue customer reasons are selected and rendered through
   * @param locale the requested BCP 47 language tag for customer wording
   * @return the decision
   */
  public static DecisionResult evaluate(
      PolicyIr policy, EvaluationContext policyContext, ReasonCatalogue catalogue, String locale) {
    // The fuel budget belongs to the policy, so it is installed here rather than left to every
    // caller to remember (§3.10). One meter per decision, shared by every component.
    Map<String, String> variableExpressions = new LinkedHashMap<>();
    policy
        .variables()
        .forEach(variable -> variableExpressions.put(variable.name(), variable.expr()));

    EvaluationContext context =
        policy
            .fuelLimit()
            .map(
                limit ->
                    new EvaluationContext(
                        policyContext.facts(),
                        policyContext.parameters(),
                        policyContext.lists(),
                        policyContext.asOf(),
                        policyContext.it(),
                        new dev.bre.engine.eval.FuelMeter(limit),
                        new VariableResolver(variableExpressions),
                        policyContext.listCap()))
            .orElseGet(
                () -> policyContext.withVariables(new VariableResolver(variableExpressions)));

    Components components = Components.index(policy);
    // Resolved once, at application scope: the authority amount is a property of the application,
    // not of whichever member happens to be under evaluation.
    Optional<Value> authorityAmount =
        policy
            .authorityAmount()
            .map(
                expr ->
                    dev.bre.engine.eval.Evaluator.evaluate(
                        dev.bre.engine.bel.parse.Compiled.expression(expr), context));
    List<StepResult> steps = new ArrayList<>();
    boolean knockedOut = false;

    for (StageDef stage : policy.flow().stages()) {
      if (knockedOut) {
        stage.steps().forEach(step -> steps.add(skipped(step)));
        continue;
      }

      List<StepResult> stageSteps = new ArrayList<>(stage.steps().size());
      for (StepDef step : stage.steps()) {
        stageSteps.add(runStep(step, components, context, policy, authorityAmount));
      }
      steps.addAll(stageSteps);

      // The whole stage runs before the knockout test (§4.8), so a knockout stage still reports
      // every finding it made rather than only the first.
      if (stage.knockout()
          && stageSteps.stream().anyMatch(step -> step.status() == RuntimeStatus.FAIL)) {
        knockedOut = true;
      }
    }

    Outcomes.Decision decision = decide(steps);
    List<Reasons.Ordered> ordered = inFlowOrder(policy, components, steps);

    return new DecisionResult(
        decision,
        isStpEligible(steps),
        steps,
        RequiredApprovals.resolve(
            ordered.stream().map(Reasons.Ordered::result).toList(), policy.authorityMatrix()),
        Reasons.customerReasons(decision, ordered, catalogue, policy.customerReasonLimit(), locale),
        Reasons.internalReasons(ordered));
  }

  /**
   * Every entity result tagged with its stage and step position, in flow order.
   *
   * <p>Both §4.9's approval order ("flow order, then entity order") and §4.10's reason order are
   * defined over these positions, so they are recovered once here rather than guessed from the flat
   * step list. {@code steps} is in flow order already — including the steps a knockout skipped,
   * which is why the walk follows the flow rather than re-deriving it.
   */
  private static List<Reasons.Ordered> inFlowOrder(
      PolicyIr policy, Components components, List<StepResult> steps) {

    List<Reasons.Ordered> out = new ArrayList<>();
    int flat = 0;
    List<StageDef> stages = policy.flow().stages();
    for (int stageIndex = 0; stageIndex < stages.size(); stageIndex++) {
      List<StepDef> stageSteps = stages.get(stageIndex).steps();
      for (int stepIndex = 0; stepIndex < stageSteps.size(); stepIndex++) {
        if (flat >= steps.size()) {
          break;
        }
        StepResult step = steps.get(flat++);
        Optional<Integer> priority = components.priorityOf(step.ref());
        for (ComponentResult entity : step.entities()) {
          out.add(new Reasons.Ordered(entity, priority, stageIndex, stepIndex));
        }
      }
    }
    return List.copyOf(out);
  }

  private static StepResult skipped(StepDef step) {
    return new StepResult(
        step.ref(),
        RuntimeStatus.NOT_APPLICABLE,
        List.of(ComponentResult.of(step.ref(), RuntimeStatus.NOT_APPLICABLE, SKIPPED_KNOCKOUT)));
  }

  private static StepResult runStep(
      StepDef step,
      Components components,
      EvaluationContext context,
      PolicyIr policy,
      Optional<Value> authorityAmount) {

    Scope scope = components.scopeOf(step.ref());

    if (context.fuel().isExhausted()) {
      // §3.10: fail-closed. A policy that ran out of fuel part way has not decided that its
      // remaining rules do not apply.
      return new StepResult(
          step.ref(),
          RuntimeStatus.ERROR,
          List.of(
              ComponentResult.error(step.ref(), EvalErrorCode.EVAL_FUEL_EXHAUSTED.name())
                  .withReasonCode(SYS_ERROR)));
    }

    List<Entity> entities = entitiesInScope(scope, context, step.roles());

    List<ComponentResult> results = new ArrayList<>(entities.size());
    for (Entity entity : entities) {
      ComponentResult raw =
          components.evaluate(step.ref(), entity.context()).forEntity(scope.name(), entity.ref());
      // §4.9: coverage and authority are resolved per entity, BEFORE aggregation — so an approved
      // deviation is already an effective PASS by the time the step's status is computed.
      if (raw.status() == RuntimeStatus.ERROR && raw.reasonCode().isEmpty()) {
        raw = raw.withReasonCode(SYS_ERROR); // §3.11: every runtime error reports SYS_ERROR.
      }
      results.add(
          Coverage.apply(
              raw,
              policy.authorityMatrix(),
              authorityAmount,
              entity.context().facts(),
              entity.context().asOf()));
    }

    RuntimeStatus status = Aggregation.aggregate(step.aggregate(), results, context);
    return new StepResult(step.ref(), status, results);
  }

  /**
   * One entity in scope: the context the component sees, and the reference identifying it.
   *
   * @param context the per-entity evaluation context
   * @param ref the entity's reference, or null when the scope has no separate identity
   */
  private record Entity(EvaluationContext context, String ref) {}

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
  private static List<Entity> entitiesInScope(
      Scope scope, EvaluationContext context, List<String> roles) {

    // PARTY scope iterates the parties, optionally narrowed to the step's roles. §2.2 allows
    // `roles` only on a PARTY-scope step, which is why the filter lives here and not in the
    // component.
    if (scope == Scope.PARTY) {
      List<Entity> entities = new ArrayList<>();
      for (Value party : partiesOf(context)) {
        if (!roles.isEmpty() && !hasRole(party, roles)) {
          continue;
        }
        entities.add(new Entity(bind(context, "party", party), partyRef(party)));
      }
      return entities;
    }

    if (scope != Scope.MEMBER) {
      return List.of(new Entity(context, applicationRef(context)));
    }

    List<Value> members = derivedMembers(context);
    if (!members.isEmpty()) {
      List<Entity> entities = new ArrayList<>(members.size());
      for (Value member : members) {
        entities.add(new Entity(bind(context, "member", member), partyRef(member)));
      }
      return entities;
    }

    // A fact snapshot that binds `member` DIRECTLY, with no parties[] to derive from, is one
    // member already selected — which is how most of the §13 single-member vectors are written.
    // Returning no entities instead would aggregate to NOT_APPLICABLE and silently report that
    // the rule did not apply, when in truth it was never run.
    if (!context.facts().field("member").isMissing()) {
      return List.of(new Entity(context, partyRef(context.facts().field("member"))));
    }
    return List.of();
  }

  /** Returns a context with one extra fact root bound, for a per-entity evaluation. */
  private static EvaluationContext bind(EvaluationContext context, String name, Value value) {
    Map<String, Value> fields = new LinkedHashMap<>(context.facts().fields());
    fields.put(name, value);
    return new EvaluationContext(
        new RecordV(fields),
        context.parameters(),
        context.lists(),
        context.asOf(),
        context.it(),
        context.fuel(),
        // A fresh cache per entity: a MEMBER-scope variable is a different value for each member,
        // so sharing one cache across members would hand the first member's answer to all of them.
        context.variables().forNewEntity(),
        context.listCap());
  }

  private static List<Value> partiesOf(EvaluationContext context) {
    return context.facts().field("parties") instanceof ListV parties
        ? parties.elements()
        : List.of();
  }

  private static boolean hasRole(Value party, List<String> roles) {
    return party instanceof RecordV record
        && record.field("role") instanceof TextV role
        && roles.contains(role.value());
  }

  private static String applicationRef(EvaluationContext context) {
    if (context.facts().field("application") instanceof RecordV application
        && application.field("applicationRef") instanceof TextV ref) {
      return ref.value();
    }
    return null;
  }

  private static String partyRef(Value party) {
    if (party instanceof RecordV record && record.field("partyRef") instanceof TextV ref) {
      return ref.value();
    }
    return null;
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

  /**
   * Straight-through eligibility (P-02).
   *
   * <p>Read from each entity's <b>raw</b> status, not the step's effective one. A deviation that an
   * approver signed off is still a deviation that needed a human: the decision is APPROVE, and
   * {@code stpEligible} is false. Reading the effective status here would report a covered
   * deviation as straight-through and lose the distinction entirely.
   */
  private static boolean isStpEligible(List<StepResult> steps) {
    return steps.stream()
        .flatMap(step -> step.entities().stream())
        .allMatch(
            entity ->
                entity.status() == RuntimeStatus.PASS
                    || entity.status() == RuntimeStatus.NOT_APPLICABLE);
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

    /**
     * The component's declared priority, for §4.10's reason ordering.
     *
     * <p>A scorecard has none — the schema gives {@code priority} only to rules and tables — and an
     * absent priority sorts last, which is §4.10's {@code rule.priority ?: infinity}.
     */
    Optional<Integer> priorityOf(String ref) {
      if (rules.containsKey(ref)) {
        return rules.get(ref).priority();
      }
      if (tables.containsKey(ref)) {
        return tables.get(ref).priority();
      }
      return Optional.empty();
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
