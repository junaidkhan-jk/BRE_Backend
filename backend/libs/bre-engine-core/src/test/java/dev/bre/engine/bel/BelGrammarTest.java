package dev.bre.engine.bel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.bre.engine.bel.ast.Expr;
import dev.bre.engine.bel.lex.Lexer;
import dev.bre.engine.bel.lex.Token;
import dev.bre.engine.bel.lex.TokenKind;
import dev.bre.engine.bel.parse.Parser;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Lexer and parser edge cases from LLD §3.1–3.2 that the conformance vectors do not reach.
 *
 * <p>The vectors exercise the grammar through whole expressions, which covers the common path well
 * and the awkward corners not at all — a vector never writes {@code 2..3}, never chains a
 * comparison, and never overflows an integer literal. Those are exactly the places a hand-written
 * parser goes wrong quietly, so they are pinned here.
 */
class BelGrammarTest {

  private static List<TokenKind> kinds(String source) {
    return Lexer.tokenize(source).stream().map(Token::kind).toList();
  }

  @Nested
  @DisplayName("§3.1 lexical structure")
  class Lexing {

    @Test
    @DisplayName("a '.' joins a number only when a digit follows, so 2..3 is 2 .. 3")
    void dotDotIsNotPartOfANumber() {
      assertThat(kinds("2..3"))
          .as("if the lexer were greedy about '.', a range over integers could not be written")
          .containsExactly(TokenKind.INTEGER, TokenKind.DOT_DOT, TokenKind.INTEGER, TokenKind.EOF);
      assertThat(kinds("2.5")).containsExactly(TokenKind.DECIMAL, TokenKind.EOF);
      assertThat(kinds("[1..10]"))
          .containsExactly(
              TokenKind.LBRACKET,
              TokenKind.INTEGER,
              TokenKind.DOT_DOT,
              TokenKind.INTEGER,
              TokenKind.RBRACKET,
              TokenKind.EOF);
    }

    @Test
    @DisplayName("underscores in numbers are visual separators only")
    void numericSeparators() {
      Expr parsed = Parser.parseExpression("200_000");
      assertThat(parsed).isInstanceOf(Expr.IntLit.class);
      assertThat(((Expr.IntLit) parsed).value()).isEqualTo(200_000L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1__0", "10_"})
    @DisplayName("a misplaced underscore in a NUMBER is a lexical error, not a different number")
    void misplacedSeparatorsAreRejected(String source) {
      assertThatExceptionOfType(BelSyntaxException.class).isThrownBy(() -> Lexer.tokenize(source));
    }

    @Test
    @DisplayName("a leading underscore is a legal identifier, not a malformed number")
    void leadingUnderscoreIsAnIdentifier() {
      // §3.1 defines an identifier as [A-Za-z_][A-Za-z0-9_]*, so _10 is a name. Only a
      // separator inside a numeric literal is constrained.
      assertThat(kinds("_10")).containsExactly(TokenKind.IDENT, TokenKind.EOF);
    }

    @Test
    @DisplayName("reserved words lex as keywords, not identifiers")
    void reservedWords() {
      assertThat(kinds("if x then true else false"))
          .containsExactly(
              TokenKind.IF,
              TokenKind.IDENT,
              TokenKind.THEN,
              TokenKind.TRUE,
              TokenKind.ELSE,
              TokenKind.FALSE,
              TokenKind.EOF);
    }

    @Test
    @DisplayName("an unterminated string is an error, positioned at the opening quote")
    void unterminatedString() {
      assertThatExceptionOfType(BelSyntaxException.class)
          .isThrownBy(() -> Lexer.tokenize("\"abc"))
          .satisfies(
              e -> {
                assertThat(e.getMessage()).contains("unterminated");
                assertThat(e.line()).isEqualTo(1);
                assertThat(e.column()).isEqualTo(1);
              });
    }

    @Test
    @DisplayName("string escapes are decoded; single quotes are not string delimiters")
    void stringEscapes() {
      Expr parsed = Parser.parseExpression("\"a\\tb\\u0041\"");
      assertThat(((Expr.StringLit) parsed).value()).isEqualTo("a\tbA");
      assertThatExceptionOfType(BelSyntaxException.class)
          .as("PSF uses single quotes for YAML quoting, so BEL cannot claim them")
          .isThrownBy(() -> Lexer.tokenize("'abc'"));
    }

    @Test
    @DisplayName("errors carry a line and column, which is the point of a hand-written lexer")
    void errorsArePositioned() {
      assertThatExceptionOfType(BelSyntaxException.class)
          .isThrownBy(() -> Lexer.tokenize("a +\n  @"))
          .satisfies(
              e -> {
                assertThat(e.line()).isEqualTo(2);
                assertThat(e.column()).isEqualTo(3);
              });
    }
  }

  @Nested
  @DisplayName("§3.2 grammar")
  class Parsing {

    @Test
    @DisplayName("comparison is non-associative, so a < b < c is a syntax error")
    void chainedComparisonIsRejected() {
      assertThatExceptionOfType(BelSyntaxException.class)
          .isThrownBy(() -> Parser.parseExpression("a < b < c"))
          .satisfies(e -> assertThat(e.getMessage()).contains("not associative"));
    }

    @Test
    @DisplayName("both bounds of between are additive, so 'x between 1 and 5 and y' groups left")
    void betweenBindsTighterThanAnd() {
      Expr parsed = Parser.parseExpression("x between 1 and 5 and y");
      assertThat(parsed)
          .as("if between swallowed the trailing 'and y', the guard would change meaning")
          .isInstanceOf(Expr.Logical.class);
      Expr.Logical and = (Expr.Logical) parsed;
      assertThat(and.op()).isEqualTo(Expr.LogicalOp.AND);
      assertThat(and.left()).isInstanceOf(Expr.Between.class);
      assertThat(and.right()).isInstanceOf(Expr.Ref.class);
    }

    @Test
    @DisplayName("precedence: or binds loosest, then and, then not, then comparison, then + then *")
    void precedence() {
      // a or b and not c = d + e * f  ==>  a or (b and (not (c = (d + (e * f)))))
      Expr parsed = Parser.parseExpression("a or b and not c = d + e * f");
      Expr.Logical or = (Expr.Logical) parsed;
      assertThat(or.op()).isEqualTo(Expr.LogicalOp.OR);
      Expr.Logical and = (Expr.Logical) or.right();
      assertThat(and.op()).isEqualTo(Expr.LogicalOp.AND);
      Expr.Not not = (Expr.Not) and.right();
      Expr.Comparison eq = (Expr.Comparison) not.operand();
      Expr.Arithmetic plus = (Expr.Arithmetic) eq.right();
      assertThat(plus.op()).isEqualTo(Expr.ArithmeticOp.ADD);
      assertThat(((Expr.Arithmetic) plus.right()).op()).isEqualTo(Expr.ArithmeticOp.MULTIPLY);
    }

    @Test
    @DisplayName("- and / are left-associative, which matters because division is not exact")
    void arithmeticIsLeftAssociative() {
      Expr.Arithmetic minus = (Expr.Arithmetic) Parser.parseExpression("a - b - c");
      assertThat(minus.left()).isInstanceOf(Expr.Arithmetic.class);
      assertThat(minus.right()).isInstanceOf(Expr.Ref.class);
    }

    @Test
    @DisplayName("the one lookahead point: '[' starts a list literal or a range")
    void bracketAmbiguity() {
      assertThat(Parser.parseExpression("x in [1, 2, 3]"))
          .extracting(e -> ((Expr.In) e).target())
          .isInstanceOf(Expr.ListLit.class);
      assertThat(Parser.parseExpression("x in [1..10]"))
          .extracting(e -> ((Expr.In) e).target())
          .isInstanceOf(Expr.Range.class);
      assertThat(Parser.parseExpression("x in []"))
          .extracting(e -> ((Expr.In) e).target())
          .isInstanceOf(Expr.ListLit.class);
    }

    @Test
    @DisplayName("range inclusivity is taken from the brackets, not assumed")
    void rangeInclusivity() {
      Expr.Range closed = (Expr.Range) ((Expr.In) Parser.parseExpression("x in [1..10]")).target();
      assertThat(closed.lowInclusive()).isTrue();
      assertThat(closed.highInclusive()).isTrue();

      Expr.Range halfOpen =
          (Expr.Range) ((Expr.In) Parser.parseExpression("x in [1..10)")).target();
      assertThat(halfOpen.lowInclusive()).isTrue();
      assertThat(halfOpen.highInclusive()).isFalse();
    }

    @Test
    @DisplayName("'not in' and 'not between' are comparison tails, not prefix negations")
    void negatedMembership() {
      assertThat(((Expr.In) Parser.parseExpression("x not in [1, 2]")).negated()).isTrue();
      assertThat(((Expr.Between) Parser.parseExpression("x not between 1 and 5")).negated())
          .isTrue();
    }

    @Test
    @DisplayName("an integer literal above the Integer range is BEL-012, not a wrapped long")
    void integerOverflowIsRejected() {
      assertThatExceptionOfType(BelSyntaxException.class)
          .isThrownBy(() -> Parser.parseExpression("99999999999999999999"))
          .satisfies(e -> assertThat(e.getMessage()).contains("BEL-012"));
    }

    @Test
    @DisplayName("'missing' is a decision-table test, and says so when used as an expression")
    void missingIsNotAnExpression() {
      assertThatExceptionOfType(BelSyntaxException.class)
          .isThrownBy(() -> Parser.parseExpression("x = missing"))
          .satisfies(e -> assertThat(e.getMessage()).contains("isMissing"));
    }

    @Test
    @DisplayName("every if needs an else, because there is nothing safe to fall through to")
    void ifRequiresElse() {
      assertThatExceptionOfType(BelSyntaxException.class)
          .isThrownBy(() -> Parser.parseExpression("if c then 1"))
          .satisfies(e -> assertThat(e.getMessage()).contains("else"));
    }

    @Test
    @DisplayName("namespaced references parse as postfix chains rooted at a reserved word")
    void namespacedReferences() {
      Expr parsed = Parser.parseExpression("param.FOIR_CAP");
      assertThat(parsed).isInstanceOf(Expr.Field.class);
      assertThat(((Expr.Field) parsed).name()).isEqualTo("FOIR_CAP");
      assertThat(((Expr.Ref) ((Expr.Field) parsed).target()).name()).isEqualTo("param");
    }

    @Test
    @DisplayName("a filter parses as a filter, and asOf is a value not an identifier")
    void filtersAndAsOf() {
      assertThat(Parser.parseExpression("member.tradelines[it.status = \"ACTIVE\"]"))
          .isInstanceOf(Expr.Filter.class);
      assertThat(Parser.parseExpression("asOf")).isInstanceOf(Expr.Ref.class);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "a +",
          "(a",
          "a in",
          "round(1, 2",
          "if then 1 else 2",
          ")",
          "a..b",
        })
    @DisplayName("malformed input is a positioned syntax error, never a silent partial parse")
    void malformedInputIsRejected(String source) {
      assertThatExceptionOfType(BelSyntaxException.class)
          .isThrownBy(() -> Parser.parseExpression(source))
          .satisfies(e -> assertThat(e.line()).isPositive());
    }
  }
}
