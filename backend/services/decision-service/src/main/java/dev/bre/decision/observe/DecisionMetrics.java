package dev.bre.decision.observe;

import dev.bre.common.Telemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * What the decision path reports (M4-08, §11.5).
 *
 * <p><b>Every label is checked against §9.6's forbidden list before it is used.</b> That is the
 * whole reason this class exists rather than callers touching a {@link MeterRegistry} directly.
 * Telemetry is the surface where personal data leaks by accident: a metric labelled with an
 * application reference looks harmless, and it puts an identifier for a specific borrower into a
 * time-series database that is replicated, retained for a year, and readable by anyone with a
 * Grafana login. Logs get reviewed for this; dashboards rarely do.
 *
 * <p><b>Cardinality is the other reason.</b> A label with one value per application is a new time
 * series per borrower — tens of millions of them — which does not merely cost money, it takes the
 * monitoring system down, and it does so during the incident when somebody needs it. The forbidden
 * list and the cardinality limit are the same list for different reasons.
 *
 * <p><b>The metrics are the ones §11.5's SLOs are computed from</b>, named so the burn-rate rules
 * in {@code deploy/prometheus/} can be read beside them without a translation step.
 */
public final class DecisionMetrics {

  /** Decisions, by outcome. The numerator and denominator of the availability SLO. */
  public static final String DECISIONS = "bre.decisions";

  /** End-to-end server-side latency, including the durable write. NFR-PERF-01's p99. */
  public static final String LATENCY = "bre.decision.latency";

  /** Requests that failed. §11.5's error budget is computed from this over {@link #DECISIONS}. */
  public static final String FAILURES = "bre.decision.failures";

  /** Rule results that were ERROR. §11.4's throughput alarm. */
  public static final String RULE_ERRORS = "bre.rule.errors";

  private final MeterRegistry registry;
  private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Timer> timers = new ConcurrentHashMap<>();

  /**
   * Creates the metrics.
   *
   * @param registry the meter registry
   */
  public DecisionMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  /**
   * Records a completed decision.
   *
   * @param tenantId the tenant
   * @param environment the environment
   * @param outcome APPROVE, DECLINE, REFER or APPROVE_WITH_DEVIATION
   * @param ruleErrors how many rule results were ERROR
   * @param took how long it took, server-side, including the durable write
   */
  public void decided(
      String tenantId, String environment, String outcome, int ruleErrors, Duration took) {
    counter(DECISIONS, tenantId, environment, "outcome", outcome).increment();
    timer(tenantId, environment).record(took);
    if (ruleErrors > 0) {
      counter(RULE_ERRORS, tenantId, environment, "outcome", outcome).increment(ruleErrors);
    }
  }

  /**
   * Records a request that did not produce a decision.
   *
   * @param tenantId the tenant
   * @param environment the environment
   * @param reason a short, BOUNDED reason — an exception's simple name, never its message. A
   *     message can contain a fact value and is unbounded, which is both a §9.6 leak and an
   *     unbounded label.
   */
  public void failed(String tenantId, String environment, String reason) {
    counter(FAILURES, tenantId, environment, "reason", boundedReason(reason)).increment();
  }

  /**
   * Checks that a failure reason is a bare identifier, and replaces it when it is not.
   *
   * <p><b>Guarding the VALUE, because the names are fixed.</b> No caller chooses a label name here,
   * so the forbidden-name list protects a future label and nothing that exists today. Where
   * personal data would actually arrive is the value — specifically as an exception's message,
   * which is the obvious thing to reach for and is both unbounded and capable of quoting a fact.
   * "Connection timed out for applicant PAN ABCDE1234F" is a label with one series per borrower and
   * a disclosure in its name.
   *
   * <p>Replaced rather than thrown, and this is the opposite of the choice made for a forbidden
   * label name. A bad label name is a programming error at startup; a bad reason arrives while
   * something is already failing, and throwing from the failure path would turn one incident into
   * two. The metric still counts the failure, under a name that says the caller must do better.
   */
  private static String boundedReason(String reason) {
    if (reason != null && reason.matches("[A-Za-z][A-Za-z0-9_.]{0,63}")) {
      return reason;
    }
    return "UNNAMED";
  }

  private Counter counter(
      String name, String tenantId, String environment, String labelName, String labelValue) {
    requirePermitted(labelName);
    String key = name + '|' + tenantId + '|' + environment + '|' + labelName + '|' + labelValue;
    return counters.computeIfAbsent(
        key,
        ignored ->
            Counter.builder(name)
                .tag("tenant", tenantId)
                .tag("environment", environment)
                .tag(labelName, labelValue)
                .register(registry));
  }

  private Timer timer(String tenantId, String environment) {
    return timers.computeIfAbsent(
        tenantId + '|' + environment,
        ignored ->
            Timer.builder(LATENCY)
                .tag("tenant", tenantId)
                .tag("environment", environment)
                // Percentiles computed in the registry rather than client-side averages. An
                // average latency is the one number that cannot show a p99 problem: a service
                // answering 99 requests in 5 ms and one in 5 s averages 55 ms and looks healthy.
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry));
  }

  private static void requirePermitted(String label) {
    if (!Telemetry.isPermittedMetricLabel(label)) {
      // Thrown, not dropped. A label silently discarded would leave a metric that looks right and
      // answers a different question, and the code that added it would never be corrected.
      throw new IllegalArgumentException(
          "the metric label '"
              + label
              + "' identifies a person or an application (§9.6). It would put an identifier for"
              + " one borrower into a time-series database that is replicated, retained and widely"
              + " readable — and give that metric one series per applicant.");
    }
  }
}
