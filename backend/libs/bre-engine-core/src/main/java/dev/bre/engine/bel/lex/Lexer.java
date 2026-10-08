package dev.bre.engine.bel.lex;

import dev.bre.engine.bel.BelSyntaxException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hand-written BEL lexer (LLD §3.1, M1-02).
 *
 * <p>Hand-written rather than generated because the studio needs precise, positioned diagnostics
 * for business authors, and because the engine's dependency ceiling is the JDK plus {@code re2j} —
 * a parser generator's runtime is not on the list (D3, LLD §1.2).
 *
 * <p>Two rules here are easy to get wrong and are pinned by tests:
 *
 * <ul>
 *   <li>A {@code .} belongs to a number <b>only if a digit follows it</b>, so {@code 2..3} lexes as
 *       {@code 2}, {@code ..}, {@code 3} — not as {@code 2.} followed by {@code .3}.
 *   <li>Underscores in numbers are visual separators only: {@code 200_000} is one integer.
 * </ul>
 */
public final class Lexer {

  /** Identifiers are capped at 64 characters (§3.1). */
  public static final int MAX_IDENTIFIER_LENGTH = 64;

  /** String literals are capped at 4,000 characters (§3.1). */
  public static final int MAX_STRING_LENGTH = 4_000;

  private static final Map<String, TokenKind> RESERVED =
      Map.ofEntries(
          Map.entry("and", TokenKind.AND),
          Map.entry("or", TokenKind.OR),
          Map.entry("not", TokenKind.NOT),
          Map.entry("in", TokenKind.IN),
          Map.entry("between", TokenKind.BETWEEN),
          Map.entry("if", TokenKind.IF),
          Map.entry("then", TokenKind.THEN),
          Map.entry("else", TokenKind.ELSE),
          Map.entry("true", TokenKind.TRUE),
          Map.entry("false", TokenKind.FALSE),
          Map.entry("it", TokenKind.IT),
          Map.entry("asOf", TokenKind.AS_OF),
          Map.entry("param", TokenKind.PARAM),
          Map.entry("list", TokenKind.LIST),
          Map.entry("rule", TokenKind.RULE),
          Map.entry("table", TokenKind.TABLE),
          Map.entry("scorecard", TokenKind.SCORECARD),
          Map.entry("missing", TokenKind.MISSING));

  private final String source;
  private int offset;
  private int line = 1;
  private int lineStart;

  private Lexer(String source) {
    this.source = source;
  }

  /**
   * Tokenises a BEL expression or unary test.
   *
   * @param source the source text
   * @return the tokens, ending with a single {@link TokenKind#EOF}
   * @throws BelSyntaxException on an unterminated string, a bad escape, an over-long identifier or
   *     string, or a character that is not part of the language
   */
  public static List<Token> tokenize(String source) {
    return new Lexer(source).run();
  }

  private List<Token> run() {
    List<Token> tokens = new ArrayList<>();
    while (true) {
      skipWhitespace();
      if (offset >= source.length()) {
        tokens.add(make(TokenKind.EOF, "", offset, offset, column(offset)));
        return tokens;
      }
      tokens.add(nextToken());
    }
  }

  private void skipWhitespace() {
    // BEL has no comments; PSF/YAML comments sit outside expressions (§3.1).
    while (offset < source.length()) {
      char c = source.charAt(offset);
      if (c == '\n') {
        offset++;
        line++;
        lineStart = offset;
      } else if (c == ' ' || c == '\t' || c == '\r') {
        offset++;
      } else {
        return;
      }
    }
  }

  private Token nextToken() {
    int start = offset;
    int startColumn = column(start);
    char c = source.charAt(offset);

    if (isIdentifierStart(c)) {
      return identifier(start, startColumn);
    }
    if (isDigit(c)) {
      return number(start, startColumn);
    }
    if (c == '"') {
      return string(start, startColumn);
    }
    return punctuationOrOperator(start, startColumn, c);
  }

  private Token identifier(int start, int startColumn) {
    while (offset < source.length() && isIdentifierPart(source.charAt(offset))) {
      offset++;
    }
    String text = source.substring(start, offset);
    if (text.length() > MAX_IDENTIFIER_LENGTH) {
      throw error(
          "identifier is " + text.length() + " characters; the limit is " + MAX_IDENTIFIER_LENGTH,
          start,
          startColumn);
    }
    TokenKind reserved = RESERVED.get(text);
    return make(reserved == null ? TokenKind.IDENT : reserved, text, start, offset, startColumn);
  }

  private Token number(int start, int startColumn) {
    consumeDigitsWithSeparators(start, startColumn);

    // A '.' is part of the number only if a digit follows it. This is what makes "2..3" lex as
    // 2, .., 3 rather than as a malformed decimal (§3.1).
    boolean isDecimal = false;
    if (offset + 1 < source.length()
        && source.charAt(offset) == '.'
        && isDigit(source.charAt(offset + 1))) {
      isDecimal = true;
      offset++;
      consumeDigitsWithSeparators(start, startColumn);
    }

    String text = source.substring(start, offset);
    return make(
        isDecimal ? TokenKind.DECIMAL : TokenKind.INTEGER, text, start, offset, startColumn);
  }

  private void consumeDigitsWithSeparators(int tokenStart, int tokenColumn) {
    boolean sawDigit = false;
    while (offset < source.length()) {
      char c = source.charAt(offset);
      if (isDigit(c)) {
        sawDigit = true;
        offset++;
      } else if (c == '_') {
        // Underscores are visual separators and must sit between digit groups (§3.1).
        if (!sawDigit || offset + 1 >= source.length() || !isDigit(source.charAt(offset + 1))) {
          throw error(
              "'_' in a number must separate digit groups, as in 200_000", tokenStart, tokenColumn);
        }
        offset++;
      } else {
        return;
      }
    }
  }

  private Token string(int start, int startColumn) {
    offset++; // opening quote
    StringBuilder decoded = new StringBuilder();
    while (true) {
      if (offset >= source.length()) {
        throw error("unterminated string literal", start, startColumn);
      }
      char c = source.charAt(offset);
      if (c == '"') {
        offset++;
        break;
      }
      if (c == '\n') {
        throw error("unterminated string literal: a newline inside a string", start, startColumn);
      }
      if (c == '\\') {
        offset++;
        decoded.append(escape(start, startColumn));
      } else {
        decoded.append(c);
        offset++;
      }
    }

    if (decoded.length() > MAX_STRING_LENGTH) {
      throw error(
          "string literal is "
              + decoded.length()
              + " characters; the limit is "
              + MAX_STRING_LENGTH,
          start,
          startColumn);
    }
    // Strings are NFC-normalised at compile time (§3.1), so two spellings of the same text can
    // never compare unequal at runtime.
    String text = Normalizer.normalize(decoded.toString(), Normalizer.Form.NFC);
    return make(TokenKind.STRING, text, start, offset, startColumn);
  }

  private char escape(int tokenStart, int tokenColumn) {
    if (offset >= source.length()) {
      throw error("unterminated escape sequence", tokenStart, tokenColumn);
    }
    char c = source.charAt(offset++);
    return switch (c) {
      case '"' -> '"';
      case '\\' -> '\\';
      case 'n' -> '\n';
      case 'r' -> '\r';
      case 't' -> '\t';
      case 'u' -> unicodeEscape(tokenStart, tokenColumn);
      default ->
          throw error(
              "unknown escape '\\" + c + "'; BEL allows \\\" \\\\ \\n \\r \\t and \\uXXXX",
              tokenStart,
              tokenColumn);
    };
  }

  private char unicodeEscape(int tokenStart, int tokenColumn) {
    if (offset + 4 > source.length()) {
      throw error("\\u escape needs four hex digits", tokenStart, tokenColumn);
    }
    String hex = source.substring(offset, offset + 4);
    for (int i = 0; i < 4; i++) {
      if (Character.digit(hex.charAt(i), 16) < 0) {
        throw error("\\u escape needs four hex digits, got '" + hex + "'", tokenStart, tokenColumn);
      }
    }
    offset += 4;
    return (char) Integer.parseInt(hex, 16);
  }

  private Token punctuationOrOperator(int start, int startColumn, char c) {
    switch (c) {
      case '(':
        offset++;
        return make(TokenKind.LPAREN, "(", start, offset, startColumn);
      case ')':
        offset++;
        return make(TokenKind.RPAREN, ")", start, offset, startColumn);
      case '[':
        offset++;
        return make(TokenKind.LBRACKET, "[", start, offset, startColumn);
      case ']':
        offset++;
        return make(TokenKind.RBRACKET, "]", start, offset, startColumn);
      case ',':
        offset++;
        return make(TokenKind.COMMA, ",", start, offset, startColumn);
      case '.':
        offset++;
        if (offset < source.length() && source.charAt(offset) == '.') {
          offset++;
          return make(TokenKind.DOT_DOT, "..", start, offset, startColumn);
        }
        return make(TokenKind.DOT, ".", start, offset, startColumn);
      case '+':
        offset++;
        return make(TokenKind.PLUS, "+", start, offset, startColumn);
      case '-':
        offset++;
        return make(TokenKind.MINUS, "-", start, offset, startColumn);
      case '*':
        offset++;
        return make(TokenKind.STAR, "*", start, offset, startColumn);
      case '/':
        offset++;
        return make(TokenKind.SLASH, "/", start, offset, startColumn);
      case '=':
        offset++;
        return make(TokenKind.EQ, "=", start, offset, startColumn);
      case '!':
        offset++;
        if (offset < source.length() && source.charAt(offset) == '=') {
          offset++;
          return make(TokenKind.NE, "!=", start, offset, startColumn);
        }
        throw error("'!' is only valid as '!='", start, startColumn);
      case '<':
        offset++;
        if (offset < source.length() && source.charAt(offset) == '=') {
          offset++;
          return make(TokenKind.LE, "<=", start, offset, startColumn);
        }
        return make(TokenKind.LT, "<", start, offset, startColumn);
      case '>':
        offset++;
        if (offset < source.length() && source.charAt(offset) == '=') {
          offset++;
          return make(TokenKind.GE, ">=", start, offset, startColumn);
        }
        return make(TokenKind.GT, ">", start, offset, startColumn);
      default:
        throw error("unexpected character '" + c + "'", start, startColumn);
    }
  }

  private Token make(TokenKind kind, String text, int start, int end, int startColumn) {
    return new Token(kind, text, start, end, line, startColumn);
  }

  private int column(int at) {
    return at - lineStart + 1;
  }

  private BelSyntaxException error(String message, int at, int atColumn) {
    return new BelSyntaxException(message, line, atColumn, at);
  }

  private static boolean isIdentifierStart(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
  }

  private static boolean isIdentifierPart(char c) {
    return isIdentifierStart(c) || isDigit(c);
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }
}
