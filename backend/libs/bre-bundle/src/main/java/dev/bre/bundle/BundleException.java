package dev.bre.bundle;

/**
 * A bundle could not be read, verified or trusted.
 *
 * <p>Every failure here is fail-closed (D9): the bundle is not loaded, and the pod keeps serving
 * whatever it was serving before (§8.3). A policy that cannot be verified is not a policy.
 */
public final class BundleException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Why verification failed. */
  public enum Reason {
    /** The document is not a {@code bre-bundle/1}. */
    MALFORMED,
    /** The recomputed hash does not match the one the bundle claims. */
    HASH_MISMATCH,
    /** No signature of a role this verifier requires. */
    MISSING_SIGNATURE,
    /** A signature did not verify against the key it names. */
    BAD_SIGNATURE,
    /** The signing key is not one this installation trusts. */
    UNTRUSTED_KEY
  }

  private final Reason reason;

  /**
   * Creates a bundle exception.
   *
   * @param reason why verification failed
   * @param detail what was wrong — never any bundle content beyond identifiers
   */
  public BundleException(Reason reason, String detail) {
    super(reason + ": " + detail);
    this.reason = reason;
  }

  /**
   * Returns why verification failed.
   *
   * @return the reason
   */
  public Reason reason() {
    return reason;
  }
}
