package dev.bre.engine.eval;

/**
 * Runtime evaluation error codes (LLD §3.11).
 *
 * <p>These are reported <b>on the result</b>, not as an HTTP error. Every one produces {@code
 * status = ERROR} and {@code reasonCode = SYS_ERROR}, and raises a system alert (00 §3.5). Because
 * {@code ERROR} can never produce {@code APPROVE} (D9), an error is a fail-closed outcome.
 */
public enum EvalErrorCode {
  /** Division by zero. Note this is an ERROR, never missing (§3.6, D-07). */
  EVAL_DIV_ZERO,
  /** Integer overflow, or {@code floor}/{@code ceil} out of range. */
  EVAL_OVERFLOW,
  /** Fuel limit reached (§3.10). */
  EVAL_FUEL_EXHAUSTED,
  /** A list over the cap. */
  EVAL_LIST_TOO_LARGE,
  /** Input to {@code matches}, or another text function, over 10,000 characters. */
  EVAL_TEXT_TOO_LONG,
  /** Inputs present but no row matched — a gap. */
  EVAL_TABLE_NO_MATCH,
  /** More than one row matched under {@code UNIQUE}. */
  EVAL_TABLE_UNIQUE_VIOLATION,
  /** A characteristic value is present but no bin matched. */
  EVAL_SCORECARD_NO_BIN,
  /** No value of a referenced parameter is effective at {@code asOf}. */
  EVAL_PARAM_NOT_EFFECTIVE,
  /** A referenced component errored for that entity. */
  EVAL_REFERENCE_ERROR,
  /** An engine defect. Raises a P1 alert. */
  EVAL_INTERNAL
}
