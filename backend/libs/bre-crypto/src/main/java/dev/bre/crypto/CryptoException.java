package dev.bre.crypto;

/**
 * A cryptographic operation failed (§9.2).
 *
 * <p><b>The message names the key and the purpose, never the data.</b> What is being sealed here is
 * an applicant's personal information, and a message that quoted it — or quoted a fragment of
 * ciphertext, or the key — would put it in a log, which is the one place §9.6 exists to keep it out
 * of. The {@code kid} and the AAD purpose are what someone diagnosing a failure actually needs, and
 * both are safe to keep.
 */
public final class CryptoException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** What went wrong, in terms an operator can act on. */
  public enum Reason {
    /** The named key does not exist, here or in the KMS. */
    UNKNOWN_KEY,
    /** The key exists but has been destroyed — a crypto-shredded principal (§9.5). */
    SHREDDED,
    /** The envelope is not a {@code 0x01} envelope, or is truncated. */
    MALFORMED_ENVELOPE,
    /** The authentication tag did not verify: wrong key, wrong AAD, or tampering. */
    NOT_AUTHENTIC,
    /** The platform refused the operation — a missing algorithm or a KMS error. */
    UNAVAILABLE
  }

  private final Reason reason;

  /**
   * Creates an exception.
   *
   * @param reason what went wrong
   * @param detail a description naming keys and purposes, never data
   */
  public CryptoException(Reason reason, String detail) {
    super(reason + ": " + detail);
    this.reason = reason;
  }

  /**
   * Creates an exception wrapping a platform failure.
   *
   * @param reason what went wrong
   * @param detail a description naming keys and purposes, never data
   * @param cause the underlying failure
   */
  public CryptoException(Reason reason, String detail, Throwable cause) {
    super(reason + ": " + detail, cause);
    this.reason = reason;
  }

  /**
   * Returns the reason.
   *
   * @return the reason
   */
  public Reason reason() {
    return reason;
  }
}
