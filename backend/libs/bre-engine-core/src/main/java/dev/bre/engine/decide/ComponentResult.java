package dev.bre.engine.decide;

import dev.bre.engine.eval.Value;
import dev.bre.engine.ir.Ir.RuntimeStatus;
import dev.bre.engine.ir.Ir.WorseWhen;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What one component produced for one entity (LLD §4.6).
 *
 * @param code the component's code
 * @param status the runtime status
 * @param reasonCode the reason, when the component did not pass
 * @param deviationType the deviation type, for a DEVIATION
 * @param measure the measured value, for deviation coverage matching (§4.9)
 * @param worseWhen which direction is worse for that measure
 * @param outputs the component's named outputs
 * @param error the runtime error code, when the status is ERROR
 * @param adverseReasons the adverse reasons, for a scorecard (S11)
 */
public record ComponentResult(
    String code,
    RuntimeStatus status,
    Optional<String> reasonCode,
    Optional<String> deviationType,
    Optional<Value> measure,
    Optional<WorseWhen> worseWhen,
    Map<String, Value> outputs,
    Optional<String> error,
    List<String> adverseReasons) {

  /** Creates a result, copying the collections. */
  public ComponentResult {
    outputs = Map.copyOf(outputs);
    adverseReasons = List.copyOf(adverseReasons);
  }

  /**
   * A result carrying only a status.
   *
   * @param code the component's code
   * @param status the status
   * @return the result
   */
  public static ComponentResult of(String code, RuntimeStatus status) {
    return new ComponentResult(
        code,
        status,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Map.of(),
        Optional.empty(),
        List.of());
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
    return new ComponentResult(
        code,
        status,
        Optional.ofNullable(reasonCode),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Map.of(),
        Optional.empty(),
        List.of());
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
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Map.of(),
        Optional.of(errorCode),
        List.of());
  }

  /**
   * Returns a copy of this result with a different status, keeping everything else.
   *
   * <p>Used by deviation coverage, which turns a DEVIATION into an effective PASS while keeping the
   * measure and the deviation type on the record (§4.9, S8-01).
   *
   * @param newStatus the status to carry
   * @return the derived result
   */
  public ComponentResult withStatus(RuntimeStatus newStatus) {
    return new ComponentResult(
        code,
        newStatus,
        reasonCode,
        deviationType,
        measure,
        worseWhen,
        outputs,
        error,
        adverseReasons);
  }
}
