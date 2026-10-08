package dev.bre.engine.eval;

/**
 * The evaluation fuel budget (LLD §3.10).
 *
 * <p>One meter per decision, shared across every component. Every AST node costs one unit, a
 * projection over {@code n} elements costs {@code 1 + n}, and a filter costs {@code 1 + n + n·c(p)}
 * — so the cost of a policy is bounded by the data it reads, not just by how it is written.
 *
 * <p><b>Why a budget at all.</b> A policy is authored by a business user and executed on the hot
 * path of a lender's API. Without a bound, one badly written filter over a large bureau report is
 * an outage: the request does not fail, it hangs, and the latency budget for every other caller
 * goes with it. Running out of fuel is turned into a decision — ERROR, which refers — rather than
 * into a timeout, which tells the caller nothing.
 *
 * <p>Running out is <b>fail-closed</b>. §3.10 is explicit that every component not yet evaluated is
 * reported ERROR too, never NOT_APPLICABLE: a policy that ran out of fuel half way has not decided
 * that the remaining rules do not apply.
 */
public final class FuelMeter {

  /** The default budget, per 00 §3.10. */
  public static final int DEFAULT_LIMIT = 1_000_000;

  private final long limit;
  private long used;
  private boolean exhausted;

  /**
   * Creates a meter.
   *
   * @param limit the budget in fuel units
   */
  public FuelMeter(long limit) {
    this.limit = limit;
  }

  /** Creates a meter with the default budget. */
  public static FuelMeter unlimited() {
    return new FuelMeter(Long.MAX_VALUE);
  }

  /**
   * Charges one unit.
   *
   * @throws EvalException {@link EvalErrorCode#EVAL_FUEL_EXHAUSTED} once the budget is spent
   */
  public void charge() {
    charge(1);
  }

  /**
   * Charges several units.
   *
   * @param units how many to charge
   * @throws EvalException {@link EvalErrorCode#EVAL_FUEL_EXHAUSTED} once the budget is spent
   */
  public void charge(long units) {
    used += units;
    if (used > limit) {
      exhausted = true;
      throw new EvalException(EvalErrorCode.EVAL_FUEL_EXHAUSTED, null, null);
    }
  }

  /**
   * Returns whether the budget has been exhausted.
   *
   * <p>Once it has, every component still to be evaluated is reported ERROR with the same code
   * (§3.10) — which is why this is asked before each component rather than only caught per
   * expression.
   */
  public boolean isExhausted() {
    return exhausted;
  }

  /** Returns how much fuel has been spent. */
  public long used() {
    return used;
  }

  /** Returns the budget. */
  public long limit() {
    return limit;
  }
}
