package dev.bre.decision.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Chooses the decision log (D6).
 *
 * <p>Both modes sit behind the one {@link DecisionLog} port, so the service layer never knows which
 * it is talking to:
 *
 * <ul>
 *   <li>{@code POSTGRES} — compact mode. Durable: the decision, its idempotency key and its outbox
 *       event commit in one transaction before the response returns.
 *   <li>{@code IN_MEMORY} — local development. <b>Not durable.</b>
 *   <li>{@code KAFKA} — Kafka-first, for scale. Not implemented; M4.
 * </ul>
 *
 * <p>The default is {@code IN_MEMORY}, deliberately: the service then starts with no database and
 * the walking skeleton runs on a laptop. The cost of that default is that it must be impossible to
 * leave on by accident, which is why {@link #decisionLog} logs the mode at WARN when it is not
 * durable, and {@link DecisionLog#describe()} says so in words rather than in a code.
 */
@Configuration
public class DecisionLogConfiguration {

  private static final Logger log = LoggerFactory.getLogger(DecisionLogConfiguration.class);

  /** Which decision log to use. */
  public enum Mode {
    /** PostgreSQL-first with a transactional outbox. Durable. */
    POSTGRES,
    /** In-process map. Local development only; loses every decision on restart. */
    IN_MEMORY,
    /** Kafka-first. Not implemented (M4). */
    KAFKA
  }

  /**
   * Builds the configured decision log.
   *
   * @param mode the mode, from {@code bre.decision-log.mode}
   * @param dataSource the data source, required for POSTGRES
   * @param objectMapper the JSON codec
   * @param engineVersion the engine version pinned on every decision (D8)
   * @param envCode the environment code recorded on every decision
   * @param storeFacts whether to persist fact snapshots, sealed per §9.2; off by default
   * @param sealer the §9.2 sealer (M2-06)
   * @return the decision log
   */
  @Bean
  public DecisionLog decisionLog(
      @Value("${bre.decision-log.mode:IN_MEMORY}") Mode mode,
      org.springframework.beans.factory.ObjectProvider<DataSource> dataSource,
      ObjectMapper objectMapper,
      @Value("${bre.engine-version:0.1.0-SNAPSHOT}") String engineVersion,
      @Value("${bre.env-code:LOCAL}") String envCode,
      @Value("${bre.decision-log.store-facts:false}") boolean storeFacts,
      dev.bre.decision.crypto.FactsSealer sealer) {

    return switch (mode) {
      case POSTGRES -> {
        DataSource configured = dataSource.getIfAvailable();
        if (configured == null) {
          // Failing to start is right. Silently falling back to the in-memory log would mean an
          // operator who asked for durability got a service that quietly loses decisions.
          throw new IllegalStateException(
              "bre.decision-log.mode=POSTGRES but no DataSource is configured. Set"
                  + " spring.datasource.url, username and password.");
        }
        log.info("decision log: PostgreSQL compact mode (durable)");
        yield new OutboxDecisionLog(
            new JdbcTemplate(configured), objectMapper, engineVersion, envCode, storeFacts, sealer);
      }
      case IN_MEMORY -> {
        log.warn(
            "decision log: IN_MEMORY — decisions are NOT durable and are lost on restart. Set"
                + " bre.decision-log.mode=POSTGRES for a durable log.");
        yield new InMemoryDecisionLog();
      }
      case KAFKA ->
          throw new IllegalStateException(
              "bre.decision-log.mode=KAFKA is not implemented yet (M4). Use POSTGRES for a durable"
                  + " log, or IN_MEMORY for local development.");
    };
  }

  /**
   * The replay service, which explains a decision by re-running it (D7).
   *
   * <p>Declared here rather than annotated, because it needs the decision log <em>this</em>
   * configuration chose: a replay reads the stored fact snapshot, and in IN_MEMORY mode there is
   * nothing sealed to read. Wiring it beside the log keeps the two from disagreeing about which
   * store is in force.
   *
   * @param bundles the verified policy cache
   * @param decisionLog the log chosen above
   * @return the replay service
   */
  @Bean
  public dev.bre.decision.explain.ReplayService replayService(
      dev.bre.decision.policy.BundleCache bundles, DecisionLog decisionLog) {
    return new dev.bre.decision.explain.ReplayService(bundles, decisionLog);
  }
}
