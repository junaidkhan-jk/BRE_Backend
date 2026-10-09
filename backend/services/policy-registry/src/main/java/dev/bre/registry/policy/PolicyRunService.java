package dev.bre.registry.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.bre.engine.decide.ComponentResult;
import dev.bre.engine.decide.FlowEvaluator;
import dev.bre.engine.eval.EvaluationContext;
import dev.bre.engine.eval.Value;
import dev.bre.engine.ir.Ir;
import dev.bre.facts.FactReader;
import dev.bre.facts.FactsValidator;
import dev.bre.facts.LendingFacts;
import dev.bre.irjson.IrCodec;
import dev.bre.registry.sign.TenantAssets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs a policy version against a fact snapshot, without deploying anything (M3-04).
 *
 * <p><b>Why this exists as its own thing.</b> There were three ways to execute a policy and none of
 * them was the one an author needs:
 *
 * <ul>
 *   <li>{@code POST /v1/decisions} on the decision service runs a <b>deployed</b> policy. An author
 *       editing a draft has nothing deployed, and deploying in order to try something is how a
 *       half-finished rule reaches an environment.
 *   <li>{@code bre decide} runs a <b>signed bundle</b> offline. Signing happens at approval, so
 *       again there is nothing to run until the work is finished.
 *   <li>Test cases run on save, and answer "does it still do what I asserted" — not "what does it
 *       do with <em>this</em> applicant", which is the question being asked while the rule is being
 *       written.
 * </ul>
 *
 * <p>So: run the DRAFT, as saved, against facts supplied in the request. No approval, no signature,
 * no deployment, nothing persisted.
 *
 * <p><b>It is the same engine, the same resolved assets and the same fact typing</b> the decision
 * service uses — {@link FlowEvaluator}, {@link TenantAssets}, {@link FactReader}. That is the whole
 * value: a run here and a decision in production differ in what is <em>recorded</em>, never in what
 * is <em>decided</em>. A simulator that was merely similar would let an author sign off a policy
 * that behaves differently once deployed, which is worse than having no simulator.
 *
 * <p><b>Nothing is written.</b> No decision record, no audit entry for the run itself. A run is a
 * question about a hypothetical, and recording thousands of them while somebody iterates on a
 * threshold would bury the entries that matter — the saves, the submission, the approval. The facts
 * are also not stored: they arrive in a request body and are gone when it returns, which is the
 * correct handling for data §9.7 requires to be synthetic and which nobody has verified is.
 */
@Service
public class PolicyRunService {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final FactReader FACT_READER = new FactReader(LendingFacts.schema());
  private static final FactsValidator VALIDATOR = new FactsValidator(LendingFacts.schema());

  private final PolicyVersionRepository repository;
  private final TenantAssets assets;

  /**
   * Creates the service.
   *
   * @param repository the policy tables
   * @param assets resolves the tenant's parameters and lists
   */
  public PolicyRunService(PolicyVersionRepository repository, TenantAssets assets) {
    this.repository = repository;
    this.assets = assets;
  }

  /**
   * What one component did, for one entity.
   *
   * @param component the rule, table or scorecard code
   * @param entity the member, household or application it ran for
   * @param status the status before coverage
   * @param effectiveStatus the status after an approved deviation was applied
   * @param reasonCode the reason, when it produced one
   * @param measure the measured value a deviation is judged on
   * @param outputs the component's named outputs — a scorecard's score, a rule's declared outputs
   * @param error the runtime error code, when it errored
   */
  public record EntityOutcome(
      String component,
      String entity,
      String status,
      String effectiveStatus,
      String reasonCode,
      String measure,
      Map<String, String> outputs,
      String error) {}

  /**
   * One flow step.
   *
   * @param component the component the step ran
   * @param status the aggregated status
   * @param entities what each entity did
   */
  public record StepOutcome(String component, String status, List<EntityOutcome> entities) {

    /** Creates a step outcome, copying the list. */
    public StepOutcome {
      entities = List.copyOf(entities);
    }
  }

  /**
   * A run.
   *
   * @param policyCode the policy
   * @param version the version run
   * @param status the version's lifecycle state, so a reader knows whether this was a draft
   * @param asOf the business date it was evaluated on
   * @param decision the overall decision
   * @param stpEligible whether it could go straight through
   * @param steps every flow step, in flow order
   * @param customerReasons the reasons an applicant would be given
   * @param internalReasons the reasons an officer would see
   * @param approvals the approvals a deviation needs
   * @param factWarnings schema problems in the supplied facts — paths only, never values
   * @param assetsResolved whether the tenant's parameters and lists were available
   */
  public record Run(
      String policyCode,
      int version,
      String status,
      java.time.LocalDate asOf,
      String decision,
      boolean stpEligible,
      List<StepOutcome> steps,
      List<String> customerReasons,
      List<String> internalReasons,
      List<String> approvals,
      List<String> factWarnings,
      boolean assetsResolved) {

    /** Creates a run, copying every list. */
    public Run {
      steps = List.copyOf(steps);
      customerReasons = List.copyOf(customerReasons);
      internalReasons = List.copyOf(internalReasons);
      approvals = List.copyOf(approvals);
      factWarnings = List.copyOf(factWarnings);
    }
  }

  /**
   * Runs a version against facts.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version — any state, including DRAFT; that is the point
   * @param facts the fact snapshot, as decoded JSON
   * @param asOf the business date; never defaulted to today silently, because the same facts on two
   *     dates are two different decisions and an author has to be able to say which they meant
   * @return what happened
   * @throws PolicyVersionService.LifecycleException if the version does not exist or has never been
   *     saved
   */
  @Transactional(readOnly = true)
  public Run run(
      String tenantId,
      String policyCode,
      int version,
      Map<String, Object> facts,
      java.time.LocalDate asOf) {

    PolicyVersionRepository.Row row =
        repository
            .find(tenantId, policyCode, version)
            .orElseThrow(
                () ->
                    new PolicyVersionService.LifecycleException(
                        "no version " + version + " of " + policyCode + " for this tenant."));

    ObjectNode ir = repository.irOf(tenantId, policyCode, version);
    if (ir == null || ir.isEmpty()) {
      throw new PolicyVersionService.LifecycleException(
          "version "
              + version
              + " of "
              + policyCode
              + " has never been saved, so there is nothing to run. Save the policy source first.");
    }

    // The facts are checked against the schema and the findings are REPORTED, not enforced. An
    // author trying a half-written snapshot should see the decision AND be told that
    // `applicaton.requestedAmount` is a typo — refusing the run would make them fix the typo
    // before learning anything, and a mistyped path reads as a missing fact, which the engine
    // handles correctly and silently.
    List<String> factWarnings = new ArrayList<>();
    try {
      VALIDATOR
          .validate(JSON.valueToTree(facts))
          .forEach(finding -> factWarnings.add(finding.toString()));
    } catch (RuntimeException notValidatable) {
      factWarnings.add(
          "the facts could not be checked against the schema: " + notValidatable.getMessage());
    }

    TenantAssets.Resolved resolved = assets.resolve(tenantId, ir.get("assets"));
    resolved
        .authorityMatrix()
        .ifPresent(matrix -> ((ObjectNode) ir.get("assets")).set("authorityMatrix", matrix));

    Ir.PolicyIr policy = IrCodec.readPolicy(ir);
    Value.RecordV factRecord = FACT_READER.toRecord(facts);

    // ponytail: no per-expression trace. M1-11's recorder exists and produces one tree per
    // EXPRESSION, but FlowEvaluator does not collect those per component and ComponentResult does
    // not carry one — so returning "the trace" here would mean returning whichever expression the
    // recorder happened to hold last, which is worse than returning none. Wiring it through the
    // flow is the missing piece, and it belongs in the engine rather than here.
    //
    // What the step outcomes below DO carry is the measure and the named outputs per entity, which
    // is the level an author iterating on a threshold actually asks about: not "how was 0.47
    // computed" but "which member deviated, on what number, against which cap".
    EvaluationContext context =
        EvaluationContext.of(factRecord, resolved.parameters(), resolved.lists(), asOf);

    FlowEvaluator.DecisionResult result = FlowEvaluator.evaluate(policy, context);

    List<StepOutcome> steps = new ArrayList<>();
    for (FlowEvaluator.StepResult step : result.steps()) {
      List<EntityOutcome> entities = new ArrayList<>();
      for (ComponentResult entity : step.entities()) {
        Map<String, String> outputs = new java.util.LinkedHashMap<>();
        entity.outputs().forEach((name, value) -> outputs.put(name, render(value)));
        entities.add(
            new EntityOutcome(
                entity.code(),
                entity.entityRef().orElse(null),
                entity.status().name(),
                entity.effectiveStatus().name(),
                entity.reasonCode().orElse(null),
                entity.measure().map(PolicyRunService::render).orElse(null),
                outputs,
                entity.error().orElse(null)));
      }
      steps.add(new StepOutcome(step.ref(), step.status().name(), entities));
    }

    List<String> approvals = new ArrayList<>();
    result
        .requiredApprovals()
        .forEach(
            item ->
                item.deviations()
                    .forEach(
                        deviation ->
                            approvals.add(
                                item.level()
                                    + ": "
                                    + deviation.deviationType()
                                    + " on "
                                    + deviation.ruleCode()
                                    + deviation.entityRef().map(r -> " (" + r + ")").orElse(""))));

    return new Run(
        policyCode,
        version,
        row.status().name(),
        asOf,
        result.decision().name(),
        result.stpEligible(),
        steps,
        result.customerReasons().stream().map(reason -> reason.code()).toList(),
        result.internalReasons(),
        approvals,
        factWarnings,
        resolved.present());
  }

  private static String render(Value value) {
    return switch (value) {
      case Value.Missing ignored -> "<missing>";
      case Value.DecimalV decimal -> decimal.value().stripTrailingZeros().toPlainString();
      case Value.TextV text -> text.value();
      case Value.BoolV bool -> String.valueOf(bool.value());
      case Value.IntV integer -> String.valueOf(integer.value());
      default -> String.valueOf(value);
    };
  }

  /** For a caller that wants the version's source to show beside the result. */
  public Optional<String> sourceOf(String tenantId, String policyCode, int version) {
    return repository.sourceOf(tenantId, policyCode, version);
  }
}
