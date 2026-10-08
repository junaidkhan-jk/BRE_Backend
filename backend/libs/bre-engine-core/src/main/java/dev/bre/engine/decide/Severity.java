package dev.bre.engine.decide;

import dev.bre.engine.ir.Ir.RuntimeStatus;

/**
 * How severe each runtime status is, for WORST aggregation and PRIORITY tables (LLD §4.7).
 *
 * <p>The order is taken from the conformance vectors rather than from intuition, because two of the
 * steps are not what you would guess:
 *
 * <ul>
 *   <li><b>{@code DATA_MISSING} is worse than {@code DEVIATION}</b> — S6-01a has {@code [PASS,
 *       DATA_MISSING, DEVIATION]} aggregate to {@code DATA_MISSING}. A gap outranks an approvable
 *       tolerance, because a tolerance is a known quantity and a gap is not.
 *   <li><b>{@code FAIL} is worse than {@code ERROR}</b> — S6-01b has {@code [ERROR, FAIL]}
 *       aggregate to {@code FAIL}. An engine fault refers; a policy breach declines, and the breach
 *       is the more definite answer.
 * </ul>
 *
 * <p>Both are fail-closed either way: §4.8 sends {@code ERROR}, {@code DATA_MISSING} and {@code
 * REFER} to REFER and {@code FAIL} to DECLINE, so none of them can reach APPROVE.
 */
final class Severity {

  private Severity() {}

  /**
   * Returns the severity rank of a status; higher is worse.
   *
   * @param status the status
   * @return its rank
   */
  static int of(RuntimeStatus status) {
    return switch (status) {
      case NOT_APPLICABLE -> 0;
      case PASS -> 1;
      case DEVIATION -> 2;
      case REFER -> 3;
      case DATA_MISSING -> 4;
      case ERROR -> 5;
      case FAIL -> 6;
    };
  }

  /**
   * Returns the more severe of two statuses.
   *
   * @param left one status
   * @param right the other
   * @return whichever ranks higher
   */
  static RuntimeStatus worst(RuntimeStatus left, RuntimeStatus right) {
    return of(right) > of(left) ? right : left;
  }
}
