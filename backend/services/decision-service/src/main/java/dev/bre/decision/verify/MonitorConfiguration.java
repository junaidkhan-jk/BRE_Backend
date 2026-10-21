package dev.bre.decision.verify;

import dev.bre.decision.explain.ReplayService;
import dev.bre.decision.log.DecisionLog;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/** Wires the monitors, which need the hot store and so exist only when there is one (M4-07). */
@Configuration
public class MonitorConfiguration {

  private static final Logger log = LoggerFactory.getLogger(MonitorConfiguration.class);

  /**
   * The outcome guardrails.
   *
   * @param dataSource the hot store
   * @return the monitors
   */
  @Bean
  public OutcomeMonitors outcomeMonitors(ObjectProvider<DataSource> dataSource) {
    return new OutcomeMonitors(new JdbcTemplate(required(dataSource)));
  }

  /**
   * The replay-verification job.
   *
   * @param dataSource the hot store
   * @param decisionLog the decision log
   * @param replay the replay service
   * @return the job
   */
  @Bean
  public ReplayVerificationJob replayVerificationJob(
      ObjectProvider<DataSource> dataSource, DecisionLog decisionLog, ReplayService replay) {
    return new ReplayVerificationJob(new JdbcTemplate(required(dataSource)), decisionLog, replay);
  }

  /**
   * The portfolio back-test (M5-05).
   *
   * <p>Beside the monitors rather than in its own configuration, because it needs exactly what they
   * need — the hot store to sample and the replay service to re-decide — and a second configuration
   * class whose only job is to repeat the same {@code required(dataSource)} check would be two
   * places to forget it.
   *
   * @param dataSource the hot store
   * @param decisionLog the decision log
   * @param replay the replay service
   * @return the job
   */
  @Bean
  public dev.bre.decision.backtest.BackTestJob backTestJob(
      ObjectProvider<DataSource> dataSource, DecisionLog decisionLog, ReplayService replay) {
    return new dev.bre.decision.backtest.BackTestJob(
        new JdbcTemplate(required(dataSource)), decisionLog, replay);
  }

  private static DataSource required(ObjectProvider<DataSource> dataSource) {
    DataSource configured = dataSource.getIfAvailable();
    if (configured == null) {
      throw new IllegalStateException(
          "the outcome guardrails read the hot store, so they need spring.datasource.*. They are"
              + " what notices a bundle that passed every test and is still wrong (§10.1).");
    }
    return configured;
  }

  /**
   * Runs the guardrails on a schedule, so a breach is noticed without anybody asking.
   *
   * <p>Hourly, matching the window §11.4 defines the decision mix over. A guardrail that only runs
   * when an operator opens a dashboard is a guardrail for the hours somebody is watching, which are
   * not the hours a bad deployment is most likely to go out.
   */
  @Bean
  public GuardrailSchedule guardrailSchedule(
      OutcomeMonitors monitors, @Value("${bre.monitors.tenants:}") String tenants) {
    return new GuardrailSchedule(monitors, tenants);
  }

  /** The hourly guardrail pass. */
  public static final class GuardrailSchedule {

    private final OutcomeMonitors monitors;
    private final java.util.List<String> tenants;

    GuardrailSchedule(OutcomeMonitors monitors, String tenants) {
      this.monitors = monitors;
      this.tenants =
          tenants == null || tenants.isBlank()
              ? java.util.List.of()
              : java.util.Arrays.stream(tenants.split(","))
                  .map(String::trim)
                  .filter(name -> !name.isEmpty())
                  .toList();
    }

    /** Checks each configured tenant and logs every finding at its own severity. */
    @Scheduled(cron = "${bre.monitors.cron:0 5 * * * *}")
    public void run() {
      for (String tenantId : tenants) {
        try {
          for (Finding finding : monitors.check(tenantId)) {
            // Logged at the level the severity implies, so an operator's log-based alerting can
            // route on it without this service knowing anything about their paging tool.
            if (Finding.PAGE.equals(finding.severity())) {
              log.error(
                  "GUARDRAIL {} {}: {}", finding.severity(), finding.monitor(), finding.summary());
            } else {
              log.warn(
                  "GUARDRAIL {} {}: {}", finding.severity(), finding.monitor(), finding.summary());
            }
          }
        } catch (RuntimeException failed) {
          // One tenant's failure must not stop the others being checked.
          log.error("the outcome guardrails failed for tenant={}", tenantId, failed);
        }
      }
    }
  }
}
