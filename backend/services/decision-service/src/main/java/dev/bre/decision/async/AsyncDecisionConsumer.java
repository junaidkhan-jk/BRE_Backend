package dev.bre.decision.async;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bre.decision.api.DecisionApi.DecisionResponse;
import dev.bre.decision.app.DecideUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.converter.ConversionException;

/**
 * Decides async requests (M4-06, FR-EXEC-009).
 *
 * <p><b>An ordinary consumer group, not a leader.</b> Every pod takes its share of partitions and
 * there is no election to get wrong — a leader would make the slowest pod the throughput of the
 * whole deployment, and a leadership flap a stall.
 *
 * <p><b>Idempotent through the decision log, not through a second ledger.</b> The event's id is the
 * {@code requestId}, and {@code DecideUseCase} already returns the existing decision for a known
 * one. So a redelivery — a rebalance, a redeployment, an offset that did not commit — re-emits the
 * original decision rather than making a second one. That matters more here than on the sync path:
 * at-least-once delivery means redelivery is normal operation, not an edge case.
 *
 * <p><b>What is NOT retryable is as important as what is.</b> A payload that will not parse, or a
 * request the engine rejects as invalid, fails identically on every attempt — retrying it stalls
 * the partition behind one bad record while every later request for every other tenant waits. Those
 * go straight to the dead-letter topic, which is AC-EXEC-009a: a poison message is dead-lettered
 * and the messages behind it are not blocked.
 */
public class AsyncDecisionConsumer {

  private static final Logger log = LoggerFactory.getLogger(AsyncDecisionConsumer.class);

  private final DecideUseCase decide;
  private final KafkaTemplate<String, String> kafka;
  private final ObjectMapper json;
  private final String completedTopic;

  /**
   * Creates the consumer.
   *
   * @param decide the decision path, shared with the synchronous API so both answer identically
   * @param kafka the producer for completed decisions
   * @param json the codec
   * @param completedTopic where to publish the result
   */
  public AsyncDecisionConsumer(
      DecideUseCase decide,
      KafkaTemplate<String, String> kafka,
      ObjectMapper json,
      String completedTopic) {
    this.decide = decide;
    this.kafka = kafka;
    this.json = json;
    this.completedTopic = completedTopic;
  }

  /**
   * Decides one request and publishes the result.
   *
   * @param payload the event, as structured JSON
   */
  @KafkaListener(
      topics = "${bre.async.requested-topic:" + AsyncDecisionEvent.TOPIC + "}",
      groupId = "${bre.async.group:bre-decision-worker}")
  public void consume(String payload) {
    AsyncDecisionEvent event = parse(payload);

    // The SAME use case the synchronous API calls. Two code paths that both "decide" would
    // eventually disagree, and a lender would have one answer over REST and another over Kafka
    // for the same facts — which is the one difference nobody would think to test for.
    DecisionResponse response = decide.decide(event.toRequest(), event.decisionId());

    publish(event, response);
  }

  private AsyncDecisionEvent parse(String payload) {
    try {
      return json.readValue(payload, AsyncDecisionEvent.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
      // NOT retryable: the same bytes will not parse next time, so retrying stalls the partition
      // behind one bad record. Thrown as a conversion failure for the container to dead-letter.
      // The message names the problem and never the payload (§9.6).
      throw new ConversionException(
          "an async decision request is not valid JSON for this version of the schema", malformed);
    }
  }

  private void publish(AsyncDecisionEvent event, DecisionResponse response) {
    org.apache.kafka.clients.producer.ProducerRecord<String, String> message =
        new org.apache.kafka.clients.producer.ProducerRecord<>(
            completedTopic, event.partitionKey(), write(response));
    message.headers().add("ce_specversion", bytes("1.0"));
    message.headers().add("ce_type", bytes(AsyncDecisionEvent.COMPLETED_TOPIC));
    message.headers().add("ce_source", bytes("/bre/decision-worker"));
    message.headers().add("ce_id", bytes(response.decisionId()));
    message.headers().add("ce_subject", bytes(event.tenantId()));

    try {
      // Blocking, like the synchronous durable write and for the same reason: returning before the
      // broker acknowledged would let the offset commit for a result nobody received, and the
      // caller would wait for a completion event that was never published.
      kafka.send(message).get(10, java.util.concurrent.TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while publishing a completed decision");
    } catch (java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException failed) {
      // Retryable, deliberately: the decision IS recorded, so a retry re-reads it from the log by
      // requestId and republishes rather than deciding again.
      throw new IllegalStateException(
          "the decision was recorded but could not be published to " + completedTopic, failed);
    }
    log.debug("completed async decision {} for tenant {}", response.decisionId(), event.tenantId());
  }

  private String write(DecisionResponse response) {
    try {
      return json.writeValueAsString(response);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("could not serialise a completed decision", e);
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }
}
