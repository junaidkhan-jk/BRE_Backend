package dev.bre.engine.bel.parse;

import dev.bre.engine.bel.BelSyntaxException;
import dev.bre.engine.bel.ast.Expr;
import dev.bre.engine.bel.lex.Lexer;
import dev.bre.engine.bel.lex.Token;
import dev.bre.engine.bel.lex.TokenKind;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Hand-written recursive-descent parser for BEL expressions (LLD §3.2, M1-02).
 *
 * <p>The grammar is LL(1) except at one point, which §3.2 names: after {@code [} or {@code (} and
 * the first {@code additive}, the next token decides — {@code ..} means a range, {@code ,} or
 * {@code ]} means a list literal, {@code )} means a parenthesised expression. {@link
 * #inTarget(Token)} is the only place that lookahead is needed, and it is handled by parsing the
 * first operand once and then deciding, rather than by backtracking.
 *
 * <p>Two deliberate rejections, both from §3.2:
 *
 * <ul>
 *   <li><b>Comparison is non-associative.</b> {@code a < b < c} is a syntax error, not a chain.
 *   <li><b>{@code l[0]} is not positional access.</b> An integer index into a fact list is the
 *       compile error BEL-009; authors use {@code first(...)}. The parser produces a {@link
 *       Expr.Filter} and the type checker rejects a non-Boolean predicate (M1-03), so the error
 *       arrives with a type, not a parse failure.
 * </ul>
 */
public final class Parser {

  /** Reserved words that can root a namespaced reference chain (§3.2, §3.8). */
  private static final Set<TokenKind> REFERENCE_ROOTS =
      EnumSet.of(
          TokenKind.AS_OF,
          TokenKind.PARAM,
          TokenKind.LIST,
          TokenKind.RULE,
          TokenKind.TABLE,
          TokenKind.SCORECARD,
          TokenKind.IT);

  /**
   * The deepest nesting the parser will descend into.
   *
   * <p>A recursive-descent parser recurses once per level of nesting, so text like {@code
   * ((((((…))))))} or a chain of filters costs one stack frame per bracket. Without a limit that is
   * a {@code StackOverflowError} — found by Jazzer in 54 seconds (see {@code BelParserFuzzTest}),
   * from a 3 KB input of nested {@code [} and {@code (}.
   *
   * <p>That is a denial of service rather than a cosmetic defect: BEL text arrives from the studio,
   * and in a multi-tenant control plane one tenant must not be able to crash the compile path for
   * everyone. A {@code StackOverflowError} can also leave a JVM in a state where unrelated work
   * fails, which is why this is a limit and not a caught error.
   *
   * <p>32, and the number is measured rather than chosen. One level of nesting costs about ten
   * stack frames — {@code conditional -> disjunction -> conjunction -> negation -> comparison ->
   * additive -> multiplicative -> unary -> postfix -> primary} — so a limit of 128 is some 1,300
   * frames, which survived on a plain JVM and still overflowed under Gradle's test worker, where
   * the JUnit and AssertJ frames sit below the parser. 32 is about 320 frames, comfortable
   * anywhere, and still four times the deepest expression in the reference policy, which nests
   * eight levels inside a filtered projection.
   *
   * <p>The counter sits on {@link #primary()} and on {@link #unary()}, which between them bound
   * every recursion. It was first put on {@code expression()}, which looked like the natural place
   * and was wrong: {@code additive -> multiplicative -> unary -> postfix -> primary ->
   * bracketedTargetOrRange -> additive} is a cycle that never passes through it, so nested list
   * literals still overflowed. {@code primary()} is where every nested construct bottoms out.
   */
  public static final int MAX_NESTING_DEPTH = 32;

  private final List<Token> tokens;
  private int position;
  private int depth;

  private Parser(List<Token> tokens) {
    this.tokens = tokens;
  }

  /**
   * Parses a complete BEL expression.
   *
   * @param source the expression text
   * @return the parsed tree
   * @throws BelSyntaxException on any lexical or syntax error, positioned at the offending token
   */
  public static Expr parseExpression(String source) {
    Parser parser = new Parser(Lexer.tokenize(source));
    Expr expr = parser.expression();
    parser.expect(TokenKind.EOF, "end of expression");
    return expr;
  }

  // expression = conditional
  private Expr expression() {
    return conditional();
  }

  /**
   * Counts one level of nesting, refusing to descend past {@link #MAX_NESTING_DEPTH}.
   *
   * <p>Reported as an ordinary syntax error, because from an author's point of view that is what it
   * is: the text is not a BEL expression this parser accepts. The position lets the studio put the
   * marker where the nesting became unreasonable.
   */
  private void enterNesting() {
    if (++depth > MAX_NESTING_DEPTH) {
      // Decremented by the caller's finally, so the parser is left consistent if this is caught.
      throw error(peek(), "an expression nested no deeper than " + MAX_NESTING_DEPTH + " levels");
    }
  }

  // conditional = "if" expression "then" expression "else" expression | disjunction
  private Expr conditional() {
    if (check(TokenKind.IF)) {
      Token ifToken = advance();
      Expr condition = expression();
      expect(TokenKind.THEN, "'then'");
      Expr thenBranch = expression();
      // The else branch is mandatory. There is no implicit else: a conditional with no else would
      // have to fall through to something, and §3.5 is explicit that an unknown condition yields
      // missing rather than a default.
      expect(TokenKind.ELSE, "'else' (every if needs one; there is no implicit else)");
      Expr elseBranch = expression();
      return new Expr.If(ifToken, condition, thenBranch, elseBranch);
    }
    return disjunction();
  }

  // disjunction = conjunction { "or" conjunction }   (left-assoc)
  private Expr disjunction() {
    Expr left = conjunction();
    while (check(TokenKind.OR)) {
      Token op = advance();
      Expr right = conjunction();
      left = new Expr.Logical(op, Expr.LogicalOp.OR, left, right);
    }
    return left;
  }

  // conjunction = negation { "and" negation }   (left-assoc)
  private Expr conjunction() {
    Expr left = negation();
    while (check(TokenKind.AND)) {
      Token op = advance();
      Expr right = negation();
      left = new Expr.Logical(op, Expr.LogicalOp.AND, left, right);
    }
    return left;
  }

  // negation = "not" negation | comparison   (right-assoc prefix)
  private Expr negation() {
    if (check(TokenKind.NOT)) {
      Token op = advance();
      // "not in" and "not between" are comparison tails, not a prefix negation. If the token
      // after "not" is one of those, this "not" belongs to the tail of a comparison whose left
      // operand we have not parsed — which the grammar makes impossible, so it is an error here.
      if (check(TokenKind.IN) || check(TokenKind.BETWEEN)) {
        throw error(
            peek(), "'" + peek().text() + "' needs a value on its left, as in 'x not in [...]'");
      }
      return new Expr.Not(op, negation());
    }
    return comparison();
  }

  // comparison = additive [ compTail ]   (NON-associative)
  private Expr comparison() {
    Expr left = additive();

    if (isCompareOp(peek().kind())) {
      Token op = advance();
      Expr right = additive();
      Expr result = new Expr.Comparison(op, compareOp(op.kind()), left, right);
      rejectChainedComparison();
      return result;
    }

    boolean negated = false;
    if (check(TokenKind.NOT)
        && (peekAt(1).kind() == TokenKind.IN || peekAt(1).kind() == TokenKind.BETWEEN)) {
      advance();
      negated = true;
    }

    if (check(TokenKind.IN)) {
      Token op = advance();
      Expr target = inTarget(op);
      Expr result = new Expr.In(op, left, target, negated);
      rejectChainedComparison();
      return result;
    }

    if (check(TokenKind.BETWEEN)) {
      Token op = advance();
      Expr low = additive();
      // Both bounds are `additive`, which is why "x between 1 and 5 and y" parses as
      // "(x between 1 and 5) and y" rather than swallowing y (§3.2).
      expect(TokenKind.AND, "'and' between the two bounds of 'between'");
      Expr high = additive();
      Expr result = new Expr.Between(op, left, low, high, negated);
      rejectChainedComparison();
      return result;
    }

    if (negated) {
      throw error(peek(), "'not' here must be followed by 'in' or 'between'");
    }
    return left;
  }

  private void rejectChainedComparison() {
    if (isCompareOp(peek().kind()) || check(TokenKind.IN) || check(TokenKind.BETWEEN)) {
      throw error(
          peek(),
          "comparison is not associative, so 'a "
              + peek().text()
              + " b' cannot follow another comparison; write it with 'and'");
    }
  }

  // inTarget = listLiteral | range | postfix
  private Expr inTarget(Token inToken) {
    if (check(TokenKind.LBRACKET) || check(TokenKind.LPAREN)) {
      return bracketedTargetOrRange();
    }
    Expr target = postfix();
    if (target == null) {
      throw error(inToken, "'in' needs a list, a range or a list-valued reference on its right");
    }
    return target;
  }

  /**
   * Resolves the grammar's one ambiguity: an opening {@code [} or {@code (} followed by an {@code
   * additive}, where the token after it decides what we are in.
   */
  private Expr bracketedTargetOrRange() {
    Token open = advance();
    boolean square = open.kind() == TokenKind.LBRACKET;

    // "[]" is the empty list literal; a range always has two bounds.
    if (square && check(TokenKind.RBRACKET)) {
      advance();
      return new Expr.ListLit(open, List.of());
    }

    Expr first = additive();

    if (check(TokenKind.DOT_DOT)) {
      advance();
      Expr high = additive();
      Token close = peek();
      boolean highInclusive;
      if (check(TokenKind.RBRACKET)) {
        highInclusive = true;
      } else if (check(TokenKind.RPAREN)) {
        highInclusive = false;
      } else {
        throw error(close, "']' or ')' to close the range");
      }
      advance();
      return new Expr.Range(open, first, square, high, highInclusive);
    }

    if (!square) {
      // "( additive )" is a parenthesised expression, not a target.
      expect(TokenKind.RPAREN, "')' to close the parenthesised expression");
      return first;
    }

    // A list literal whose first element we have already parsed.
    List<Expr> elements = new ArrayList<>();
    elements.add(first);
    while (check(TokenKind.COMMA)) {
      advance();
      elements.add(expression());
    }
    expect(TokenKind.RBRACKET, "']' to close the list literal");
    return new Expr.ListLit(open, elements);
  }

  // additive = multiplicative { ("+"|"-") multiplicative }   (left-assoc)
  private Expr additive() {
    Expr left = multiplicative();
    while (check(TokenKind.PLUS) || check(TokenKind.MINUS)) {
      Token op = advance();
      Expr right = multiplicative();
      left =
          new Expr.Arithmetic(
              op,
              op.kind() == TokenKind.PLUS ? Expr.ArithmeticOp.ADD : Expr.ArithmeticOp.SUBTRACT,
              left,
              right);
    }
    return left;
  }

  // multiplicative = unary { ("*"|"/") unary }   (left-assoc)
  private Expr multiplicative() {
    Expr left = unary();
    while (check(TokenKind.STAR) || check(TokenKind.SLASH)) {
      Token op = advance();
      Expr right = unary();
      left =
          new Expr.Arithmetic(
              op,
              op.kind() == TokenKind.STAR ? Expr.ArithmeticOp.MULTIPLY : Expr.ArithmeticOp.DIVIDE,
              left,
              right);
    }
    return left;
  }

  // unary = "-" unary | postfix
  private Expr unary() {
    if (check(TokenKind.MINUS)) {
      Token op = advance();
      // A chain of minus signs recurses here without passing through expression(), so it needs
      // its own guard: `-----…-1` is as effective a stack bomb as nested parentheses.
      enterNesting();
      try {
        return new Expr.Negate(op, unary());
      } finally {
        depth--;
      }
    }
    Expr operand = postfix();
    if (operand == null) {
      throw error(peek(), "an expression");
    }
    return operand;
  }

  // postfix = primary { "." IDENT | "[" expression "]" }
  private Expr postfix() {
    enterNesting();
    try {
      return postfixInner();
    } finally {
      depth--;
    }
  }

  private Expr postfixInner() {
    Expr target = primary();
    if (target == null) {
      return null;
    }
    while (true) {
      if (check(TokenKind.DOT)) {
        Token dot = advance();
        Token name = expectFieldName();
        target = new Expr.Field(dot, target, name.text());
      } else if (check(TokenKind.LBRACKET)) {
        Token bracket = advance();
        Expr predicate = expression();
        expect(TokenKind.RBRACKET, "']' to close the filter");
        target = new Expr.Filter(bracket, target, predicate);
      } else {
        return target;
      }
    }
  }

  /**
   * A field name after a dot. Reserved words are allowed here — {@code rule.CODE.out} and {@code
   * scorecard.CODE.reasons} are ordinary postfix chains, and a fact field could legitimately be
   * spelled like a keyword.
   */
  private Token expectFieldName() {
    Token token = peek();
    if (token.kind() == TokenKind.IDENT || isReservedWord(token.kind())) {
      return advance();
    }
    throw error(token, "a field name after '.'");
  }

  // primary = literal | listLiteral | call | IDENT | "(" expression ")"
  private Expr primary() {
    Token token = peek();
    switch (token.kind()) {
      case INTEGER:
        advance();
        return new Expr.IntLit(token, parseIntegerLiteral(token));
      case DECIMAL:
        advance();
        return new Expr.DecimalLit(token, new BigDecimal(stripSeparators(token.text())));
      case STRING:
        advance();
        return new Expr.StringLit(token, token.text());
      case TRUE:
        advance();
        return new Expr.BoolLit(token, true);
      case FALSE:
        advance();
        return new Expr.BoolLit(token, false);
      case IDENT:
        advance();
        if (check(TokenKind.LPAREN)) {
          return call(token);
        }
        return new Expr.Ref(token, token.text());
      case LBRACKET:
        return bracketedTargetOrRange();
      case LPAREN:
        advance();
        Expr grouped = expression();
        expect(TokenKind.RPAREN, "')' to close the parenthesised expression");
        return grouped;
      case MISSING:
        // `missing` is a unary test, valid only in a decision-table cell or a scorecard bin
        // (§3.3). In an expression, isMissing(x) is the way to ask.
        throw error(
            token, "'missing' is a decision-table test, not an expression; use isMissing(x)");
      default:
        if (REFERENCE_ROOTS.contains(token.kind())) {
          advance();
          return new Expr.Ref(token, token.text());
        }
        return null;
    }
  }

  private Expr call(Token name) {
    expect(TokenKind.LPAREN, "'('");
    List<Expr> arguments = new ArrayList<>();
    if (!check(TokenKind.RPAREN)) {
      arguments.add(expression());
      while (check(TokenKind.COMMA)) {
        advance();
        arguments.add(expression());
      }
    }
    expect(TokenKind.RPAREN, "')' to close the argument list");
    return new Expr.Call(name, name.text(), arguments);
  }

  private long parseIntegerLiteral(Token token) {
    String digits = stripSeparators(token.text());
    try {
      return Long.parseLong(digits);
    } catch (NumberFormatException overflow) {
      // BEL-012: a value above 2^63 − 1 is a compile error, not a wrapped long.
      throw error(token, "integer literal is larger than the Integer type allows (BEL-012)");
    }
  }

  private static String stripSeparators(String text) {
    return text.indexOf('_') < 0 ? text : text.replace("_", "");
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

  private static boolean isReservedWord(TokenKind kind) {
    return switch (kind) {
      case AND,
          OR,
          NOT,
          IN,
          BETWEEN,
          IF,
          THEN,
          ELSE,
          TRUE,
          FALSE,
          IT,
          AS_OF,
          PARAM,
          LIST,
          RULE,
          TABLE,
          SCORECARD,
          MISSING ->
          true;
      default -> false;
    };
  }

  private Token peek() {
    return tokens.get(position);
  }

  private Token peekAt(int ahead) {
    int index = Math.min(position + ahead, tokens.size() - 1);
    return tokens.get(index);
  }

  private boolean check(TokenKind kind) {
    return peek().kind() == kind;
  }

  private Token advance() {
    Token token = tokens.get(position);
    if (token.kind() != TokenKind.EOF) {
      position++;
    }
    return token;
  }

  private Token expect(TokenKind kind, String expected) {
    if (peek().kind() != kind) {
      throw error(peek(), expected);
    }
    return advance();
  }

  private BelSyntaxException error(Token token, String expected) {
    String found = token.kind() == TokenKind.EOF ? "end of expression" : "'" + token.text() + "'";
    return new BelSyntaxException(
        "expected " + expected + " but found " + found,
        token.line(),
        token.column(),
        token.start());
  }
}
