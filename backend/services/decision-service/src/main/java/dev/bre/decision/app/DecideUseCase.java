package dev.bre.decision.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bre.common.UuidV7;
import dev.bre.decision.api.DecisionApi.DecisionRequest;
import dev.bre.decision.api.DecisionApi.DecisionResponse;
import dev.bre.decision.api.DecisionApi.RuleOutcome;
import dev.bre.decision.api.DecisionApi.RulePayload;
import dev.bre.decision.log.DecisionLog;
import dev.bre.decision.log.DecisionRecord;
import dev.bre.engine.decide.Outcomes.OnMissing;
import dev.bre.engine.decide.PolicyEvaluator;
import dev.bre.engine.decide.RuleDef;
import dev.bre.engine.eval.EvaluationContext;
import dev.bre.engine.eval.Value;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
   * The fact schema the snapshot is assumed to conform to.
   *
   * <p>A placeholder until the canonical schema lands with {@code bre-fact-model} (M1-14). Recorded
   * rather than omitted because a replay has to know which schema the stored facts were written
   * against; a record that does not say is a record that cannot be replayed with confidence.
   */
  private static final String FACT_SCHEMA = "lending-facts@unversioned";

  private final DecisionLog decisionLog;
  private final ObjectMapper objectMapper;
  private final Clock clock;

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
  public DecideUseCase(DecisionLog decisionLog, ObjectMapper objectMapper, Clock clock) {
    this.decisionLog = decisionLog;
    this.objectMapper = objectMapper;
    this.clock = clock;
    log.info("decision log: {}", decisionLog.describe());
  }

  /**
   * Evaluates a request and records the decision.
   *
   * @param request the decision request
   * @return the decision
   */
  public DecisionResponse decide(DecisionRequest request) {
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

    PolicyEvaluator.PolicyResult result =
        PolicyEvaluator.evaluate(
            toRules(request.policy().rules()),
            EvaluationContext.of(FactMapper.toRecord(request.facts()), Map.of(), Map.of(), asOf));

    DecisionResponse response =
        new DecisionResponse(
            // UUIDv7, not v4 (ADR-008): the id carries its own creation time, which the hot
            // store derives decision_date from, so a lookup by id prunes to one day partition
            // (§5.3). Minted from the instant already resolved above rather than reading the
            // clock again, so the id and the record agree about when the decision happened.
            UuidV7.generate(receivedAt).toString(),
            request.tenantId(),
            request.requestId(),
            asOf,
            result.decision().name(),
            result.stpEligible(),
            toOutcomes(result.results()));

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
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
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

    return response;
  }

  /**
   * Looks a decision up.
   *
   * @param tenantId the tenant
   * @param decisionId the decision identifier
   * @return the decision, or empty
   */
  public Optional<DecisionResponse> find(String tenantId, String decisionId) {
    return decisionLog.find(tenantId, decisionId).map(DecisionRecord::response);
  }

  private static List<RuleDef> toRules(List<RulePayload> payloads) {
    List<RuleDef> rules = new ArrayList<>(payloads.size());
    for (RulePayload payload : payloads) {
      rules.add(
          new RuleDef(
              payload.code(),
              payload.check(),
              Optional.ofNullable(payload.when()),
              onMissing(payload.onMissing()),
              Optional.ofNullable(payload.reasonCode())));
    }
    return rules;
  }

  private static OnMissing onMissing(String name) {
    if (name == null || name.isBlank()) {
      return OnMissing.DATA_MISSING; // the IR's default
    }
    try {
      return OnMissing.valueOf(name.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      throw new IllegalArgumentException(
          "onMissing must be one of DATA_MISSING, FAIL or NOT_APPLICABLE, not '" + name + "'");
    }
  }

  private static List<RuleOutcome> toOutcomes(List<PolicyEvaluator.RuleResult> results) {
    List<RuleOutcome> outcomes = new ArrayList<>(results.size());
    for (PolicyEvaluator.RuleResult result : results) {
      outcomes.add(
          new RuleOutcome(
              result.code(),
              result.status().name(),
              result.reasonCode().orElse(null),
              result.error().orElse(null)));
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

  /** Converts request facts to engine values. Separate class so it can be unit-tested alone. */
  static final class FactMapper {
    private FactMapper() {}

    static Value.RecordV toRecord(Map<String, Object> facts) {
      return dev.bre.decision.app.JsonFactReader.toRecord(facts);
    }
  }
}
