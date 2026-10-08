package dev.bre.engine.bel.ast;

import dev.bre.engine.bel.lex.Token;
import java.math.BigDecimal;
import java.util.List;

/**
 * A parsed BEL expression (LLD §3.2, M1-02).
 *
 * <p>Every node carries the {@link Token} it started at, so a diagnostic can point at the exact
 * place in the author's text. That is the whole reason this is a hand-written parser: a business
 * analyst writing a policy in the studio needs to be told where their expression is wrong.
 *
 * <p>The tree is a faithful parse, not a simplified one — no constant folding, no reassociation.
 * Lowering to the executable closure tree is a separate pass (M1-04/M1-05), which keeps the parse
 * honest and makes the diagnostics in between meaningful.
 */
public sealed interface Expr {

  /** Returns the token this expression starts at, for diagnostics. */
  Token token();

  /** An integer literal. Underscores have already been stripped by the lexer's text. */
  record IntLit(Token token, long value) implements Expr {}

  /** A decimal literal, held exactly. Never a {@code double}. */
  record DecimalLit(Token token, BigDecimal value) implements Expr {}

  /** A string literal, NFC-normalised at lex time. */
  record StringLit(Token token, String value) implements Expr {}

  /** {@code true} or {@code false}. */
  record BoolLit(Token token, boolean value) implements Expr {}

  /**
   * A bare identifier, or one of the reserved roots a namespaced reference hangs off ({@code asOf},
   * {@code param}, {@code list}, {@code rule}, {@code table}, {@code scorecard}, {@code it}). The
   * binder gives these meaning (§3.8); the parser only records the name.
   */
  record Ref(Token token, String name) implements Expr {}

  /** Field access, {@code r.f} — or a step in a namespaced chain such as {@code param.X}. */
  record Field(Token token, Expr target, String name) implements Expr {}

  /**
   * A filter, {@code l[p]}, where {@code it} is bound to each element.
   *
   * <p>An integer index is <b>not</b> this node: {@code l[0]} is the compile error BEL-009, because
   * positional access into a fact list is almost always a bug. Authors use {@code first(...)}.
   */
  record Filter(Token token, Expr target, Expr predicate) implements Expr {}

  /** A call to one of the closed function registry's functions (§3.9). */
  record Call(Token token, String function, List<Expr> arguments) implements Expr {
    /** Creates a call, copying the argument list. */
    public Call {
      arguments = List.copyOf(arguments);
    }
  }

  /** A list literal, {@code [a, b, c]}. Elements may evaluate to missing. */
  record ListLit(Token token, List<Expr> elements) implements Expr {
    /** Creates a list literal, copying the element list. */
    public ListLit {
      elements = List.copyOf(elements);
    }
  }

  /** Unary minus. */
  record Negate(Token token, Expr operand) implements Expr {}

  /** {@code not a}. Right-associative prefix. */
  record Not(Token token, Expr operand) implements Expr {}

  /** Arithmetic: {@code + - * /}. Left-associative. */
  record Arithmetic(Token token, ArithmeticOp op, Expr left, Expr right) implements Expr {}

  /** Comparison: {@code = != < <= > >=}. Non-associative, so {@code a < b < c} never parses. */
  record Comparison(Token token, CompareOp op, Expr left, Expr right) implements Expr {}

  /** {@code and} / {@code or}. Left-associative, short-circuiting, three-valued (§3.5). */
  record Logical(Token token, LogicalOp op, Expr left, Expr right) implements Expr {}

  /** {@code x in target} and {@code x not in target}. */
  record In(Token token, Expr value, Expr target, boolean negated) implements Expr {}

  /** {@code x between a and b}, and its negation. Both bounds are {@code additive}. */
  record Between(Token token, Expr value, Expr low, Expr high, boolean negated) implements Expr {}

  /**
   * {@code if c then a else b}.
   *
   * <p>When {@code c} is unknown the result is <b>missing</b>, never the else branch — the
   * fail-closed choice (§3.5).
   */
  record If(Token token, Expr condition, Expr thenBranch, Expr elseBranch) implements Expr {}

  /**
   * A range, {@code [a..b]} / {@code (a..b)} / {@code [a..b)} / {@code (a..b]}. Only valid as an
   * {@code in} target or a unary test.
   */
  record Range(Token token, Expr low, boolean lowInclusive, Expr high, boolean highInclusive)
      implements Expr {}

  /** Arithmetic operators. */
  enum ArithmeticOp {
    /** {@code +} */
    ADD,
    /** {@code -} */
    SUBTRACT,
    /** {@code *} */
    MULTIPLY,
    /** {@code /} — always yields Decimal, and zero divisor is an ERROR. */
    DIVIDE
  }

  /** Comparison operators. */
  enum CompareOp {
    /** {@code =} */
    EQ,
    /** {@code !=} */
    NE,
    /** {@code <} */
    LT,
    /** {@code <=} */
    LE,
    /** {@code >} */
    GT,
    /** {@code >=} */
    GE
  }

  /** Logical connectives. */
  enum LogicalOp {
    /** {@code and} */
    AND,
    /** {@code or} */
    OR
  }
}
