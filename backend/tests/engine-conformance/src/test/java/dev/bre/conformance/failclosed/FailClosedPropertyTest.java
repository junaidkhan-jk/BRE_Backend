package dev.bre.conformance.failclosed;

import dev.bre.conformance.failclosed.FailClosedModel.Facts;
import dev.bre.conformance.failclosed.FailClosedModel.Rule;
import java.util.List;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * LLD §13 property 2, run against the real engine at M1-09's exit criterion of 10⁶ cases.
 *
 * <p>The property: for policies whose components use only {@code onMissing ∈ {REFER, FAIL}} and
 * contain no {@code isMissing} or {@code coalesce}, removing any fact never turns a non-APPROVE
 * decision into APPROVE. It is the most important invariant in the product — a fail-open engine
 * approves loans <em>because</em> data was absent — and Draft 1 had the defect.
 *
 * <p>Each case decides once and then once more per present fact, so this is several million
 * evaluations. It takes about a minute. That is the whole cost of the exit criterion, and it buys
 * the one assurance worth a minute: no smaller number was kept alongside it, because a second
 * weaker copy of this property is a thing that gets run by mistake.
 *
 * <p>This class holds jqwik annotations only. A class mixing a jqwik {@code @Property} with a JUnit
 * Jupiter {@code @Test} gets claimed by the Jupiter engine, and the property is then reported as
 * skipped while the build stays green — so the harness self-check lives in {@link
 * FailClosedHarnessTest} instead, and the two never share a class. A Jupiter {@code @Tag} on a
 * {@code @Property} has the same effect for a different reason: the platform filter excludes it and
 * jqwik reports a skip, which is indistinguishable from a pass in a build log.
 */
class FailClosedPropertyTest {

  /** M1-09's exit criterion. */
  private static final int TRIES = 1_000_000;

  @Property(tries = TRIES)
  void removingAFactNeverImprovesTheDecisionToApprove(
      @ForAll("policies") List<Rule> policy, @ForAll("facts") Facts facts) {
    FailClosedProperty.check(RealEngine.INSTANCE, policy, facts);
  }

  @Provide
  Arbitrary<List<Rule>> policies() {
    return FailClosedProperty.policies();
  }

  @Provide
  Arbitrary<Facts> facts() {
    return FailClosedProperty.facts();
  }
}
