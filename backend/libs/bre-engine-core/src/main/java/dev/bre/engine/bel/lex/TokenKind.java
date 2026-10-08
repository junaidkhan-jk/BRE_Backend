package dev.bre.engine.bel.lex;

/** Token classes of BEL (LLD §3.1). */
public enum TokenKind {
  /** {@code [A-Za-z_][A-Za-z0-9_]*}, at most 64 characters, case-sensitive. */
  IDENT,
  /** {@code DIGITS}, where underscores are visual separators only. */
  INTEGER,
  /** {@code DIGITS "." DIGITS}. No exponents, no leading {@code +}. */
  DECIMAL,
  /** A double-quoted string. Single quotes are not BEL delimiters — PSF uses them for YAML. */
  STRING,

  // Reserved words (§3.1). Lexed as keywords so a misuse is a parse error, not a bad reference.
  /** {@code and} */
  AND,
  /** {@code or} */
  OR,
  /** {@code not} */
  NOT,
  /** {@code in} */
  IN,
  /** {@code between} */
  BETWEEN,
  /** {@code if} */
  IF,
  /** {@code then} */
  THEN,
  /** {@code else} */
  ELSE,
  /** {@code true} */
  TRUE,
  /** {@code false} */
  FALSE,
  /** {@code it} — the current element inside a filter. */
  IT,
  /** {@code asOf} — the request's evaluation date. The engine's only notion of "today". */
  AS_OF,
  /** {@code param} */
  PARAM,
  /** {@code list} */
  LIST,
  /** {@code rule} */
  RULE,
  /** {@code table} */
  TABLE,
  /** {@code scorecard} */
  SCORECARD,
  /** {@code missing} — a unary test, never an expression literal. */
  MISSING,

  // Punctuation
  /** {@code (} */
  LPAREN,
  /** {@code )} */
  RPAREN,
  /** {@code [} */
  LBRACKET,
  /** {@code ]} */
  RBRACKET,
  /** {@code ,} */
  COMMA,
  /** {@code .} */
  DOT,
  /** {@code ..} — a range. Lexed greedily, so {@code 2..3} is {@code 2}, {@code ..}, {@code 3}. */
  DOT_DOT,

  // Operators
  /** {@code +} */
  PLUS,
  /** {@code -} */
  MINUS,
  /** {@code *} */
  STAR,
  /** {@code /} */
  SLASH,
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
  GE,

  /** End of input. */
  EOF
}
