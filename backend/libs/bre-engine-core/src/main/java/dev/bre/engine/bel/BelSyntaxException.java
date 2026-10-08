package dev.bre.engine.bel;

/**
 * A BEL lexical or syntax error, carrying the position it was found at.
 *
 * <p>Carries the offending <em>source text</em> and a position, never a fact value: a BEL
 * expression is policy authored by a human, not customer data, so quoting it is safe — and the
 * ArchUnit rule {@code exceptionsNeverCarryFactValues} keeps that distinction honest by rejecting
 * any constructor that takes a {@code Value}.
 */
public final class BelSyntaxException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int line;
  private final int column;
  private final int offset;

  /**
   * Creates a syntax error.
   *
   * @param message what was wrong, and what was expected
   * @param line one-based line
   * @param column one-based column
   * @param offset zero-based character offset
   */
  public BelSyntaxException(String message, int line, int column, int offset) {
    super(message + " (at " + line + ":" + column + ")");
    this.line = line;
    this.column = column;
    this.offset = offset;
  }

  /** Returns the one-based line of the error. */
  public int line() {
    return line;
  }

  /** Returns the one-based column of the error. */
  public int column() {
    return column;
  }

  /** Returns the zero-based character offset of the error. */
  public int offset() {
    return offset;
  }
}
