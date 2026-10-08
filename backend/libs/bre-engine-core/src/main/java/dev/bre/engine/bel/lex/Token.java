package dev.bre.engine.bel.lex;

/**
 * One BEL token, with the source span it came from.
 *
 * <p>The span is carried on every token because M1-02 requires positioned diagnostics: an author
 * writing a policy in the studio needs to be told <em>where</em> their expression is wrong, not
 * just that it is.
 *
 * @param kind the token class
 * @param text the exact source text of the token, with escapes already decoded for strings
 * @param start zero-based offset of the token's first character
 * @param end zero-based offset one past the token's last character
 * @param line one-based line number of the token's first character
 * @param column one-based column number of the token's first character
 */
public record Token(TokenKind kind, String text, int start, int end, int line, int column) {

  /** Returns a short {@code line:column} description for diagnostics. */
  public String position() {
    return line + ":" + column;
  }

  @Override
  public String toString() {
    return kind + "(" + text + ")@" + position();
  }
}
