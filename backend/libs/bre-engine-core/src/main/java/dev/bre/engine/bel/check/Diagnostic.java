package dev.bre.engine.bel.check;

/**
 * One compiler diagnostic (LLD §4.14).
 *
 * <p>Carries the error code, a 1-based position and a message. The position is not decoration: the
 * studio puts a marker on it (§13.3), and a diagnostic without one cannot be shown in an editor.
 *
 * <p><b>The message names fields and types, never values.</b> A policy compiles against a schema,
 * not against an application, so there is rarely a value to leak here — but {@code measure} and
 * {@code outputs} expressions are checked with the same code paths that later evaluate them, and
 * §9.6 applies to anything that can reach a log.
 *
 * @param code the catalogue code, e.g. {@code BEL-003}
 * @param line the 1-based line
 * @param column the 1-based column
 * @param message what is wrong
 */
public record Diagnostic(String code, int line, int column, String message) {

  /** BEL-002. */
  public static final String UNKNOWN_IDENTIFIER = "BEL-002";

  /** BEL-003. */
  public static final String TYPE_MISMATCH = "BEL-003";

  /** BEL-004. */
  public static final String UNKNOWN_FUNCTION = "BEL-004";

  /** BEL-005. */
  public static final String WRONG_ARITY = "BEL-005";

  /** BEL-006. */
  public static final String IT_OUTSIDE_FILTER = "BEL-006";

  /** BEL-007. */
  public static final String FILTER_ON_NON_LIST = "BEL-007";

  /** BEL-008. */
  public static final String FILTER_PREDICATE_NOT_BOOLEAN = "BEL-008";

  /** BEL-010. */
  public static final String ORDERING_NOT_ALLOWED = "BEL-010";

  /** BEL-011. */
  public static final String NOT_AN_ENUM_VALUE = "BEL-011";

  /** BEL-017. */
  public static final String PROTECTED_FIELD = "BEL-017";

  /** BEL-019. */
  public static final String UNKNOWN_FACT_PATH = "BEL-019";

  /** BEL-021. */
  public static final String DATE_AND_DATETIME = "BEL-021";

  /** BEL-022. */
  public static final String UNKNOWN_PARAMETER = "BEL-022";

  /** BEL-023. */
  public static final String UNKNOWN_REFERENCE_LIST = "BEL-023";

  /** BEL-024. */
  public static final String LIST_ELEMENT_MISMATCH = "BEL-024";

  /** BEL-026. */
  public static final String AADHAAR_FIELD = "BEL-026";

  @Override
  public String toString() {
    return code + " at " + line + ":" + column + ": " + message;
  }
}
