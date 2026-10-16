package dev.bre.decision.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * Consumes {@code bre.decision.recorded.v1} into the hot store (M4-02).
 *
 * <p><b>This is the read side of Kafka-first mode.</b> The topic is the system of record and
 * answers no questions — "show me decision 7" is not an operation on a partition — so every lookup,
 * every explanation and every regulator's query is served from the rows this writes.
 *
 * <p><b>A duplicate is expected, and is not an error.</b> Delivery is at-least-once: a rebalance, a
 * redeployment or an offset commit that did not land all redeliver. The decision id is the primary
 * key, so the second write is refused by the database and swallowed here. That is the design —
 * making the sink idempotent by relying on the key it already has, rather than by tracking offsets
 * in a second place that can disagree with Kafka's.
 *
 * <p><b>The archive is written alongside the hot store, and a failure there is not swallowed.</b>
 * §6.1.5's archive is the copy that outlives the hot store's ninety days and answers a regulator in
 * year nine. Writing it on a best-effort basis would produce an archive with holes in it, and a
 * reader cannot tell a missing decision from one that was never made.
 *
 * <p><b>What this deliberately does not do is skip a record it cannot write.</b> A failure that is
 * not a duplicate rethrows, so the container's error handler retries and ultimately dead-letters
 * rather than advancing past it. A sink that logged and continued would leave a decision in Kafka
 * and absent from the store, and the only sign would be a lookup returning nothing years later.
 */
public class DecisionSink {

  private static final Logger log = LoggerFactory.getLogger(DecisionSink.class);

  private final OutboxDecisionLog store;
  private final ObjectMapper json;
  private final dev.bre.decision.archive.ParquetDecisionArchive archive;

  /**
   * Creates the sink.
   *
   * @param store the hot store, written through compact mode's own insert path minus its outbox
   * @param json the mapper, with the time module the event's Instants need
   * @param archive the WORM Parquet archive, or null when none is configured
   */
  public DecisionSink(
      OutboxDecisionLog store,
      ObjectMapper json,
      dev.bre.decision.archive.ParquetDecisionArchive archive) {
    this.store = store;
    this.json = json;
    this.archive = archive;
  }

  /**
   * Writes one decision.
   *
   * @param payload the event, as structured JSON
   */
  @KafkaListener(
      topics = "${bre.decision-log.kafka.topic:bre.decision.recorded.v1}",
      groupId = "${bre.decision-log.kafka.sink-group:bre-decision-sink}")
  public void consume(String payload) {
    DecisionEvent event;
    try {
      event = json.readValue(payload, DecisionEvent.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
      // Unparseable is NOT retryable: the same bytes will not parse on the next attempt either, so
      // retrying would stall the partition behind one bad record forever. Rethrown as a
      // non-retryable failure for the container to dead-letter, which is where somebody can look
      // at it. The message names the problem and never the payload (§9.6).
      throw new org.springframework.kafka.support.converter.ConversionException(
          "a decision event on the topic is not valid JSON for this version of the schema",
          malformed);
    }

    try {
      store.appendWithoutOutbox(event.toRecord());
    } catch (DuplicateKeyException alreadyWritten) {
      // At-least-once doing what it says. Logged at DEBUG: on a rebalance this is every record in
      // flight, and at INFO it would bury the log in the one situation somebody is reading it.
      log.debug("decision {} was already in the hot store", event.decisionId());
    }

    if (archive != null) {
      // After the hot store, and deliberately NOT in a try that swallows. A duplicate reaching
      // the archive is handled at compaction by decisionId, as §6.1.5 says; a decision that never
      // reaches it is a hole nobody can see.
      archive.add(event, java.time.Instant.now());
    }
  }
}
