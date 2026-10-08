package dev.bre.engine.bel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bre.engine.bel.parse.Parser;
import org.junit.jupiter.api.Test;

/**
 * The parser's nesting limit (§3.2).
 *
 * <p>Found by Jazzer rather than by review: a 3 KB input of nested {@code [} and {@code (} produced
 * a {@code StackOverflowError} in 54 seconds of fuzzing. A recursive-descent parser costs a stack
 * frame per level of nesting, and BEL text arrives from the studio — so in a multi-tenant control
 * plane this was a denial of service one tenant could inflict on a shared compile path.
 */
class ParserDepthTest {

  private static String nest(String open, String close, int depth, String core) {
    return open.repeat(depth) + core + close.repeat(depth);
  }

  @Test
  void expressionsNestedToTheLimitStillParse() {
    assertThatCode(
            () ->
                Parser.parseExpression(
                    nest("(", ")", Parser.MAX_NESTING_DEPTH - 1, "application.a")))
        .doesNotThrowAnyException();
  }

  @Test
  void parenthesesNestedPastTheLimitAreASyntaxErrorNotAStackOverflow() {
    assertThatThrownBy(() -> Parser.parseExpression(nest("(", ")", 5_000, "application.a")))
        .isInstanceOf(BelSyntaxException.class)
        .hasMessageContaining("nested no deeper than");
  }

  /** A chain of minus signs recurses without passing through {@code expression()}. */
  @Test
  void aChainOfUnaryMinusesIsBounded() {
    assertThatThrownBy(() -> Parser.parseExpression("-".repeat(5_000) + "1"))
        .isInstanceOf(BelSyntaxException.class)
        .hasMessageContaining("nested no deeper than");
  }

  @Test
  void nestedFiltersAreBounded() {
    // The shape the fuzzer actually found: repeated filter brackets.
    String source = "application.a" + "[it.b".repeat(5_000) + "]".repeat(5_000);
    assertThatThrownBy(() -> Parser.parseExpression(source)).isInstanceOf(BelSyntaxException.class);
  }

  @Test
  void nestedListLiteralsAreBounded() {
    assertThatThrownBy(() -> Parser.parseExpression(nest("[", "]", 5_000, "1")))
        .isInstanceOf(BelSyntaxException.class);
  }

  /**
   * The limit has to clear real authoring with room to spare. The deepest expression in the §3.14
   * reference policy is a filtered projection inside an aggregate, which nests eight levels.
   */
  @Test
  void theLimitIsFarAboveRealisticAuthoring() {
    assertThat(Parser.MAX_NESTING_DEPTH).isGreaterThanOrEqualTo(24);

    assertThatCode(
            () ->
                Parser.parseExpression(
                    "sum(member.bureauReports.tradelines[it.accountType in list.UNSECURED"
                        + " and it.status in list.OPEN].currentBalance)"))
        .as("the deepest expression the specification actually contains")
        .doesNotThrowAnyException();
  }

  /** The diagnostic still carries a position, which an editor needs to place a marker. */
  @Test
  void theErrorCarriesAPosition() {
    assertThatThrownBy(() -> Parser.parseExpression(nest("(", ")", 5_000, "application.a")))
        .isInstanceOf(BelSyntaxException.class)
        .satisfies(
            e -> {
              BelSyntaxException syntax = (BelSyntaxException) e;
              assertThat(syntax.line()).isGreaterThanOrEqualTo(1);
              assertThat(syntax.column()).isGreaterThanOrEqualTo(1);
            });
  }
}
