package dev.bre.decision.log;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.bre.decision.api.DecisionApi.DecisionResponse;
import java.time.Instant;

/**
 * The payload of {@code bre.decision.recorded.v1} (M4-01, 00 §3.9).
 *
 * <p><b>An explicit wire type, not the internal record serialised.</b> {@link DecisionRecord} is
 * shaped for the code that uses it — {@code Optional} fields, an {@code Instant}, a facts blob —
 * and it will keep changing. This topic is the system of record in Kafka-first mode and is read by
 * the sink, the shadow evaluator and whatever a lender attaches to it, so its shape is a published
 * contract and belongs in a type somebody has to edit deliberately.
 *
 * <p><b>Nullable fields rather than {@code Optional}.</b> This is a JSON contract other systems
 * read, and {@code Optional} needs a Jackson module to serialise as anything but an object with an
 * {@code empty} flag — an implementation detail of this codebase leaking into a published payload.
 * An absent value is simply an absent key, which is what every consumer already expects.
 *
 * <p><b>The facts are not here, and that is a boundary rather than an omission.</b> A decision's
 * facts are the applicant's personal data. Putting them on a topic with a week's retention, read by
 * every consumer with access to it, is the opposite of §9.6 — so the fact snapshot stays in the
 * store that can encrypt it per principal and shred a key (§9.2, §9.5). Kafka-first mode therefore
 * refuses {@code store-facts} until that exists; see {@code KafkaDecisionLog}.
 *
 * @param decisionId the UUIDv7 identifier
 * @param tenantId the tenant
 * @param requestId the caller's idempotency key
 * @param applicationRef the application, or null when the caller sent none
 * @param receivedAt when the request arrived
 * @param recordedAt when the decision was taken
 * @param latencyMicros how long it took, server-side
 * @param timeZone the tenant's zone, which decided the business date
 * @param fuelUsed the engine's fuel counter, for the §4.4 budget
 * @param policyCode which policy decided it, or null
 * @param policyVersion which version, or null
 * @param bundleHash the exact signed bundle so a replay can load the same bytes, or null
 * @param factSchema the fact-schema version the request was validated against
 * @param response the decision itself
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
public record DecisionEvent(
    String decisionId,
    String tenantId,
    String requestId,
    String applicationRef,
    Instant receivedAt,
    Instant recordedAt,
    long latencyMicros,
    String timeZone,
    long fuelUsed,
    String policyCode,
    Integer policyVersion,
    String bundleHash,
    String factSchema,
    DecisionResponse response) {

  /** The topic. Versioned in the name, because a breaking change is a new topic (00 §3.9). */
  public static final String TOPIC = "bre.decision.recorded.v1";

  /**
   * The partition key: {@code tenantId:applicationRef} (00 §3.9).
   *
   * <p>Not the decision id, which would spread one application's decisions across partitions and
   * lose their order. A consumer that saw an APPROVE after the DECLINE that superseded it would act
   * on a decision the lender had already reversed, so per-application ordering is the guarantee
   * this key exists to give.
   *
   * @return the key
   */
  public String partitionKey() {
    return tenantId + ":" + (applicationRef == null ? requestId : applicationRef);
  }

  /**
   * Builds the event from an internal record, dropping the facts.
   *
   * @param record the decision as the engine produced it
   * @return the event
   */
  public static DecisionEvent from(DecisionRecord record) {
    return new DecisionEvent(
        record.decisionId(),
        record.tenantId(),
        record.requestId(),
        record.applicationRef().orElse(null),
        record.receivedAt(),
        record.recordedAt(),
        record.latencyMicros(),
        record.timeZone(),
        record.fuelUsed(),
        record.policyCode().orElse(null),
        record.policyVersion().orElse(null),
        record.bundleHash().orElse(null),
        record.factSchema(),
        record.response());
  }

  /**
   * Rebuilds the internal record, for the sink to write.
   *
   * <p>{@code factsJson} comes back empty — see the class comment. A row written by the sink
   * therefore has no fact snapshot, which is the same state a compact-mode row has with {@code
   * store-facts} off, and the replay path already treats that as "not stored" rather than as an
   * error.
   *
   * @return the record
   */
  public DecisionRecord toRecord() {
    return new DecisionRecord(
        decisionId,
        tenantId,
        requestId,
        java.util.Optional.ofNullable(applicationRef),
        receivedAt,
        recordedAt,
        latencyMicros,
        timeZone,
        fuelUsed,
        java.util.Optional.ofNullable(policyCode),
        java.util.Optional.ofNullable(policyVersion),
        java.util.Optional.ofNullable(bundleHash),
        factSchema,
        response,
        "");
  }
}
