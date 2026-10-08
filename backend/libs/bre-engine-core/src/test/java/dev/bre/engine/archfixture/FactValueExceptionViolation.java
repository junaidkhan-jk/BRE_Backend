package dev.bre.engine.archfixture;

/**
 * DELIBERATE VIOLATION of LLD §1.2 rule 3. The exception carries the fact VALUE, which leaks PII
 * into every stack trace and log line. The compliant form carries the path and the type.
 */
public final class FactValueExceptionViolation extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Takes a fact value. A type named {@code RecordV} is one of the fact-value types the rule bans
   * in exception constructors.
   *
   * @param offendingValue the fact value that must not be captured
   */
  public FactValueExceptionViolation(RecordV offendingValue) {
    super("bad value: " + offendingValue);
  }

  /** Stand-in for the engine's record value type, which lands in M1-01. */
  public record RecordV(String contents) {}
}
