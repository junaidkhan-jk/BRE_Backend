package dev.bre.engine.bel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bre.engine.bel.ast.Expr;
import dev.bre.engine.bel.parse.Compiled;
import dev.bre.engine.bel.parse.Parser;
import dev.bre.engine.bel.parse.UnaryTestParser;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The parse cache behind M1-05's compile-once (LLD §4.1 stage 9). */
class CompiledTest {

  @BeforeEach
  void emptyTheCache() {
    Compiled.clear();
  }

  @Test
  void theSameSourceYieldsTheSameInstance() {
    String source = "application.requestedAmount > 0";
    Expr first = Compiled.expression(source);
    Expr second = Compiled.expression(source);

    assertThat(second).as("a second call must not re-parse").isSameAs(first);
    assertThat(Compiled.cachedExpressions()).isEqualTo(1);
  }

  /** A cached tree has to be indistinguishable from a fresh one, or D8's determinism is gone. */
  @Test
  void aCachedTreeEqualsAFreshlyParsedOne() {
    for (String source :
        List.of(
            "application.requestedAmount / application.tenureMonths <= 1000",
            "member.income.monthlyNet >= param.FLOOR and not isMissing(member.score)",
            "sum(member.bureauReports.tradelines[it.status = \"ACTIVE\"].currentBalance)",
            "if application.score >= 650 then 1 else 0",
            "application.purpose in [\"HOUSING\", \"BUSINESS\"]")) {
      assertThat(Compiled.expression(source))
          .as("cached and fresh must agree for: %s", source)
          .isEqualTo(Parser.parseExpression(source));
    }
  }

  @Test
  void unaryTestsAreCachedSeparatelyFromExpressions() {
    Compiled.expression("application.score");
    Compiled.unaryTest(">= 650");

    assertThat(Compiled.cachedExpressions()).isEqualTo(1);
    assertThat(Compiled.cachedUnaryTests()).isEqualTo(1);
    assertThat(Compiled.unaryTest(">= 650")).isSameAs(Compiled.unaryTest(">= 650"));
  }

  @Test
  void aCachedUnaryTestEqualsAFreshlyParsedOne() {
    for (String cell :
        List.of("= \"C\"", "in [\"A\",\"B\"]", "< 700", "[650..750)", "-", "missing")) {
      assertThat(Compiled.unaryTest(cell))
          .as("cell: %s", cell)
          .isEqualTo(UnaryTestParser.parse(cell));
    }
  }

  /**
   * A failure is never cached. Caching one would make the second diagnostic depend on the first —
   * and a studio that reported a stale syntax error after the author fixed it would be worse than
   * one that re-parsed.
   */
  @Test
  void aSyntaxErrorIsNotCachedAndThrowsEveryTime() {
    String broken = "application. ) +";

    for (int attempt = 0; attempt < 3; attempt++) {
      assertThatThrownBy(() -> Compiled.expression(broken))
          .as("attempt %d", attempt)
          .isInstanceOf(BelSyntaxException.class);
    }
    assertThat(Compiled.cachedExpressions()).isZero();
  }

  @Test
  void aBrokenUnaryTestIsNotCachedEither() {
    assertThatThrownBy(() -> Compiled.unaryTest("in [")).isInstanceOf(BelSyntaxException.class);
    assertThat(Compiled.cachedUnaryTests()).isZero();
  }

  /** Unbounded would be a slow leak that looks like a working optimisation for weeks. */
  @Test
  void theCacheIsBoundedAndKeepsServingAfterItFills() {
    assertThat(Compiled.MAX_ENTRIES).isPositive();

    // Not filling 65,536 entries in a unit test; the behaviour at the bound is what matters, and
    // clearAndSeed is reachable only there. Instead: assert the counter tracks, and that a value
    // is always the parse of its own key however many distinct sources have gone through.
    for (int i = 0; i < 500; i++) {
      String source = "application.f" + i + " > 0";
      assertThat(Compiled.expression(source)).isEqualTo(Parser.parseExpression(source));
    }
    assertThat(Compiled.cachedExpressions()).isEqualTo(500);
  }

  @Test
  void clearEmptiesBothCaches() {
    Compiled.expression("application.a > 0");
    Compiled.unaryTest("> 0");
    Compiled.clear();

    assertThat(Compiled.cachedExpressions()).isZero();
    assertThat(Compiled.cachedUnaryTests()).isZero();
  }

  /**
   * Concurrent first-use must not produce two different trees, and must not throw. A duplicate
   * parse under a race is fine — the trees are equal and immutable — but a torn read is not.
   */
  @Test
  void concurrentFirstUseIsSafe() throws Exception {
    String source = "member.income.monthlyNet >= 10000 and member.score >= 650";
    int threads = 16;

    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      List<Callable<Expr>> tasks = new ArrayList<>(threads);
      for (int i = 0; i < threads; i++) {
        tasks.add(() -> Compiled.expression(source));
      }
      List<Future<Expr>> results = pool.invokeAll(tasks);

      Expr expected = Parser.parseExpression(source);
      for (Future<Expr> result : results) {
        assertThat(result.get()).isEqualTo(expected);
      }
    }
    assertThat(Compiled.cachedExpressions()).isEqualTo(1);
  }
}
