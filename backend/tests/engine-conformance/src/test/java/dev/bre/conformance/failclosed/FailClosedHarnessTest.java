package dev.bre.conformance.failclosed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bre.conformance.failclosed.FailClosedModel.Decision;
import dev.bre.conformance.failclosed.FailClosedModel.Facts;
import dev.bre.conformance.failclosed.FailClosedModel.OnMissing;
import dev.bre.conformance.failclosed.FailClosedModel.Rule;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the fail-closed harness is not vacuous: pointed at a deliberately fail-open engine, it
 * must report the violation.
 *
 * <p>Without this, {@link FailClosedPropertyTest} passing would say nothing — a property asserted
 * only against a correct implementation is green whether or not it checks anything. This is the
 * M0-05 exit criterion: "it fails on a deliberately fail-open stub".
 *
 * <p>Separate class from the property on purpose; see {@link FailClosedPropertyTest}'s note on
 * engine ownership.
 */
class FailClosedHarnessTest {

  /** A rule that FAILs on the present value, so no correct engine can reach APPROVE without it. */
  private static final List<Rule> POLICY = List.of(new Rule("income", 50_000L, OnMissing.REFER));

  private static final Facts FACTS = new Facts(Map.of("income", 10_000L));

  @Test
  @DisplayName("with the fact present, this policy declines")
  void baselineDeclines() {
    assertThat(StubEngines.failClosed().decide(POLICY, FACTS)).isEqualTo(Decision.DECLINE);
  }

  @Test
  @DisplayName("the fail-open stub really does approve once the fact is removed")
  void failOpenStubApprovesOnMissingData() {
    assertThat(StubEngines.failOpen().decide(POLICY, FACTS.without("income")))
        .as("if the stub were not actually fail-open, the test below would prove nothing")
        .isEqualTo(Decision.APPROVE);
  }

  @Test
  @DisplayName("the harness catches the fail-open engine rather than staying silent")
  void harnessCatchesFailOpen() {
    assertThatThrownBy(() -> FailClosedProperty.check(StubEngines.failOpen(), POLICY, FACTS))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("fail-open")
        .hasMessageContaining("income");
  }

  @Test
  @DisplayName("the harness passes the compliant engine on the same input")
  void harnessPassesFailClosed() {
    FailClosedProperty.check(StubEngines.failClosed(), POLICY, FACTS);
  }
}
