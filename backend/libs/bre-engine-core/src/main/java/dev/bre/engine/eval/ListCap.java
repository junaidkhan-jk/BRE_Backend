package dev.bre.engine.eval;

import dev.bre.engine.eval.Value.ListV;
import java.util.List;

/**
 * The list-size cap (LLD §3.10, 00 §3.5 Safety).
 *
 * <p>A list larger than the cap is an error — "whether it arrives as a fact or is produced at
 * runtime". Both halves matter. BEL has no loops and no recursion, so the only way an expression
 * can do unbounded work is over a list, and the only ways a list gets large are a large fact and a
 * projection that flattens one. A cap on facts alone would leave {@code
 * group.members.bureauReports.tradelines} free to multiply three bounded lists into an unbounded
 * one.
 *
 * <p>Exceeding it is {@link EvalErrorCode#EVAL_LIST_TOO_LARGE}, which is an {@code ERROR} on the
 * component and therefore refers the application (§4.8). It is deliberately not a silent
 * truncation: a borrower whose exposure was summed over the first 10,000 of 12,000 tradelines would
 * be assessed on an understated total, which is the direction that approves a loan it should not.
 *
 * <p>The cap is configuration ({@code bre.engine.max-list-size}) rather than a constant, because a
 * tenant doing MSME lending against large bureau files may legitimately need a higher one, and the
 * fuel meter — not this cap — is what bounds the work.
 */
public final class ListCap {

  /** §3.10's default for {@code bre.engine.max-list-size}. */
  public static final int DEFAULT_MAX_LIST_SIZE = 10_000;

  private final int maxSize;

  /**
   * Creates a cap.
   *
   * @param maxSize the largest permitted list, which must be positive
   */
  public ListCap(int maxSize) {
    if (maxSize <= 0) {
      throw new IllegalArgumentException("the list cap must be positive, not " + maxSize);
    }
    this.maxSize = maxSize;
  }

  /** The §3.10 default cap. */
  public static ListCap defaults() {
    return new ListCap(DEFAULT_MAX_LIST_SIZE);
  }

  /**
   * The cap that permits any list.
   *
   * <p>For a unit test that is not about the cap. Never for a decision: the point of the cap is
   * that a policy's worst case is bounded before it runs.
   *
   * @return an effectively unlimited cap
   */
  public static ListCap unlimited() {
    return new ListCap(Integer.MAX_VALUE);
  }

  /**
   * Returns the cap's limit.
   *
   * @return the largest permitted list size
   */
  public int maxSize() {
    return maxSize;
  }

  /**
   * Checks a list that is about to be produced, and returns it.
   *
   * @param elements the elements
   * @return a list value over them
   * @throws EvalException EVAL_LIST_TOO_LARGE if there are more than the cap permits
   */
  public ListV of(List<Value> elements) {
    check(elements.size());
    return new ListV(elements);
  }

  /**
   * Checks a size without building a list.
   *
   * <p>Used where the result size is known before the work is done, so that a projection over a
   * list far past the cap fails before it allocates rather than after.
   *
   * @param size the size to check
   * @throws EvalException EVAL_LIST_TOO_LARGE if it exceeds the cap
   */
  public void check(int size) {
    if (size > maxSize) {
      // The message carries sizes, never elements: a list element is a fact value (§9.6).
      throw new EvalException(
          EvalErrorCode.EVAL_LIST_TOO_LARGE,
          null,
          "list of " + size + " exceeds the cap of " + maxSize);
    }
  }
}
