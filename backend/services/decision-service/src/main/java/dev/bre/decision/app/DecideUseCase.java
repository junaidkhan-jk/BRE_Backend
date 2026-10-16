package dev.bre.decision.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bre.common.UuidV7;
import dev.bre.decision.api.DecisionApi.DecisionRequest;
import dev.bre.decision.api.DecisionApi.DecisionResponse;
import dev.bre.decision.api.DecisionApi.RuleOutcome;
import dev.bre.decision.log.DecisionLog;
import dev.bre.decision.log.DecisionRecord;
import dev.bre.decision.policy.PolicyResolution;
import dev.bre.decision.policy.PolicyResolver;
import dev.bre.engine.decide.FlowEvaluator;
import dev.bre.engine.eval.EvaluationContext;
import dev.bre.facts.FactReader;
import dev.bre.facts.LendingFacts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Decides one request, then records it durably before returning (D6).
 *
 * <p>This is the service layer, and it is where the clock lives. The engine is a pure function of
 * {@code (facts, policy, asOf)} and reads no clock at all (D8); when a caller omits {@code asOf},
 * resolving it to a date is a decision made <em>here</em>, once, and stored on the record. That
 * separation is what makes a replay exact: the stored {@code asOf} is replayed, not re-derived.
 *
 * <p>Default tenant time zone is {@code Asia/Kolkata} (00 §3.7a). All data stays in India (D13).
 */
@Service
public class DecideUseCase {

  private static final Logger log = LoggerFactory.getLogger(DecideUseCase.class);

  /** The tenant time zone recorded with every decision (00 §3.7a). */
  private static final ZoneId TENANT_ZONE = ZoneId.of("Asia/Kolkata");

  /**
   * The fact schema the snapshot conforms to.
   *
   * <p>Taken from the schema itself rather than written as a literal. A replay has to know which
   * schema the stored facts were written against — a record that does not say cannot be replayed
   * with confidence — and a literal here would keep saying 1.0.0 after the schema moved on.
   */
  private static final String FACT_SCHEMA = LendingFacts.CODE + "@" + LendingFacts.VERSION;

  private final PolicyResolver policyResolver;
  private final DecisionLog decisionLog;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final dev.bre.decision.observe.DecisionMetrics metrics;
  private final dev.bre.decision.admit.FairShareBulkhead bulkhead;

  /**
   * Creates the use case.
   *
   * <p><b>Exactly one constructor, on purpose.</b> Spring auto-selects a constructor only when
   * there is a single one; add a convenience overload and startup fails with {@code
   * NoSuchMethodException: DecideUseCase.<init>()} as it falls back to looking for a no-arg
   * constructor. The clock is therefore injected as a bean rather than defaulted here, which also
   * lets a test pin "today" without a second constructor.
   *
   * @param decisionLog the durable log the response waits on
   * @param objectMapper used to store the fact snapshot verbatim
   * @param clock resolves an omitted {@code asOf}; see {@link DecisionClockConfiguration}
   */
  public DecideUseCase(
      PolicyResolver policyResolver,
      DecisionLog decisionLog,
      ObjectMapper objectMapper,
      Clock clock,
      dev.bre.decision.observe.DecisionMetrics metrics,
      dev.bre.decision.admit.FairShareBulkhead bulkhead) {
    this.policyResolver = policyResolver;
    this.decisionLog = decisionLog;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.metrics = metrics;
    this.bulkhead = bulkhead;
    bulkhead.logSizing();
    log.info("decision log: {}", decisionLog.describe());
  }

  /**
   * Evaluates a request and records the decision.
   *
   * @param request the decision request
   * @return the decision
   */
  public DecisionResponse decide(DecisionRequest request) {
    return decide(request, null);
  }

  /**
   * Evaluates a request, using a decision id that was minted earlier.
   *
   * <p><b>For the async path, where the id has to exist before the decision does.</b> {@code POST
   * /v1/decisions:async} answers 202 with a {@code decisionId} the caller will later GET, so that
   * id is minted at accept time and travels on the event. Letting the worker mint its own would
   * hand the caller an identifier for a decision that is never created under it.
   *
   * @param request the decision request
   * @param presetDecisionId the identifier to record it under, or null to mint one
   * @return the decision
   */
  public DecisionResponse decide(DecisionRequest request, String presetDecisionId) {
    // Idempotency (D5). Evaluation is deterministic, so a duplicate is harmless — but returning
    // the original record means a retrying caller sees one decision, not two with different ids.
    Optional<DecisionRecord> existing =
        decisionLog.findByRequestId(request.tenantId(), request.requestId());
    if (existing.isPresent()) {
      log.info(
          "returning the existing decision for tenant={} requestId={}",
          request.tenantId(),
          request.requestId());
      return existing.get().response();
    }

    Instant receivedAt = clock.instant();
    LocalDate asOf = request.asOf() != null ? request.asOf() : LocalDate.now(clock);

    // The policy is RESOLVED, never supplied. The caller names a policy code and may pin a
    // bundle hash; what is actually evaluated is a signed, content-addressed bundle this pod
    // fetched and verified (§8.1, §8.3, D4/D5). That is what makes the decision replayable: the
    // record below carries the bundleHash, so the exact rules can be fetched again years later.
    PolicyResolution resolved =
        policyResolver.resolve(
            request.tenantId(),
            request.environment(),
            request.policyCode(),
            // The application reference fixes the challenger bucket (§4.11). When the caller does
            // not supply one, the request id stands in: it is stable across a retry, which is the
            // property that matters — an applicant must not be re-bucketed by retrying.
            applicationRefOf(request).orElse(request.requestId()),
            Optional.ofNullable(request.bundleHash()).filter(h -> !h.isBlank()),
            receivedAt);

    // Inside the CPU bulkhead (§6.3). Evaluation is the CPU-bound part; everything around it —
    // the idempotency lookup above and the durable write below — is I/O and runs outside, on the
    // virtual thread, so a slow database does not consume an evaluation slot.
    FlowEvaluator.DecisionResult result =
        bulkhead.call(
            request.tenantId(),
            () ->
                FlowEvaluator.evaluate(
                    resolved.policy(),
                    EvaluationContext.of(
                        FACT_READER.toRecord(request.facts()),
                        // The parameters and lists the bundle was SIGNED with (§8.4).
                        resolved.parameters(),
                        resolved.lists(),
                        asOf)));

    DecisionResponse response =
        new DecisionResponse(
            // UUIDv7, not v4 (ADR-008): the id carries its own creation time, which the hot
            // store derives decision_date from, so a lookup by id prunes to one day partition
            // (§5.3). Minted from the instant already resolved above rather than reading the
            // clock again, so the id and the record agree about when the decision happened.
            presetDecisionId != null ? presetDecisionId : UuidV7.generate(receivedAt).toString(),
            request.tenantId(),
            request.requestId(),
            asOf,
            result.decision().name(),
            result.stpEligible(),
            toOutcomes(result.steps()));

    // Durable BEFORE the response returns (D6).
    Instant completedAt = clock.instant();
    decisionLog.append(
        new DecisionRecord(
            response.decisionId(),
            request.tenantId(),
            request.requestId(),
            applicationRefOf(request),
            receivedAt,
            completedAt,
            Duration.between(receivedAt, completedAt).toNanos() / 1_000,
            TENANT_ZONE.getId(),
            0L,
            Optional.of(request.policyCode()),
            // Still empty: Ir.PolicyIr does not carry the version, only the code — the IR's
            // `policy` node has it and IrCodec drops it. Left empty rather than defaulted to 1,
            // because a wrong version on an audit record is worse than an absent one. The
            // bundleHash below identifies the policy exactly in the meantime.
            Optional.empty(),
            // The bundle hash the decision was ACTUALLY taken against — not the policy code, which
            // names a moving target. These three were Optional.empty() placeholders, which meant
            // the record could not say which rules decided it and no decision could be replayed at
            // all: ReplayService refuses a record with no bundleHash, correctly, and that refusal
            // is what surfaced this.
            Optional.of(resolved.bundleHash()),
            FACT_SCHEMA,
            response,
            writeFacts(request.facts())));

    // No fact values in the log line (LLD §1.2 rule 2, §9.6) — the decision and the ids only.
    log.info(
        "decided tenant={} requestId={} decisionId={} decision={} stpEligible={}",
        request.tenantId(),
        request.requestId(),
        response.decisionId(),
        response.decision(),
        response.stpEligible());

    // Recorded AFTER the durable write, so the latency histogram measures what the caller
    // actually waited for — which is what NFR-PERF-01's p99 budget is about. Timing only the
    // evaluation would produce a number that is always inside budget and never the one a caller
    // experiences.
    metrics.decided(
        request.tenantId(),
        request.environment(),
        response.decision(),
        (int) response.results().stream().filter(outcome -> outcome.error() != null).count(),
        Duration.between(receivedAt, completedAt));

    return response;
  }

  /**
   * Returns a decision by id.
   *
   * @param tenantId the tenant, which scopes the lookup — a decision id is only unique within one
   * @param decisionId the decision
   * @return the decision, or empty when this tenant has no such decision
   */
  public Optional<DecisionResponse> find(String tenantId, String decisionId) {
    return decisionLog.find(tenantId, decisionId).map(DecisionRecord::response);
  }

  /**
   * One outcome per flow step.
   *
   * <p>The step's AGGREGATED status, not a per-entity one: a MEMBER-scope component runs once per
   * group member, and a response that listed forty outcomes for one rule would be reporting the
   * engine's internals rather than the decision. The per-entity detail belongs in the explanation
   * (§6.4), which is M2-05.
   */
  private static List<RuleOutcome> toOutcomes(List<FlowEvaluator.StepResult> steps) {
    List<RuleOutcome> outcomes = new ArrayList<>(steps.size());
    for (FlowEvaluator.StepResult step : steps) {
      String reasonCode =
          step.entities().stream()
              .filter(e -> e.reasonCode().isPresent())
              .findFirst()
              .flatMap(dev.bre.engine.decide.ComponentResult::reasonCode)
              .orElse(null);
      String error =
          step.entities().stream()
              .filter(e -> e.error().isPresent())
              .findFirst()
              .flatMap(dev.bre.engine.decide.ComponentResult::error)
              .orElse(null);
      outcomes.add(new RuleOutcome(step.ref(), step.status().name(), reasonCode, error));
    }
    return outcomes;
  }

  /** The application reference, for the hot store's by-application index (§5.3). */
  private static Optional<String> applicationRefOf(DecisionRequest request) {
    Object application = request.facts().get("application");
    if (application instanceof Map<?, ?> fields) {
      Object ref = fields.get("applicationRef");
      if (ref != null) {
        return Optional.of(String.valueOf(ref));
      }
    }
    return Optional.empty();
  }

  private String writeFacts(Map<String, Object> facts) {
    try {
      return objectMapper.writeValueAsString(facts);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // Do not put the facts in the message: that is the PII leak §9.6 forbids.
      throw new IllegalStateException("the fact snapshot could not be serialised for the record");
    }
  }

  /**
   * Converts request facts to engine values, through the fact schema (§3.4).
   *
   * <p>One reader, built once. {@link FactReader} is immutable and holds only the schema, and the
   * schema is a few hundred paths parsed from a resource — building one per request would parse
   * nothing again but would still allocate per decision for no reason.
   */
  private static final FactReader FACT_READER = new FactReader(LendingFacts.schema());
}
