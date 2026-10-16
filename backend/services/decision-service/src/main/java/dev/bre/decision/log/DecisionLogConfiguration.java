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
    /** Kafka-first (M4-01). Durable on the broker's acknowledgement; reads come from the sink. */
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
   * @param kafka the producer, present only when Kafka is configured
   * @param topic the topic Kafka-first mode writes to
   * @param ackTimeoutMs how long a decision waits for the broker before failing
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
      dev.bre.decision.crypto.FactsSealer sealer,
      org.springframework.beans.factory.ObjectProvider<
              org.springframework.kafka.core.KafkaTemplate<String, String>>
          kafka,
      @Value("${bre.decision-log.kafka.topic:bre.decision.recorded.v1}") String topic,
      @Value("${bre.decision-log.kafka.ack-timeout-ms:5000}") long ackTimeoutMs) {

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
      case KAFKA -> {
        org.springframework.kafka.core.KafkaTemplate<String, String> producer =
            kafka.getIfAvailable();
        if (producer == null) {
          throw new IllegalStateException(
              "bre.decision-log.mode=KAFKA but no Kafka producer is configured. Set"
                  + " spring.kafka.bootstrap-servers.");
        }
        DataSource hotStore = dataSource.getIfAvailable();
        if (hotStore == null) {
          // Kafka-first still needs the hot store, and this is the misconfiguration most likely
          // to be made: the topic is the system of record, but a topic answers no questions. With
          // no store the service would accept decisions and be unable to show a single one of
          // them, which is a worse state than refusing to start.
          throw new IllegalStateException(
              "bre.decision-log.mode=KAFKA but no DataSource is configured. Kafka is the system of"
                  + " record; every READ is served from the hot store the sink fills, so the"
                  + " service needs both. Set spring.datasource.url, username and password.");
        }
        if (storeFacts) {
          // Fail closed rather than drop them quietly. The event carries no facts by design (see
          // DecisionEvent), so an operator who asked for fact storage and got none would discover
          // it during a regulator's replay — which is the worst possible time.
          throw new IllegalStateException(
              "bre.decision-log.mode=KAFKA with store-facts=true is refused: the decision event"
                  + " carries no fact snapshot, because facts are personal data and a topic is not"
                  + " where they belong (§9.6). Per-principal envelope encryption of the snapshot"
                  + " is M4-04. Use POSTGRES to store facts today.");
        }
        OutboxDecisionLog store =
            new OutboxDecisionLog(
                new JdbcTemplate(hotStore), objectMapper, engineVersion, envCode, false, sealer);
        KafkaDecisionLog kafkaLog =
            new KafkaDecisionLog(
                producer, store, objectMapper, topic, java.time.Duration.ofMillis(ackTimeoutMs));
        kafkaLog.logMode();
        yield kafkaLog;
      }
    };
  }

  /**
   * The sink, which fills the hot store from the topic (M4-02).
   *
   * <p>Only in Kafka-first mode: in the other two there is nothing to consume, and a listener
   * container started against an unconfigured broker would retry a connection forever and report it
   * as an application that is up.
   *
   * <p>It takes its store from the log rather than building one, because the sink writing to a
   * different store than the reads come from is the one way this mode can be silently broken —
   * every decision recorded, every lookup empty.
   *
   * @param decisionLog the log chosen above, which in this mode is the Kafka one
   * @param objectMapper the JSON codec
   * @return the sink
   */
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      name = "bre.decision-log.mode",
      havingValue = "KAFKA")
  public DecisionSink decisionSink(
      DecisionLog decisionLog,
      ObjectMapper objectMapper,
      @Value("${bre.archive.dir:}") String archiveDir,
      @Value("${bre.archive.roll-after-seconds:300}") long rollAfterSeconds,
      @Value("${bre.archive.roll-at-rows:20000}") int rollAtRows) {

    if (!(decisionLog instanceof KafkaDecisionLog kafkaLog)
        || !(kafkaLog.hotStore() instanceof OutboxDecisionLog store)) {
      throw new IllegalStateException(
          "the sink needs the hot store the Kafka log reads from, and the configured log is "
              + decisionLog.getClass().getSimpleName());
    }

    dev.bre.decision.archive.ParquetDecisionArchive archive = null;
    if (archiveDir != null && !archiveDir.isBlank()) {
      archive =
          new dev.bre.decision.archive.ParquetDecisionArchive(
              new dev.bre.decision.archive.ArchiveStore.Directory(
                  java.nio.file.Path.of(archiveDir)),
              java.time.Duration.ofSeconds(rollAfterSeconds),
              rollAtRows);
      log.info("archiving decisions as Parquet under {}", archiveDir);
    } else {
      // Not a warning. An installation whose retention requirement is met by the hot store alone
      // is a legitimate configuration, and the archive is the thing you turn on when it is not.
      log.info(
          "no bre.archive.dir: decisions are kept in the hot store only, for as long as its"
              + " retention allows. Set it to keep the §6.1.5 WORM Parquet archive.");
    }
    return new DecisionSink(store, objectMapper, archive);
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
