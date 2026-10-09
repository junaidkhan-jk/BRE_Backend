package dev.bre.engine.bel.parse;

import dev.bre.engine.bel.ast.Expr;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parsed expressions and unary tests, parsed once and reused (LLD §4.1 stage 9).
 *
 * <p><b>Why this exists, measured rather than assumed.</b> Every expression on the decision path
 * was being re-parsed on every decision: a rule's {@code check}, its {@code measure}, each table
 * input, and — worst — every table cell and scorecard bin, once per row per entity. The first JMH
 * run on this engine put parsing at <b>1.07 µs</b> against evaluation's <b>0.44 µs</b>, so a
 * 100-rule decision was spending about half its 212 µs re-deriving syntax trees it had already
 * built. A 2,000-row decision table over a 40-member group would parse over a million unary tests
 * for one decision.
 *
 * <p>That is also why this is a parse cache and not the closure-tree rewrite §4.1 stage 9 describes
 * in full. The benchmark says dispatch is not the bottleneck — evaluation of a real affordability
 * expression is already 0.44 µs, and a 100-rule decision already meets the LLD's p99 ≤ 0.5 ms
 * target. Lowering the remaining tree-walk to closures is a further, smaller win that the numbers
 * do not yet justify, and it would be a rewrite of the one component 90 conformance vectors pin.
 * Measure first, then decide: the benchmark is committed so the decision can be revisited with
 * evidence rather than taste.
 *
 * <p><b>Why caching is safe here.</b> Parsing is a pure function of the source text — no clock, no
 * locale, no context — so a cached tree is indistinguishable from a freshly parsed one, and D8's
 * determinism is unaffected. The AST is immutable, so sharing one across threads and across
 * decisions is safe. A syntax error is not cached: it is thrown every time, because caching a
 * failure would make the second diagnostic depend on the first.
 *
 * <p><b>The bound matters.</b> A policy version is immutable and its expressions are finite, but a
 * long-lived pod serves many versions over time, and an unbounded cache keyed by source text is a
 * slow memory leak that looks like a working optimisation for weeks. The cache is capped and clears
 * wholesale when full rather than evicting one entry at a time: an LRU needs per-access bookkeeping
 * on the hot path, and for this access pattern — a working set of one or two policy versions,
 * overwhelmingly hits — a rare full clear costs one re-parse per live expression and no per-read
 * cost at all.
 */
public final class Compiled {

  /**
   * How many distinct expression sources to hold.
   *
   * <p>Sized for several policy versions at once: §2.2 caps a policy at 2,000 table rows and 16
   * inputs, so one large version can contribute tens of thousands of distinct cell sources. 65,536
   * holds a realistic working set with room for a rollout serving two versions side by side.
   */
  public static final int MAX_ENTRIES = 65_536;

  private static final Map<String, Expr> EXPRESSIONS = new ConcurrentHashMap<>();
  private static final Map<String, UnaryTest> UNARY_TESTS = new ConcurrentHashMap<>();

  private Compiled() {}

  /**
   * Returns the parsed form of an expression, parsing it on first use.
   *
   * @param source the BEL expression text
   * @return the syntax tree
   * @throws dev.bre.engine.bel.BelSyntaxException if the source does not parse; never cached
   */
  public static Expr expression(String source) {
    Expr cached = EXPRESSIONS.get(source);
    if (cached != null) {
      return cached;
    }
    // Parsed outside computeIfAbsent on purpose. A syntax error thrown from inside a mapping
    // function on a ConcurrentHashMap propagates while holding the bin lock, and the entry is
    // simply absent afterwards — correct, but it makes a throwing parse take a lock that every
    // other thread hashing to that bin waits on. Parsing first and putting after keeps the
    // failure path lock-free. A duplicate parse under a race is harmless: the trees are equal and
    // immutable.
    Expr parsed = Parser.parseExpression(source);
    if (EXPRESSIONS.size() < MAX_ENTRIES) {
      EXPRESSIONS.put(source, parsed);
    } else {
      clearAndSeed(EXPRESSIONS, source, parsed);
    }
    return parsed;
  }

  /**
   * Returns the parsed form of a unary test — a decision-table cell or a scorecard bin.
   *
   * <p>Separate from {@link #expression} because they are separate grammars with separate parsers
   * (§3.3), and because these are the ones parsed most often: a cell is parsed once per row per
   * entity, so a table's cells dominate the parse cost of a per-entity component.
   *
   * @param source the unary-test text
   * @return the parsed test
   * @throws dev.bre.engine.bel.BelSyntaxException if the source does not parse; never cached
   */
  public static UnaryTest unaryTest(String source) {
    UnaryTest cached = UNARY_TESTS.get(source);
    if (cached != null) {
      return cached;
    }
    UnaryTest parsed = UnaryTestParser.parse(source);
    if (UNARY_TESTS.size() < MAX_ENTRIES) {
      UNARY_TESTS.put(source, parsed);
    } else {
      clearAndSeed(UNARY_TESTS, source, parsed);
    }
    return parsed;
  }

  private static <T> void clearAndSeed(Map<String, T> cache, String source, T parsed) {
    cache.clear();
    cache.put(source, parsed);
  }

  /**
   * Empties both caches.
   *
   * <p>For tests that measure parse counts, and for an operator who has just deployed a version and
   * wants the memory back. Never needed for correctness: an entry can only ever be the parse of its
   * own key.
   */
  public static void clear() {
    EXPRESSIONS.clear();
    UNARY_TESTS.clear();
  }

  /**
   * How many expressions are currently cached.
   *
   * @return the count
   */
  public static int cachedExpressions() {
    return EXPRESSIONS.size();
  }

  /**
   * How many unary tests are currently cached.
   *
   * @return the count
   */
  public static int cachedUnaryTests() {
    return UNARY_TESTS.size();
  }
}
