package dev.bre.conformance.failclosed;

import dev.bre.conformance.failclosed.FailClosedModel.Facts;
import dev.bre.conformance.failclosed.FailClosedModel.Rule;
import java.util.List;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * The jqwik run of LLD §13 property 2 (see {@link FailClosedProperty}).
 *
 * <p>This class holds jqwik annotations only. A class mixing a jqwik {@code @Property} with a JUnit
 * Jupiter {@code @Test} gets claimed by the Jupiter engine, and the property is reported as skipped
 * while the build stays green — so the harness self-check lives in {@link FailClosedHarnessTest}
 * instead, and the two never share a class.
 *
 * <p>M1-09 points this at the real engine and raises {@code tries} to 10⁶, which is that
 * milestone's exit criterion.
 */
class FailClosedPropertyTest {

  /** Cases for the stub run. M1-09 raises this to 1_000_000 against the real engine. */
  private static final int STUB_TRIES = 2_000;

  @Property(tries = STUB_TRIES)
  void removingAFactNeverImprovesTheDecisionToApprove(
      @ForAll("policies") List<Rule> policy, @ForAll("facts") Facts facts) {
    FailClosedProperty.check(StubEngines.failClosed(), policy, facts);
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
