package dev.bre.conformance.failclosed;

import dev.bre.conformance.failclosed.FailClosedModel.Decision;
import dev.bre.conformance.failclosed.FailClosedModel.Engine;
import dev.bre.conformance.failclosed.FailClosedModel.Facts;
import dev.bre.conformance.failclosed.FailClosedModel.OnMissing;
import dev.bre.conformance.failclosed.FailClosedModel.Rule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

/**
 * LLD §13 property 2 — the fail-closed property — stated once, so the jqwik property run and the
 * harness self-check assert exactly the same thing.
 *
 * <p>The property: for policies whose components use only {@code onMissing ∈ {REFER, FAIL}} and
 * contain no {@code isMissing} or {@code coalesce}, <b>removing any fact never turns a non-APPROVE
 * decision into APPROVE</b>.
 *
 * <p>This is the most important invariant in the product. A fail-open engine approves loans because
 * data was absent — a credit loss and a regulatory finding at once. It is the defect D9 exists to
 * close, and Draft 1 had it.
 */
public final class FailClosedProperty {

  /** The fact names the generators draw from. */
  private static final List<String> FACT_NAMES =
      List.of("income", "emi", "age", "bureauScore", "indebtedness");

  private FailClosedProperty() {}

  /**
   * Asserts the property for one policy and fact snapshot: dropping each fact in turn must never
   * move a non-APPROVE decision to APPROVE.
   *
   * @param engine the engine under test
   * @param policy the rules to evaluate
   * @param facts the fact snapshot to evaluate, then erode one fact at a time
   * @throws AssertionError if removing a fact improves the decision to APPROVE
   */
  public static void check(Engine engine, List<Rule> policy, Facts facts) {
    Decision baseline = engine.decide(policy, facts);
    if (baseline == Decision.APPROVE) {
      // Only a non-APPROVE baseline can be "improved" into APPROVE.
      return;
    }
    for (String factName : facts.values().keySet()) {
      if (engine.decide(policy, facts.without(factName)) == Decision.APPROVE) {
        throw new AssertionError(
            "fail-open: removing fact '"
                + factName
                + "' turned "
                + baseline
                + " into APPROVE. policy="
                + policy
                + " facts="
                + facts.values());
      }
    }
  }

  /** Generates small policies of one to five single-fact rules. */
  public static Arbitrary<List<Rule>> policies() {
    Arbitrary<Rule> rule =
        Combinators.combine(
                Arbitraries.of(FACT_NAMES.toArray(String[]::new)),
                Arbitraries.longs().between(0L, 100_000L),
                Arbitraries.of(OnMissing.values()))
            .as(Rule::new);
    return rule.list().ofMinSize(1).ofMaxSize(5);
  }

  /**
   * Generates fact snapshots over a subset of {@link #FACT_NAMES}.
   *
   * <p>A set arbitrary, not {@code list().uniqueElements()}: over a five-element pool the latter
   * discards duplicates often enough that jqwik can report the property EXHAUSTED, which Gradle
   * renders as "skipped" — a property that never runs while the build stays green.
   */
  public static Arbitrary<Facts> facts() {
    Arbitrary<Set<String>> names =
        Arbitraries.of(FACT_NAMES.toArray(String[]::new)).set().ofMinSize(1).ofMaxSize(5);
    return names.flatMap(
        presentNames ->
            Arbitraries.longs()
                .between(0L, 100_000L)
                .list()
                .ofSize(presentNames.size())
                .map(values -> toFacts(presentNames, values)));
  }

  private static Facts toFacts(Set<String> presentNames, List<Long> values) {
    Map<String, Long> factValues = new LinkedHashMap<>();
    List<String> ordered = new ArrayList<>(presentNames);
    for (int i = 0; i < ordered.size(); i++) {
      factValues.put(ordered.get(i), values.get(i));
    }
    return new Facts(Map.copyOf(factValues));
  }
}
