package dev.bre.decision.policy;

/**
 * No policy could be resolved for a request.
 *
 * <p>Always fail-closed: the request is refused rather than decided against some fallback. A
 * decision taken against the wrong policy is worse than no decision, because the caller acts on it.
 */
public final class PolicyResolutionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Why resolution failed. */
  public enum Reason {
    /** This pod has no manifest at all — it has never reached the registry (§8.5). */
    NO_MANIFEST,
    /** The manifest has no entry for this tenant, environment and policy. */
    NOT_SERVED,
    /** A pin was asked for that is not servable, or pinning is not allowed (§8.1). */
    PIN_REFUSED
  }

  private final Reason reason;

  /**
   * Creates the exception.
   *
   * @param reason why resolution failed
   * @param detail what was asked for — identifiers only, never facts
   */
  public PolicyResolutionException(Reason reason, String detail) {
    super(reason + ": " + detail);
    this.reason = reason;
  }

  /**
   * Returns why resolution failed.
   *
   * @return the reason
   */
  public Reason reason() {
    return reason;
  }
}
