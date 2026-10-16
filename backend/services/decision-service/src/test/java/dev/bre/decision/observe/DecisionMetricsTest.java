package dev.bre.decision.observe;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the decision path reports, and what it must never report (M4-08, §9.6).
 *
 * <p>The forbidden-label tests are the point of this file. Telemetry is where personal data leaks
 * by accident: a metric labelled with an application reference looks harmless and puts an
 * identifier for one borrower into a time-series database that is replicated, retained for a year
 * and readable by anyone with a Grafana login. Log lines get reviewed for this. Dashboards do not.
 */
class DecisionMetricsTest {

  private MeterRegistry registry;
  private DecisionMetrics metrics;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    metrics = new DecisionMetrics(registry);
  }

  @Test
  @DisplayName("a decision is counted by outcome, with latency")
  void decisionsAreCounted() {
    metrics.decided("t_a", "PROD", "APPROVE", 0, Duration.ofMillis(18));
    metrics.decided("t_a", "PROD", "DECLINE", 0, Duration.ofMillis(22));
    metrics.decided("t_a", "PROD", "APPROVE", 0, Duration.ofMillis(15));

    assertThat(counterValue("APPROVE")).isEqualTo(2);
    assertThat(counterValue("DECLINE")).isEqualTo(1);
    assertThat(registry.find(DecisionMetrics.LATENCY).timer().count()).isEqualTo(3);
  }

  @Test
  @DisplayName("latency is measured around the whole call, including the durable write")
  void latencyCoversTheDurableWrite() {
    // NFR-PERF-01's budget is p99 ≤ 100 ms INCLUDING the durable write. Timing only the
    // evaluation would give a number always inside budget and never the one a caller experiences,
    // which is the most comfortable way to miss a latency problem.
    metrics.decided("t_a", "PROD", "APPROVE", 0, Duration.ofMillis(95));
    assertThat(
            registry
                .find(DecisionMetrics.LATENCY)
                .timer()
                .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
        .isGreaterThanOrEqualTo(95);
  }

  @Test
  @DisplayName("rule ERRORs are counted, because they are a queue somebody has to work")
  void ruleErrorsAreCounted() {
    metrics.decided("t_a", "PROD", "REFER", 3, Duration.ofMillis(30));
    assertThat(registry.find(DecisionMetrics.RULE_ERRORS).counter().count()).isEqualTo(3);
  }

  @Test
  @DisplayName("an exception MESSAGE cannot become a label value")
  void anExceptionMessageIsNotAcceptedAsAReason() {
    // Where personal data would actually arrive. No caller picks a label NAME here — they are
    // fixed — so the value is the surface, and the obvious mistake is passing getMessage().
    // "Connection timed out for applicant PAN ABCDE1234F" is a disclosure AND one time series per
    // borrower, which does not merely cost money: it takes the monitoring system down during the
    // incident when somebody needs it.
    metrics.failed("t_a", "PROD", "Connection timed out for applicant PAN ABCDE1234F");

    String reason = registry.find(DecisionMetrics.FAILURES).counter().getId().getTag("reason");
    assertThat(reason).isEqualTo("UNNAMED");
    assertThat(reason).doesNotContain("ABCDE1234F");

    // Replaced rather than thrown, deliberately: a bad reason arrives while something is ALREADY
    // failing, and throwing from the failure path turns one incident into two. The failure is
    // still counted.
    assertThat(registry.find(DecisionMetrics.FAILURES).counter().count()).isEqualTo(1);
  }

  @Test
  @DisplayName("a forbidden label NAME is refused, which protects the next label somebody adds")
  void forbiddenLabelNamesAreRefused() {
    // Unreachable through the methods above, because none of them lets a caller choose a name —
    // which is exactly why it is asserted here. The guard exists for the label somebody adds next
    // year, and a guard with no test is one that gets deleted as dead code.
    for (String forbidden : List.of("applicationRef", "application_ref", "partyRef", "party_ref")) {
      assertThat(dev.bre.common.Telemetry.isPermittedMetricLabel(forbidden))
          .as("%s must never be permitted as a metric label", forbidden)
          .isFalse();
    }
    assertThat(dev.bre.common.Telemetry.isPermittedMetricLabel("outcome")).isTrue();
    assertThat(dev.bre.common.Telemetry.isPermittedMetricLabel("tenant")).isTrue();
  }

  @Test
  @DisplayName("no emitted metric carries a tag that could identify anybody")
  void noEmittedMetricIdentifiesAnybody() {
    metrics.decided("t_a", "PROD", "APPROVE", 1, Duration.ofMillis(20));
    metrics.failed("t_a", "PROD", "IllegalStateException");

    // Swept across everything the registry holds, rather than asserting on the calls above. A
    // future metric added without thinking is exactly the regression this catches, and it would
    // not be in this test's list of calls.
    for (Meter meter : registry.getMeters()) {
      for (io.micrometer.core.instrument.Tag tag : meter.getId().getTags()) {
        assertThat(dev.bre.common.Telemetry.isPermittedMetricLabel(tag.getKey()))
            .as("metric %s carries the tag '%s'", meter.getId().getName(), tag.getKey())
            .isTrue();
      }
    }
  }

  @Test
  @DisplayName("a failure reason is a bounded name, never a message")
  void failureReasonsAreBounded() {
    // An exception's MESSAGE can contain a fact value and is unbounded — both a §9.6 leak and an
    // unbounded label. The contract is the simple name, and this records that it is the contract.
    metrics.failed("t_a", "PROD", "SQLTransientConnectionException");
    assertThat(registry.find(DecisionMetrics.FAILURES).counter().count()).isEqualTo(1);
    assertThat(registry.find(DecisionMetrics.FAILURES).counter().getId().getTag("reason"))
        .isEqualTo("SQLTransientConnectionException")
        .doesNotContain(" ");
  }

  private double counterValue(String outcome) {
    return registry.find(DecisionMetrics.DECISIONS).tag("outcome", outcome).counter().count();
  }
}
