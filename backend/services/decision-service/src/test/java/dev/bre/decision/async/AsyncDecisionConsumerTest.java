package dev.bre.decision.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.bre.decision.api.DecisionApi.DecisionRequest;
import dev.bre.decision.api.DecisionApi.DecisionResponse;
import dev.bre.decision.app.DecideUseCase;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.converter.ConversionException;

/**
 * The async decision worker (M4-06, FR-EXEC-009).
 *
 * <p>AC-EXEC-009a is about what happens to a message that can NEVER succeed, so that is what most
 * of this file asserts. A poison message retried for ever stalls its partition, and every later
 * request for every other tenant on that partition waits behind something that will not work — one
 * malformed payload taking out a shared cell's throughput.
 *
 * <p><b>Mocks here, against this codebase's habit, and the reason is the subject.</b> Everywhere
 * else the tests use the real thing — a real broker, a real PostgreSQL, a real HTTP server —
 * because the question was whether the plumbing works. The question here is a CLASSIFICATION: for
 * each kind of failure, does this consumer throw something the error handler will retry, or
 * something it will dead-letter? That is a property of what comes out of the method, and a real
 * broker would make it slower to assert without making it truer. {@code KafkaDecisionLogTest}
 * already proves the plumbing against a real broker.
 */
class AsyncDecisionConsumerTest {

  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private DecideUseCase decide;
  private KafkaTemplate<String, String> kafka;
  private AsyncDecisionConsumer consumer;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    decide = mock(DecideUseCase.class);
    kafka = mock(KafkaTemplate.class);
    when(kafka.send(anyRecord())).thenReturn(acknowledged());
    consumer = new AsyncDecisionConsumer(decide, kafka, JSON, "bre.decision.completed.v1");
  }

  @Test
  @DisplayName("a request is decided under the id the caller was already given")
  void decidesUnderThePreMintedId() throws Exception {
    // The id was minted when the 202 was returned, and the caller is holding it to GET later. A
    // worker minting its own would hand them a handle to nothing — a 404 that looks exactly like
    // data loss.
    String decisionId = "01a13f03-863b-7b1d-adf7-d194d43bc38f";
    when(decide.decide(any(DecisionRequest.class), any()))
        .thenReturn(response(decisionId, "APPROVE"));

    consumer.consume(JSON.writeValueAsString(event(decisionId, "req-1")));

    verify(decide).decide(any(DecisionRequest.class), org.mockito.ArgumentMatchers.eq(decisionId));
  }

  @Test
  @DisplayName("the completed decision is published with its CloudEvent attributes")
  void publishesTheCompletedDecision() throws Exception {
    when(decide.decide(any(DecisionRequest.class), any()))
        .thenReturn(response("01a13f03-863b-7b1d-adf7-d194d43bc38f", "DECLINE"));

    consumer.consume(JSON.writeValueAsString(event("01a13f03-863b-7b1d-adf7-d194d43bc38f", "r")));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<ProducerRecord<String, String>> sent =
        ArgumentCaptor.forClass(ProducerRecord.class);
    verify(kafka).send(sent.capture());

    assertThat(sent.getValue().topic()).isEqualTo("bre.decision.completed.v1");
    assertThat(sent.getValue().key())
        .as("keyed by tenant and application, so one application keeps its order")
        .isEqualTo("t_a:APP-1");
    assertThat(sent.getValue().value()).contains("DECLINE");
  }

  @Test
  @DisplayName("a payload that will not parse is NOT retryable: it is dead-lettered")
  void aMalformedPayloadIsNotRetried() {
    // AC-EXEC-009a. The same bytes fail identically on every attempt, so retrying buys nothing and
    // costs the partition — every later request behind it waits for a message that can never
    // succeed. ConversionException is what the error handler is configured never to retry.
    assertThatThrownBy(() -> consumer.consume("{this is not json"))
        .isInstanceOf(ConversionException.class)
        .hasMessageContaining("not valid JSON");

    // And nothing was decided or published, so a poison message cannot produce a partial result.
    verify(decide, never()).decide(any(DecisionRequest.class), any());
    verify(kafka, never()).send(anyRecord());
  }

  @Test
  @DisplayName("a message the engine rejects as invalid is not retried either")
  void anInvalidRequestIsNotRetried() throws Exception {
    // An IllegalArgumentException from the engine — an unknown policy, a malformed fact — is also
    // permanent. It is on the not-retryable list for the same reason, and this records that the
    // consumer lets it out rather than swallowing it into a silent success.
    when(decide.decide(any(DecisionRequest.class), any()))
        .thenThrow(new IllegalArgumentException("no such policy"));

    String payload = JSON.writeValueAsString(event("01a13f03-863b-7b1d-adf7-d194d43bc38f", "r"));
    assertThatThrownBy(() -> consumer.consume(payload))
        .isInstanceOf(IllegalArgumentException.class);
    verify(kafka, never()).send(anyRecord());
  }

  @Test
  @DisplayName("a redelivery re-emits the original decision rather than making a second")
  void redeliveryIsIdempotent() throws Exception {
    // At-least-once means redelivery is normal operation, not an edge case: a rebalance, a
    // redeployment, an offset that did not commit. DecideUseCase returns the existing decision for
    // a known requestId, so this is idempotent through the decision log rather than through a
    // second ledger that could disagree with it.
    String decisionId = "01a13f03-863b-7b1d-adf7-d194d43bc38f";
    when(decide.decide(any(DecisionRequest.class), any()))
        .thenReturn(response(decisionId, "APPROVE"));

    String payload = JSON.writeValueAsString(event(decisionId, "req-same"));
    consumer.consume(payload);
    consumer.consume(payload);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<ProducerRecord<String, String>> sent =
        ArgumentCaptor.forClass(ProducerRecord.class);
    verify(kafka, org.mockito.Mockito.times(2)).send(sent.capture());
    // Both publish the SAME decision id. The duplicate is the contract, not a defect — the
    // consumer deduplicates on ce-id.
    assertThat(sent.getAllValues())
        .allSatisfy(record -> assertThat(record.value()).contains(decisionId));
  }

  @Test
  @DisplayName("a publish failure is retryable, because the decision is already recorded")
  void aPublishFailureIsRetryable() throws Exception {
    // The opposite classification from a malformed payload, and the distinction matters: the
    // decision EXISTS. A retry re-reads it by requestId and republishes rather than deciding
    // again, so retrying is both safe and the only way the caller ever hears about it.
    when(decide.decide(any(DecisionRequest.class), any()))
        .thenReturn(response("01a13f03-863b-7b1d-adf7-d194d43bc38f", "APPROVE"));
    when(kafka.send(anyRecord()))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

    String payload = JSON.writeValueAsString(event("01a13f03-863b-7b1d-adf7-d194d43bc38f", "r"));
    assertThatThrownBy(() -> consumer.consume(payload))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not be published");
  }

  // --- plumbing --------------------------------------------------------------------------------

  /**
   * A typed matcher for the producer record.
   *
   * <p>One suppression on a helper rather than one at every call site. {@code KafkaTemplate} is
   * generic and {@code ProducerRecord.class} erases, so each raw {@code any(ProducerRecord.class)}
   * is an unchecked call — which -Werror makes fatal, correctly: an unchecked warning scattered
   * through a file is the kind nobody reads.
   */
  @SuppressWarnings("unchecked")
  private static ProducerRecord<String, String> anyRecord() {
    return any(ProducerRecord.class);
  }

  private static CompletableFuture<SendResult<String, String>> acknowledged() {
    RecordMetadata metadata = new RecordMetadata(new TopicPartition("t", 0), 0L, 0, 0L, 0, 0);
    return CompletableFuture.completedFuture(
        new SendResult<>(new ProducerRecord<>("t", "k", "v"), metadata));
  }

  private static AsyncDecisionEvent event(String decisionId, String requestId) {
    return new AsyncDecisionEvent(
        decisionId,
        "t_a",
        requestId,
        "2026-10-01",
        "PROD",
        "MFI_JLG_CREDIT_UW",
        null,
        Map.of("application", Map.of("applicationRef", "APP-1")));
  }

  private static DecisionResponse response(String decisionId, String outcome) {
    return new DecisionResponse(
        decisionId, "t_a", "r", LocalDate.of(2026, 10, 1), outcome, false, List.of());
  }
}
