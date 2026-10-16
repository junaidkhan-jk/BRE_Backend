package dev.bre.decision.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.bre.decision.api.DecisionApi.DecisionResponse;
import dev.bre.decision.api.DecisionApi.RuleOutcome;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * Kafka-first mode against a REAL broker and a REAL PostgreSQL (M4-01, M4-02).
 *
 * <p>A real {@link EmbeddedKafkaKraftBroker} rather than a mocked template, because what is under
 * test is a durability guarantee: that {@code append} does not return until the broker has
 * acknowledged, and that the producer is configured so the acknowledgement means what D6 says it
 * means. A mock would assert my own assumptions about what {@code send().get()} does.
 *
 * <p>No Docker on this host, so this is the same trade {@code embedded-postgres} already makes — an
 * actual broker in this JVM, from Maven Central.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafkaDecisionLogTest {

  private static final String TOPIC = "bre.decision.recorded.v1";

  /** Partitions on the test topic. Used to create it AND to assign to it, so the two agree. */
  private static final int PARTITIONS = 2;

  private static final String TENANT = "t_kafka";

  private static EmbeddedKafkaKraftBroker broker;
  private static EmbeddedPostgres postgres;
  private static JdbcTemplate jdbc;
  private static ObjectMapper json;
  private static OutboxDecisionLog store;
  private static KafkaDecisionLog log;

  @BeforeAll
  void start() throws Exception {
    broker = new EmbeddedKafkaKraftBroker(1, PARTITIONS, TOPIC);
    broker.afterPropertiesSet();

    postgres = EmbeddedPostgres.builder().start();
    DataSource dataSource = postgres.getPostgresDatabase();
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    jdbc = new JdbcTemplate(dataSource);

    json =
        JsonMapper.builder()
            .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    // storeFacts=false, so the sealer is never invoked — Kafka-first mode refuses fact storage
    // anyway (see DecisionEvent). A real one is built because FactsSealer is a final class.
    dev.bre.decision.crypto.FactsSealer sealer =
        new dev.bre.decision.crypto.FactsSealer(
            new dev.bre.crypto.TenantDataKeyProvider(
                new dev.bre.crypto.LocalDevKms(), dev.bre.crypto.InMemoryKeyStores.tenantKeys()),
            new dev.bre.crypto.PrincipalKeyService(
                new dev.bre.crypto.LocalDevKms(), dev.bre.crypto.InMemoryKeyStores.principalKeys()),
            tenantId -> "a-fixed-test-root-secret".getBytes(StandardCharsets.UTF_8),
            dev.bre.facts.LendingFacts.schema());
    store = new OutboxDecisionLog(jdbc, json, "0.1.0-test", "TEST", false, sealer);
    log = new KafkaDecisionLog(template(), store, json, TOPIC, Duration.ofSeconds(10));
  }

  private static KafkaTemplate<String, String> template() {
    Map<String, Object> settings = KafkaTestUtils.producerProps(broker.getBrokersAsString());
    settings.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    settings.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    // The two settings that ARE the guarantee, set here exactly as application.yaml sets them.
    settings.put(ProducerConfig.ACKS_CONFIG, "all");
    settings.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(settings));
  }

  @AfterAll
  void stop() throws Exception {
    if (broker != null) {
      broker.destroy();
    }
    if (postgres != null) {
      postgres.close();
    }
  }

  @Test
  @DisplayName("a decision is on the topic before append returns, with its CloudEvent attributes")
  void appendIsDurableBeforeItReturns() throws Exception {
    DecisionRecord record = decision("APP-1", "APPROVE");

    log.append(record);

    try (KafkaConsumer<String, String> consumer = consumer("before-return")) {
      // append has already RETURNED, and the consumer is assigned only now — so anything it finds
      // was durable before the call came back. That is the guarantee, asserted without a race.
      List<ConsumerRecord<String, String>> found = recordsFor(consumer, TENANT + ":APP-1");
      assertThat(found).hasSize(1);

      ConsumerRecord<String, String> message = found.get(0);
      assertThat(message.key())
          .as("partition key is tenantId:applicationRef, so one application keeps its order")
          .isEqualTo(TENANT + ":APP-1");

      assertThat(header(message, "ce_type")).isEqualTo("bre.decision.recorded.v1");
      assertThat(header(message, "ce_specversion")).isEqualTo("1.0");
      assertThat(header(message, "ce_id")).isEqualTo(record.decisionId());
      assertThat(header(message, "ce_subject")).isEqualTo(TENANT);

      JsonNode payload = json.readTree(message.value());
      assertThat(payload.get("response").get("decision").asText()).isEqualTo("APPROVE");
      assertThat(payload.get("tenantId").asText()).isEqualTo(TENANT);
    }
  }

  @Test
  @DisplayName("the event carries no facts, whatever the decision was taken on")
  void theEventCarriesNoFacts() throws Exception {
    // The boundary in DecisionEvent, asserted rather than documented. A topic has a retention
    // window and every consumer with access reads all of it; the applicant's data does not belong
    // there. A regression here would publish PII to every consumer and nothing else would notice.
    DecisionRecord record = decision("APP-FACTS", "DECLINE");

    log.append(record);

    try (KafkaConsumer<String, String> consumer = consumer("no-facts")) {
      List<ConsumerRecord<String, String>> found = recordsFor(consumer, TENANT + ":APP-FACTS");
      assertThat(found).hasSize(1);

      String body = found.get(0).value();
      assertThat(body).doesNotContain("factsJson");
      assertThat(body)
          .as("the fixture's PAN must not appear anywhere on the topic")
          .doesNotContain("ABCDE1234F");
    }
  }

  @Test
  @DisplayName("the sink writes the event into the hot store, and reads are served from there")
  void theSinkFillsTheHotStore() throws Exception {
    DecisionSink sink = new DecisionSink(store, json, null);
    DecisionRecord record = decision("APP-SINK", "REFER");

    log.append(record);

    // Before the sink runs, the read side is empty — the documented lag of this mode, asserted so
    // that it is a known property rather than a surprise.
    assertThat(log.find(TENANT, record.decisionId())).isEmpty();

    try (KafkaConsumer<String, String> consumer = consumer("sink")) {
      List<ConsumerRecord<String, String>> found = recordsFor(consumer, TENANT + ":APP-SINK");
      assertThat(found).hasSize(1);

      sink.consume(found.get(0).value());

      Optional<DecisionRecord> readBack = log.find(TENANT, record.decisionId());
      assertThat(readBack).isPresent();
      assertThat(readBack.get().response().decision()).isEqualTo("REFER");
      assertThat(log.findByRequestId(TENANT, record.requestId())).isPresent();
    }
  }

  @Test
  @DisplayName("a redelivered event is written once, because the decision id is the key")
  void aRedeliveryIsIdempotent() throws Exception {
    DecisionSink sink = new DecisionSink(store, json, null);
    DecisionRecord record = decision("APP-DUP", "APPROVE");
    String payload = json.writeValueAsString(DecisionEvent.from(record));

    sink.consume(payload);
    // At-least-once is the contract, so this happens on every rebalance and every redeployment.
    // The second write must be swallowed rather than thrown, or one rebalance stalls a partition
    // behind a record that can never succeed.
    sink.consume(payload);

    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM bre_dec.decisions WHERE tenant_id = ? AND decision_id = ?::uuid",
            Integer.class,
            TENANT,
            record.decisionId());
    assertThat(rows).isEqualTo(1);
  }

  @Test
  @DisplayName("an unparseable event is not retried forever: it is a conversion failure")
  void malformedIsNotRetryable() {
    DecisionSink sink = new DecisionSink(store, json, null);
    // Retrying bytes that cannot parse stalls the partition behind one bad record for ever, so
    // this has to be the kind of failure a container dead-letters rather than redelivers.
    assertThatThrownBy(() -> sink.consume("{this is not json"))
        .isInstanceOf(org.springframework.kafka.support.converter.ConversionException.class)
        .hasMessageContaining("not valid JSON");
  }

  @Test
  @DisplayName("no broker means no decision: append fails rather than returning unrecorded")
  void aBrokenBrokerFailsTheDecision() {
    Map<String, Object> settings = KafkaTestUtils.producerProps("127.0.0.1:1");
    settings.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    settings.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    settings.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000);
    KafkaDecisionLog unreachable =
        new KafkaDecisionLog(
            new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(settings)),
            store,
            json,
            TOPIC,
            Duration.ofSeconds(2));

    // The whole reason append blocks. A 500 for a decision that was made correctly is bad; a 200
    // for a decision nobody recorded is worse, and it is discovered by a regulator rather than by
    // a caller.
    assertThatThrownBy(() -> unreachable.append(decision("APP-DOWN", "APPROVE")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not be recorded durably");
  }

  // --- plumbing --------------------------------------------------------------------------------

  /**
   * A consumer ASSIGNED to the topic's partitions, with no consumer group at all.
   *
   * <p>Three versions of this, and each was a different mistake worth keeping. The first used
   * {@code latest} with a short poll to "drain the assignment", which is a race — the poll does not
   * guarantee assignment completed, so a record produced immediately after can be skipped, and two
   * of three tests passed on timing. The second used {@code subscribe} with a unique group per
   * test, which is correct and SLOW: joining a group on a cold broker creates the internal offsets
   * topic and runs the join protocol, which took 128 seconds under full-suite load and failed a
   * 60-second deadline while the code under test worked perfectly.
   *
   * <p>Assignment needs none of it. No coordinator to find, no group to join, no offsets to commit:
   * the test names the partitions and seeks to the beginning. Group management is Kafka's feature
   * for sharing work between pods, which is the sink's problem and not this test's.
   *
   * <p>The partitions come from the constant the topic was CREATED with rather than from {@code
   * partitionsFor}, which asks the broker and returns empty on a fresh consumer whose metadata has
   * not arrived — assigning nothing, which fails as "not subscribed to any topics".
   *
   * @param unusedName kept so call sites still read as naming their consumer
   * @return a consumer positioned at the start of every partition
   */
  private static KafkaConsumer<String, String> consumer(String unusedName) {
    Map<String, Object> settings =
        KafkaTestUtils.consumerProps(broker.getBrokersAsString(), "assigned-no-group", true);
    settings.put("key.deserializer", StringDeserializer.class);
    settings.put("value.deserializer", StringDeserializer.class);
    KafkaConsumer<String, String> consumer = new KafkaConsumer<>(settings);
    List<org.apache.kafka.common.TopicPartition> partitions =
        java.util.stream.IntStream.range(0, PARTITIONS)
            .mapToObj(partition -> new org.apache.kafka.common.TopicPartition(TOPIC, partition))
            .toList();
    consumer.assign(partitions);
    consumer.seekToBeginning(partitions);
    return consumer;
  }

  /** Every record on the topic for one key, read from the beginning. */
  private static List<ConsumerRecord<String, String>> recordsFor(
      KafkaConsumer<String, String> consumer, String key) {
    List<ConsumerRecord<String, String>> matching = new java.util.ArrayList<>();
    // Thirty seconds is ample now there is no group to join: the consumer is assigned directly
    // and seeks to the beginning, so the only wait is the broker serving a fetch.
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline && matching.isEmpty()) {
      for (ConsumerRecord<String, String> candidate : consumer.poll(Duration.ofMillis(500))) {
        if (key.equals(candidate.key())) {
          matching.add(candidate);
        }
      }
    }
    return matching;
  }

  private static String header(ConsumerRecord<String, String> message, String name) {
    return new String(message.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
  }

  private static DecisionRecord decision(String applicationRef, String outcome) {
    String id = dev.bre.common.UuidV7.generate(Instant.now()).toString();
    DecisionResponse response =
        new DecisionResponse(
            id,
            TENANT,
            "req-" + applicationRef + "-" + id,
            LocalDate.of(2026, 10, 1),
            outcome,
            false,
            List.of(
                new RuleOutcome(
                    "RULE_1", "APPROVE".equals(outcome) ? "PASS" : "FAIL", null, null)));
    return new DecisionRecord(
        id,
        TENANT,
        response.requestId(),
        Optional.of(applicationRef),
        Instant.now(),
        Instant.now(),
        1234L,
        "Asia/Kolkata",
        99L,
        Optional.of("MFI_JLG_CREDIT_UW"),
        Optional.of(1),
        Optional.of("sha256:" + "a".repeat(64)),
        "lending-facts@1.0.0",
        response,
        "{\"parties\":[{\"pan\":\"ABCDE1234F\"}]}");
  }
}
