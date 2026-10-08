package dev.bre.engine.bel.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.bre.engine.bel.BelSyntaxException;
import dev.bre.engine.bel.parse.Parser;
import dev.bre.engine.bel.parse.UnaryTestParser;

/**
 * Coverage-guided fuzzing of the BEL lexer and parser (M1-02's exit criterion).
 *
 * <p><b>The property under test is narrow and deliberate: the parser may reject anything, but it
 * may never fail in an unplanned way.</b> Every malformed input must produce {@link
 * BelSyntaxException} — not a {@code StringIndexOutOfBoundsException} from a lexer that walked off
 * the end, not a {@code StackOverflowError} from unbounded recursion on nested parentheses, not a
 * {@code NullPointerException} from a token that was not there. Those are the three failures a
 * hand-written recursive-descent parser actually has, and all three are reachable from text.
 *
 * <p>Why it matters here more than in most parsers: BEL text arrives from the studio, and in a
 * multi-tenant control plane a crash in the compile path is a denial of service one tenant can
 * inflict on a shared service. §9 treats policy source as untrusted input, and this is the test
 * that makes that true rather than aspirational.
 *
 * <p><b>This does not evaluate.</b> Evaluation needs facts, a fuel budget and a context; fuzzing it
 * is a separate target and a separate property. Parsing is where untrusted bytes first meet the
 * engine.
 *
 * <h2>Running it</h2>
 *
 * <p>Under {@code ./gradlew test} this runs as a plain regression test over the seed corpus and any
 * previously-found crash — fast, deterministic, and what CI needs on every change. Actual fuzzing
 * needs the agent and a time budget:
 *
 * <pre>{@code
 * JAZZER_FUZZ=1 ./gradlew :libs:bre-engine-core:test --tests '*BelParserFuzzTest*'
 * }</pre>
 *
 * <p>M1-02's criterion is one hour clean, which the two targets at 30 minutes each satisfy
 * together. {@code maxDuration} is ignored outside fuzzing mode, so an ordinary build still takes
 * seconds. A crash is written to the resource directory beside this class, becomes a regression
 * case on the next ordinary run, and must be fixed rather than deleted.
 */
class BelParserFuzzTest {

  /**
   * Fuzzes the expression parser.
   *
   * @param data the fuzzer's bytes, consumed as a string
   */
  @FuzzTest(maxDuration = "30m")
  void theExpressionParserOnlyEverThrowsASyntaxError(FuzzedDataProvider data) {
    String source = data.consumeRemainingAsString();
    try {
      Parser.parseExpression(source);
    } catch (BelSyntaxException expected) {
      // The one permitted outcome for malformed input. A position is part of the contract: a
      // diagnostic with no location is not actionable in an editor.
      if (expected.line() < 1 || expected.column() < 1) {
        throw new AssertionError(
            "BEL-001 must carry a 1-based position; got "
                + expected.line()
                + ":"
                + expected.column());
      }
    }
    // Any other Throwable propagates and the fuzzer reports it. That is the point: this test does
    // not catch RuntimeException, because catching it is what would hide the defect being hunted.
  }

  /**
   * Fuzzes the unary-test parser, which reads decision-table cells and scorecard bins.
   *
   * <p>Separate from the expression parser because it is a separate parser with its own entry
   * point, its own range and membership syntax, and its own history: the balanced-token bug that
   * broke every {@code in [...]} cell lived here and not in the expression grammar.
   *
   * @param data the fuzzer's bytes, consumed as a string
   */
  @FuzzTest(maxDuration = "30m")
  void theUnaryTestParserOnlyEverThrowsASyntaxError(FuzzedDataProvider data) {
    String cell = data.consumeRemainingAsString();
    try {
      UnaryTestParser.parse(cell);
    } catch (BelSyntaxException expected) {
      if (expected.line() < 1 || expected.column() < 1) {
        throw new AssertionError(
            "BEL-001 must carry a 1-based position; got "
                + expected.line()
                + ":"
                + expected.column());
      }
    }
  }
}
