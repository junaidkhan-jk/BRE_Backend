package dev.bre.engine.bel.parse;

import dev.bre.engine.bel.ast.Expr;
import java.util.List;

/**
 * A parsed decision-table cell or scorecard bin (LLD §3.3).
 *
 * <p>Unary tests are a separate little grammar from expressions, and the difference that matters is
 * how each one treats a <b>missing</b> input:
 *
 * <table>
 *   <caption>Matching a missing input</caption>
 *   <tr><th>Test</th><th>Missing input</th></tr>
 *   <tr><td>{@code -}</td><td><b>no match</b> — deliberately unlike FEEL</td></tr>
 *   <tr><td>{@code missing}</td><td>matches</td></tr>
 *   <tr><td>{@code -, missing}</td><td>matches</td></tr>
 *   <tr><td>anything else</td><td>no match</td></tr>
 * </table>
 *
 * <p>That {@code -} does <em>not</em> match a missing input is the fail-closed choice, and it is
 * why S5-01 ends in {@code onMissing} rather than quietly taking the catch-all row. A dash means
 * "any value", and a missing input has no value.
 */
public sealed interface UnaryTest {

  /** {@code -} — matches any <em>present</em> value, and never a missing one. */
  record AnyPresent() implements UnaryTest {
    /** The single instance. */
    public static final AnyPresent INSTANCE = new AnyPresent();
  }

  /** {@code missing} — matches only a missing input, never a present one. */
  record IsMissing() implements UnaryTest {
    /** The single instance. */
    public static final IsMissing INSTANCE = new IsMissing();
  }

  /**
   * {@code = e}, {@code < e}, {@code >= e} and so on, and a bare {@code e} meaning {@code = e}.
   *
   * @param op the comparison operator
   * @param operand the right-hand side
   */
  record Compare(Expr.CompareOp op, Expr operand) implements UnaryTest {}

  /**
   * A range, with the inclusivity the brackets state.
   *
   * @param low the lower bound
   * @param lowInclusive whether the lower bound is included
   * @param high the upper bound
   * @param highInclusive whether the upper bound is included
   */
  record Range(Expr low, boolean lowInclusive, Expr high, boolean highInclusive)
      implements UnaryTest {}

  /**
   * {@code in target} or {@code not in target}.
   *
   * @param target the list, range or list-valued reference
   * @param negated whether the membership is negated
   */
  record In(Expr target, boolean negated) implements UnaryTest {}

  /**
   * Several tests separated by commas, which is an OR.
   *
   * <p>Matches a missing input only if one of its members is {@code missing} — so {@code -,
   * missing} matches everything, while {@code < 5, > 10} matches no missing input.
   *
   * @param alternatives the tests, in declaration order
   */
  record AnyOf(List<UnaryTest> alternatives) implements UnaryTest {
    /** Creates a disjunction, copying the list. */
    public AnyOf {
      alternatives = List.copyOf(alternatives);
    }
  }
}
