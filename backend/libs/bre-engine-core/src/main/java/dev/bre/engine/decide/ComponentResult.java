package dev.bre.engine.decide;

import dev.bre.engine.eval.Value;
import dev.bre.engine.ir.Ir.RuntimeStatus;
import dev.bre.engine.ir.Ir.WorseWhen;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What one component produced for one entity (LLD §4.6, §4.9).
 *
 * <p><b>{@code status} and {@code effectiveStatus} are both kept, and that is the point.</b> When
 * an approval covers a deviation, §4.9 makes the effective status PASS — but the raw DEVIATION
 * stays on the record, alongside the measure, the deviation type and the approval reference. An
 * auditor asking "was anything approved around here, and by what authority?" needs the deviation to
 * still be visible; collapsing it to a PASS would erase exactly the thing that has to be
 * answerable.
 *
 * @param code the component's code
 * @param status the raw runtime status, before coverage
 * @param effectiveStatus the status after coverage; equal to {@code status} unless an approval
 *     covered a deviation
 * @param entityType the scope of the entity this result is for, e.g. {@code MEMBER}
 * @param entityRef the entity's reference, e.g. a party ref
 * @param reasonCode the reason, when the component did not pass
 * @param deviationType the deviation type, for a DEVIATION
 * @param measure the measured value, for coverage matching
 * @param worseWhen which direction is worse for that measure
 * @param requiredLevel the authority level an uncovered deviation needs
 * @param annotation {@code DEVIATION_APPROVED} when an approval covered this deviation
 * @param approvalRef the covering approval's reference
 * @param outputs the component's named outputs
 * @param error the runtime error code, when the status is ERROR
 * @param adverseReasons the adverse reasons, for a scorecard
 */
public record ComponentResult(
    String code,
    RuntimeStatus status,
    RuntimeStatus effectiveStatus,
    Optional<String> entityType,
    Optional<String> entityRef,
    Optional<String> reasonCode,
    Optional<String> deviationType,
    Optional<Value> measure,
    Optional<WorseWhen> worseWhen,
    Optional<String> requiredLevel,
    Optional<String> annotation,
    Optional<String> approvalRef,
    Map<String, Value> outputs,
    Optional<String> error,
    List<String> adverseReasons) {

  /** Creates a result, copying the collections. */
  public ComponentResult {
    outputs = Map.copyOf(outputs);
    adverseReasons = List.copyOf(adverseReasons);
  }

  /**
   * A result carrying a status, a reason and the component's outputs.
   *
   * @param code the component's code
   * @param status the status
   * @param reasonCode the reason, or null
   * @param deviationType the deviation type, or null
   * @param measure the measured value, or null
   * @param worseWhen the worse direction, or null
   * @param outputs the named outputs
   * @return the result
   */
  public static ComponentResult of(
      String code,
      RuntimeStatus status,
      String reasonCode,
      String deviationType,
      Value measure,
      WorseWhen worseWhen,
      Map<String, Value> outputs) {
    return new ComponentResult(
        code,
        status,
        status,
        Optional.empty(),
        Optional.empty(),
        Optional.ofNullable(reasonCode),
        Optional.ofNullable(deviationType),
        Optional.ofNullable(measure),
        Optional.ofNullable(worseWhen),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        outputs,
        Optional.empty(),
        List.of());
  }

  /**
   * A result carrying only a status.
   *
   * @param code the component's code
   * @param status the status
   * @return the result
   */
  public static ComponentResult of(String code, RuntimeStatus status) {
    return of(code, status, null, null, null, null, Map.of());
  }

  /**
   * A result carrying a status and a reason.
   *
   * @param code the component's code
   * @param status the status
   * @param reasonCode the reason, or null
   * @return the result
   */
  public static ComponentResult of(String code, RuntimeStatus status, String reasonCode) {
    return of(code, status, reasonCode, null, null, null, Map.of());
  }

  /**
   * An error result.
   *
   * @param code the component's code
   * @param errorCode the runtime error code (§3.11)
   * @return the result
   */
  public static ComponentResult error(String code, String errorCode) {
    return new ComponentResult(
        code,
        RuntimeStatus.ERROR,
        RuntimeStatus.ERROR,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Map.of(),
        Optional.of(errorCode),
        List.of());
  }

  /**
   * Returns a copy identified as belonging to one entity.
   *
   * @param type the entity's scope
   * @param ref the entity's reference, or null for an unidentified entity
   * @return the derived result
   */
  public ComponentResult forEntity(String type, String ref) {
    return new ComponentResult(
        code,
        status,
        effectiveStatus,
        Optional.ofNullable(type),
        Optional.ofNullable(ref),
        reasonCode,
        deviationType,
        measure,
        worseWhen,
        requiredLevel,
        annotation,
        approvalRef,
        outputs,
        error,
        adverseReasons);
  }

  /**
   * Returns a copy whose raw and effective status are both the given one.
   *
   * @param newStatus the status
   * @return the derived result
   */
  public ComponentResult withStatus(RuntimeStatus newStatus) {
    return new ComponentResult(
        code,
        newStatus,
        newStatus,
        entityType,
        entityRef,
        reasonCode,
        deviationType,
        measure,
        worseWhen,
        requiredLevel,
        annotation,
        approvalRef,
        outputs,
        error,
        adverseReasons);
  }

  /**
   * Returns a copy with a different reason code.
   *
   * @param newReasonCode the reason
   * @return the derived result
   */
  public ComponentResult withReasonCode(String newReasonCode) {
    return new ComponentResult(
        code,
        status,
        effectiveStatus,
        entityType,
        entityRef,
        Optional.ofNullable(newReasonCode),
        deviationType,
        measure,
        worseWhen,
        requiredLevel,
        annotation,
        approvalRef,
        outputs,
        error,
        adverseReasons);
  }

  /**
   * Returns a copy marked as covered by an approval: effectively PASS, while the raw DEVIATION, the
   * measure and the deviation type stay on the record (§4.9, S8-01).
   *
   * @param newAnnotation the annotation, {@code DEVIATION_APPROVED}
   * @param newApprovalRef the covering approval's reference
   * @return the derived result
   */
  public ComponentResult approved(String newAnnotation, String newApprovalRef) {
    return new ComponentResult(
        code,
        status,
        RuntimeStatus.PASS,
        entityType,
        entityRef,
        reasonCode,
        deviationType,
        measure,
        worseWhen,
        Optional.empty(),
        Optional.ofNullable(newAnnotation),
        Optional.ofNullable(newApprovalRef),
        outputs,
        error,
        adverseReasons);
  }

  /**
   * Returns a copy whose EFFECTIVE status differs from its raw one, keeping the raw status on the
   * record.
   *
   * <p>Used where §4.9 changes what a deviation means without changing what the policy found: no
   * authority band governs it, or the authority amount itself is missing. The entity still shows
   * {@code status: DEVIATION} so an auditor can see the finding (P-04c).
   *
   * @param newEffective the effective status
   * @param newReasonCode the reason
   * @return the derived result
   */
  public ComponentResult withEffectiveStatus(RuntimeStatus newEffective, String newReasonCode) {
    return new ComponentResult(
        code,
        status,
        newEffective,
        entityType,
        entityRef,
        Optional.ofNullable(newReasonCode),
        deviationType,
        measure,
        worseWhen,
        requiredLevel,
        annotation,
        approvalRef,
        outputs,
        error,
        adverseReasons);
  }

  /**
   * Returns a copy marked as needing an approval it does not have (§4.9).
   *
   * @param level the authority level required
   * @return the derived result
   */
  public ComponentResult requiring(String level) {
    return new ComponentResult(
        code,
        status,
        status,
        entityType,
        entityRef,
        reasonCode,
        deviationType,
        measure,
        worseWhen,
        Optional.ofNullable(level),
        Optional.empty(),
        Optional.empty(),
        outputs,
        error,
        adverseReasons);
  }
}
