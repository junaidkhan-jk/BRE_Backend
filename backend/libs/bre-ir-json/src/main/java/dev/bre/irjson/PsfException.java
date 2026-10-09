package dev.bre.irjson;

/**
 * A PSF decode or canonicalisation failure (LLD §4.14 error catalogue).
 *
 * <p>Carries the error code, the document path it occurred at, and a detail — never the offending
 * value. A PSF document is policy source, and a facts document canonicalised for the integrity HMAC
 * is personal data (§9.6); a message quoting either ends up in a log. The path is what someone
 * diagnosing the failure needs, and it is safe to keep.
 */
public final class PsfException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Error codes this exception reports. */
  public enum Code {
    /** YAML parse error. */
    PSF_001("PSF-001"),
    /** Unquoted non-integer number; quote decimal literals. */
    PSF_002("PSF-002"),
    /** Unknown key. */
    PSF_003("PSF-003"),
    /** Non-integer JSON number in a canonical document. */
    CANON_001("CANON-001"),
    /** A policy names a parameter set or reference list the asset source does not define. */
    ASSET_001("ASSET-001"),
    /** A parameter or list element is not well-formed for its declared type. */
    ASSET_002("ASSET-002");

    private final String wire;

    Code(String wire) {
      this.wire = wire;
    }

    /**
     * Returns the code as the catalogue spells it.
     *
     * @return e.g. {@code "PSF-002"}
     */
    public String wire() {
      return wire;
    }
  }

  private final Code code;
  private final String path;

  /**
   * Creates a PSF exception.
   *
   * @param code the error code
   * @param path the document path, e.g. {@code rules[2].onFail.measure}
   * @param detail what was wrong, naming no value
   */
  public PsfException(Code code, String path, String detail) {
    super(code.wire() + " at " + (path.isEmpty() ? "<root>" : path) + ": " + detail);
    this.code = code;
    this.path = path;
  }

  /**
   * Creates a PSF exception wrapping a parser failure.
   *
   * @param code the error code
   * @param path the document path
   * @param detail what was wrong, naming no value
   * @param cause the underlying failure
   */
  public PsfException(Code code, String path, String detail, Throwable cause) {
    super(code.wire() + " at " + (path.isEmpty() ? "<root>" : path) + ": " + detail, cause);
    this.code = code;
    this.path = path;
  }

  /**
   * Returns the error code.
   *
   * @return the code
   */
  public Code code() {
    return code;
  }

  /**
   * Returns the document path the failure occurred at.
   *
   * @return the path, empty at the document root
   */
  public String path() {
    return path;
  }
}
