package dev.bre.decision.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka-first decision log (D6, M4-01).
 *
 * <p><b>The write the response waits on is the Kafka write.</b> {@code append} blocks until the
 * broker has acknowledged, because D6's guarantee is that a decision the caller has acted on is one
 * the lender can produce on audit. Returning before the acknowledgement would make the log
 * eventually durable, which is a different and much weaker promise than the one the API makes.
 *
 * <p><b>Three producer settings carry the whole guarantee, and all three are mandatory here rather
 * than configurable.</b>
 *
 * <ul>
 *   <li>{@code acks=all} — the leader waits for every in-sync replica. With {@code acks=1} a
 *       decision acknowledged to a caller is lost when the leader fails before replicating, which
 *       is exactly the RPO-0 claim D6 makes.
 *   <li>{@code enable.idempotence=true} — the producer stamps a sequence number, so a retried send
 *       after a network timeout does not append the same decision twice. Without it the obvious
 *       failure is a duplicate the sink writes as a second row with the same decision id, and the
 *       insert's primary key then turns a retry into a poisoned partition.
 *   <li>{@code min.insync.replicas=2}, on the TOPIC rather than here — {@code acks=all} on a topic
 *       with one in-sync replica is {@code acks=1} wearing a disguise. It is set when the topic is
 *       created, which is why that is an operational step and not a default.
 * </ul>
 *
 * <p><b>Reads do not come from Kafka.</b> A log is not a store: there is no "get decision 7" on a
 * topic, and scanning a partition for one key is not a lookup. So every read delegates to the hot
 * store, which {@link DecisionSink} fills by consuming this topic. The consequence is real and is
 * stated rather than hidden: a read immediately after a write can miss, because the sink is
 * asynchronous. {@code findByRequestId} is the one that matters — idempotency is therefore
 * best-effort in this mode, and a caller that retries within the sink's lag gets a second decision
 * with the same {@code requestId}. Compact mode has no such window, which is a genuine reason to
 * choose it when volume does not demand this one.
 *
 * <p><b>The facts are never published.</b> See {@link DecisionEvent}. Kafka-first mode refuses to
 * start with {@code store-facts} on, rather than quietly dropping them: an operator who asked for
 * fact storage and silently did not get it would discover it during a regulator's replay.
 *
 * <p><b>Encoding deviation, recorded.</b> 00 §3.9 specifies CloudEvents <em>binary</em> mode with a
 * <em>protobuf</em> payload for this topic, because it is the high-volume one. This writes
 * structured JSON, as every other topic does. The attributes, the key and the durability are as
 * specified; only the encoding differs, and it is one {@code Serializer} away. Protobuf needs a
 * generated schema and a schema registry, both M4 infrastructure that does not exist yet, and
 * shipping a JSON payload that says so beats shipping nothing or pretending.
 */
public class KafkaDecisionLog implements DecisionLog {

  private static final Logger log = LoggerFactory.getLogger(KafkaDecisionLog.class);

  private final KafkaTemplate<String, String> kafka;
  private final DecisionLog readSide;
  private final ObjectMapper json;
  private final String topic;
  private final Duration ackTimeout;

  /**
   * Creates the log.
   *
   * @param kafka the producer, configured for acks=all and idempotence
   * @param readSide the hot store every read is served from — the same store {@link DecisionSink}
   *     writes into
   * @param json the mapper, with the time module the event's Instants need
   * @param topic the topic, {@code bre.decision.recorded.v1} unless overridden
   * @param ackTimeout how long to wait for the broker before failing the decision
   */
  public KafkaDecisionLog(
      KafkaTemplate<String, String> kafka,
      DecisionLog readSide,
      ObjectMapper json,
      String topic,
      Duration ackTimeout) {
    this.kafka = kafka;
    this.readSide = readSide;
    this.json = json;
    this.topic = topic;
    this.ackTimeout = ackTimeout;
  }

  @Override
  public void append(DecisionRecord record) {
    DecisionEvent event = DecisionEvent.from(record);
    ProducerRecord<String, String> message =
        new ProducerRecord<>(topic, event.partitionKey(), writeJson(event));

    // CloudEvents in binary mode puts the attributes on the headers (D12, §7.1). The payload is
    // structured JSON rather than protobuf — see the class comment — but the attributes are the
    // specified ones, so a consumer reading them is reading what it expects.
    message.headers().add("ce_specversion", bytes("1.0"));
    message.headers().add("ce_type", bytes(DecisionEvent.TOPIC));
    message.headers().add("ce_source", bytes("/bre/decision-service"));
    message.headers().add("ce_id", bytes(record.decisionId()));
    message.headers().add("ce_time", bytes(record.recordedAt().toString()));
    message.headers().add("ce_subject", bytes(record.tenantId()));

    try {
      // BLOCKING, deliberately. See the class comment: the response waits on this.
      //
      // The send() call is INSIDE the try, not just the get(). KafkaTemplate throws
      // KafkaException synchronously when it cannot even fetch metadata — an unreachable broker,
      // most obviously — so a try around the get() alone lets that escape as a raw KafkaException
      // and the caller gets "Send failed" instead of a message saying the decision was not
      // recorded. Found by pointing a test at a dead port, which is the one case this code exists
      // to handle well.
      kafka.send(message).get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while recording the decision", interrupted);
    } catch (ExecutionException
        | TimeoutException
        | org.springframework.kafka.KafkaException failed) {
      // Fail the decision. A 500 for a decision that was made correctly is a bad outcome; a 200
      // for a decision nobody recorded is a worse one, and it is the one that is discovered by a
      // regulator rather than by a caller. The message names no fact value (§9.6).
      throw new IllegalStateException(
          "the decision could not be recorded durably within "
              + ackTimeout
              + ", so it was not returned. decision="
              + record.decisionId(),
          failed);
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private String writeJson(DecisionEvent event) {
    try {
      return json.writeValueAsString(event);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // The cause is kept and the value is not: Jackson's own message names the type and the
      // problem, never the data (§9.6).
      throw new IllegalStateException("could not serialise the decision event", e);
    }
  }

  @Override
  public Optional<DecisionRecord> find(String tenantId, String decisionId) {
    return readSide.find(tenantId, decisionId);
  }

  @Override
  public Optional<DecisionRecord> findByRequestId(String tenantId, String requestId) {
    return readSide.findByRequestId(tenantId, requestId);
  }

  @Override
  public Optional<StoredFacts> facts(String tenantId, String decisionId) {
    // Always empty in this mode, and not an error: the facts were never published. The replay path
    // already distinguishes "not stored" from "corrupt".
    return Optional.empty();
  }

  @Override
  public String describe() {
    return "Kafka-first (durable on acks=all; reads served from the hot store the sink fills,"
        + " so a read can lag a write)";
  }

  /**
   * The hot store every read is served from.
   *
   * <p>Exposed so {@link DecisionSink} writes into the same store this reads from. That the two
   * agree is the whole correctness condition of this mode, and making the sink find its own store
   * would be two places to configure one thing.
   *
   * @return the store
   */
  public DecisionLog hotStore() {
    return readSide;
  }

  /** Logs the shape of the guarantee at startup, where an operator will see it. */
  public void logMode() {
    log.info(
        "decision log: Kafka-first on topic {} — the response waits on the broker acknowledgement."
            + " Set min.insync.replicas=2 on the topic, or acks=all is acks=1 in disguise.",
        topic);
  }
}
