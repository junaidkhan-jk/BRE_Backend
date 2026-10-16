package dev.bre.decision.async;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bre.decision.app.DecideUseCase;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Wires the async decision worker and its dead-letter handling (M4-06, FR-EXEC-009).
 *
 * <p>Only when {@code bre.async.enabled} is on. A listener container started against an
 * unconfigured broker retries a connection for ever and reports it as an application that is up,
 * which is a worse failure than refusing to start.
 */
@Configuration
@ConditionalOnProperty(name = "bre.async.enabled", havingValue = "true")
public class AsyncConfiguration {

  private static final Logger log = LoggerFactory.getLogger(AsyncConfiguration.class);

  /**
   * The consumer.
   *
   * @param decide the decision path, shared with the synchronous API
   * @param kafka the producer
   * @param json the codec
   * @param completedTopic where results are published
   * @return the consumer
   */
  @Bean
  public AsyncDecisionConsumer asyncDecisionConsumer(
      DecideUseCase decide,
      KafkaTemplate<String, String> kafka,
      ObjectMapper json,
      @Value("${bre.async.completed-topic:" + AsyncDecisionEvent.COMPLETED_TOPIC + "}")
          String completedTopic) {
    return new AsyncDecisionConsumer(decide, kafka, json, completedTopic);
  }

  /**
   * Retries, then dead-letters.
   *
   * <p><b>The split between what is retried and what is not is the whole of AC-EXEC-009a.</b> A
   * transient failure — the database briefly unreachable, a broker election — succeeds on the next
   * attempt, so three tries with a back-off is right. A payload that will not parse, or a request
   * the engine rejects as invalid, fails identically every time: retrying it stalls the partition
   * behind one bad record, and every later request for every other tenant on that partition waits
   * behind a message that can never succeed. Those are dead-lettered on the FIRST attempt.
   *
   * <p>The dead-letter topic is {@code <topic>.dlq} per 00 §3.9, on the same partition the record
   * came from — so a poison message's ordering context survives for whoever investigates it.
   *
   * @param kafka the producer used to publish dead letters
   * @return the error handler
   */
  @Bean
  public DefaultErrorHandler asyncErrorHandler(KafkaTemplate<String, String> kafka) {
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            kafka,
            (record, exception) -> {
              log.error(
                  "dead-lettering an async request from {}-{} offset {}: {}",
                  record.topic(),
                  record.partition(),
                  record.offset(),
                  exception.getClass().getSimpleName());
              // Same partition, so the ordering context of a poison message is intact for whoever
              // looks at it later.
              return new TopicPartition(record.topic() + ".dlq", record.partition());
            });

    // Three attempts with exponential back-off, per §4.3.
    ExponentialBackOff backOff = new ExponentialBackOff(500L, 2.0);
    backOff.setMaxAttempts(3);

    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);

    // Not retryable, ever. Each of these fails the same way on every attempt, so a retry buys
    // nothing and costs the partition.
    handler.addNotRetryableExceptions(
        ConversionException.class,
        com.fasterxml.jackson.core.JsonProcessingException.class,
        IllegalArgumentException.class,
        jakarta.validation.ValidationException.class);

    return handler;
  }
}
