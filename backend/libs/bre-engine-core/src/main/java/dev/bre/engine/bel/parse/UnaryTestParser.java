package dev.bre.engine.bel.parse;

import dev.bre.engine.bel.BelSyntaxException;
import dev.bre.engine.bel.ast.Expr;
import dev.bre.engine.bel.lex.Lexer;
import dev.bre.engine.bel.lex.Token;
import dev.bre.engine.bel.lex.TokenKind;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a decision-table cell or scorecard bin (LLD §3.3).
 *
 * <pre>
 * unaryTests = "-" | unaryTest , { "," , unaryTest } ;   (* "," = OR *)
 * unaryTest  = "missing"
 *            | compOp , additive
 *            | range
 *            | [ "not" ] , "in" , inTarget
 *            | additive ;                                 (* bare value means "= value" *)
 * </pre>
 *
 * <p>The comma does double duty, and that is the thing to get right: it separates a cell's own
 * alternatives <em>and</em> appears inside a list literal such as {@code in ["A", "B"]}. Every
 * operand is therefore consumed as a <b>balanced</b> run of tokens — see {@link #takeExpression()}
 * — so a list's internal commas are never mistaken for cell separators.
 */
public final class UnaryTestParser {

  private final List<Token> tokens;
  private int position;

  private UnaryTestParser(List<Token> tokens) {
    this.tokens = tokens;
  }

  /**
   * Parses one cell.
   *
   * @param source the cell text
   * @return the parsed test
   * @throws BelSyntaxException on a lexical or syntax error, positioned at the offending token
   */
  public static UnaryTest parse(String source) {
    UnaryTestParser parser = new UnaryTestParser(Lexer.tokenize(source));
    UnaryTest test = parser.unaryTests();
    if (parser.peek().kind() != TokenKind.EOF) {
      throw parser.error(parser.peek(), "end of the cell");
    }
    return test;
  }

  private UnaryTest unaryTests() {
    List<UnaryTest> alternatives = new ArrayList<>();
    alternatives.add(unaryTest());
    while (peek().kind() == TokenKind.COMMA) {
      advance();
      alternatives.add(unaryTest());
    }
    return alternatives.size() == 1 ? alternatives.get(0) : new UnaryTest.AnyOf(alternatives);
  }

  private UnaryTest unaryTest() {
    Token token = peek();

    // A lone "-" is the dash. A "-" with something after it is a negative number, which the
    // expression parser reads as unary minus.
    if (token.kind() == TokenKind.MINUS && isEndOfTest(peekAt(1).kind())) {
      advance();
      return UnaryTest.AnyPresent.INSTANCE;
    }

    if (token.kind() == TokenKind.MISSING) {
      advance();
      return UnaryTest.IsMissing.INSTANCE;
    }

    if (isCompareOp(token.kind())) {
      advance();
      return new UnaryTest.Compare(compareOp(token.kind()), takeExpression());
    }

    boolean negated = false;
    if (token.kind() == TokenKind.NOT) {
      advance();
      negated = true;
      if (peek().kind() != TokenKind.IN) {
        throw error(peek(), "'in' after 'not' in a decision-table cell");
      }
    }

    if (peek().kind() == TokenKind.IN) {
      advance();
      return new UnaryTest.In(takeExpression(), negated);
    }

    // A range, a bracketed list, or a bare value meaning "= value".
    if (peek().kind() == TokenKind.LBRACKET || peek().kind() == TokenKind.LPAREN) {
      Expr parsed = takeExpression();
      if (parsed instanceof Expr.Range range) {
        return new UnaryTest.Range(
            range.low(), range.lowInclusive(), range.high(), range.highInclusive());
      }
      // A bare list literal in a cell reads as membership.
      return new UnaryTest.In(parsed, false);
    }

    return new UnaryTest.Compare(Expr.CompareOp.EQ, takeExpression());
  }

  /**
   * Consumes the next balanced run of tokens and parses it as a BEL expression.
   *
   * <p>"Balanced" is the point: a cell like {@code in ["A", "B"]} contains a comma belonging to the
   * list, not to the cell's comma-separated OR. Stopping at the first comma regardless of depth
   * splits the list in half, and the remainder then fails to parse.
   *
   * <p>ponytail: the run is re-rendered to source and re-lexed rather than handed to the parser as
   * tokens, because {@link Parser} owns the expression grammar and a token-level entry point would
   * be a second way in. The cost is one extra lex of a short string; the alternative is two copies
   * of the grammar, which is how they drift apart.
   */
  private Expr takeExpression() {
    int from = position;
    int depth = 0;
    int to = position;

    while (to < tokens.size()) {
      Token token = tokens.get(to);
      if (token.kind() == TokenKind.EOF) {
        break;
      }
      if (token.kind() == TokenKind.LBRACKET || token.kind() == TokenKind.LPAREN) {
        depth++;
      } else if (token.kind() == TokenKind.RBRACKET || token.kind() == TokenKind.RPAREN) {
        if (depth == 0) {
          break;
        }
        depth--;
      } else if (token.kind() == TokenKind.COMMA && depth == 0) {
        break;
      }
      to++;
    }

    if (to == from) {
      throw error(peek(), "a value");
    }
    String source = render(from, to);
    position = to;
    return Parser.parseExpression(source);
  }

  /** Re-renders tokens {@code [from, to)} as BEL source, re-quoting strings the lexer decoded. */
  private String render(int from, int to) {
    StringBuilder text = new StringBuilder();
    for (int index = from; index < to; index++) {
      Token token = tokens.get(index);
      if (!text.isEmpty()) {
        text.append(' ');
      }
      text.append(token.kind() == TokenKind.STRING ? quote(token.text()) : token.text());
    }
    return text.toString();
  }

  private static String quote(String value) {
    return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
  }

  private static boolean isEndOfTest(TokenKind kind) {
    return kind == TokenKind.EOF || kind == TokenKind.COMMA;
  }

  private static boolean isCompareOp(TokenKind kind) {
    return switch (kind) {
      case EQ, NE, LT, LE, GT, GE -> true;
      default -> false;
    };
  }

  private static Expr.CompareOp compareOp(TokenKind kind) {
    return switch (kind) {
      case EQ -> Expr.CompareOp.EQ;
      case NE -> Expr.CompareOp.NE;
      case LT -> Expr.CompareOp.LT;
      case LE -> Expr.CompareOp.LE;
      case GT -> Expr.CompareOp.GT;
      case GE -> Expr.CompareOp.GE;
      default -> throw new IllegalArgumentException("not a comparison operator: " + kind);
    };
  }

  private Token peek() {
    return tokens.get(position);
  }

  private Token peekAt(int ahead) {
    return tokens.get(Math.min(position + ahead, tokens.size() - 1));
  }

  private Token advance() {
    Token token = tokens.get(position);
    if (token.kind() != TokenKind.EOF) {
      position++;
    }
    return token;
  }

  private BelSyntaxException error(Token token, String expected) {
    String found = token.kind() == TokenKind.EOF ? "end of the cell" : "'" + token.text() + "'";
    return new BelSyntaxException(
        "expected " + expected + " but found " + found,
        token.line(),
        token.column(),
        token.start());
  }
}
