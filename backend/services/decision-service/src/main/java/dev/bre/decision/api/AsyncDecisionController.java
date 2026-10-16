package dev.bre.decision.api;

import dev.bre.common.UuidV7;
import dev.bre.decision.api.DecisionApi.DecisionRequest;
import dev.bre.decision.async.AsyncDecisionEvent;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Async decisions for callers who hold no Kafka credentials (M4-06, FR-EXEC-009).
 *
 * <p><b>This endpoint is what makes a shared cell possible.</b> A dedicated cell's tenant may
 * produce to {@code bre.decision.requested.v1} directly, with their own ACLs. A shared-cell tenant
 * must not: giving them Kafka credentials on a cluster carrying other lenders' decisions means
 * their topic ACLs are the only thing between them and somebody else's credit data, and an ACL is a
 * configuration that can be wrong. So they POST here, over the same authenticated path as a
 * synchronous decision, and this service holds the only Kafka credentials. That is AC-EXEC-009b.
 *
 * <p><b>202 with a decisionId that is minted here, before the decision exists.</b> The caller is
 * being handed an identifier to GET later, so it has to be the one the decision is eventually
 * recorded under. A worker minting its own would give the caller a handle to nothing — a 404 that
 * looks exactly like data loss.
 */
@RestController
@RequestMapping("/v1/decisions:async")
// Only when a broker is named. The SYNCHRONOUS decision API must start with no Kafka at all —
// IN_MEMORY and POSTGRES modes need none — and adding this controller broke exactly that: every
// test of the sync API failed to load its context because this constructor demanded a
// KafkaTemplate. A deployment with no brokers configured now simply has no async endpoint, which
// is the honest answer rather than a service that refuses to start.
@org.springframework.boot.autoconfigure.condition.ConditionalOnBean(KafkaTemplate.class)
public class AsyncDecisionController {

  private static final Logger log = LoggerFactory.getLogger(AsyncDecisionController.class);

  private final KafkaTemplate<String, String> kafka;
  private final com.fasterxml.jackson.databind.ObjectMapper json;
  private final Clock clock;
  private final String topic;

  /**
   * Creates the controller.
   *
   * @param kafka the producer — the only Kafka credentials in the path
   * @param json the codec
   * @param clock for minting the identifier
   * @param topic where requests are published
   */
  public AsyncDecisionController(
      KafkaTemplate<String, String> kafka,
      com.fasterxml.jackson.databind.ObjectMapper json,
      Clock clock,
      @org.springframework.beans.factory.annotation.Value(
              "${bre.async.requested-topic:" + AsyncDecisionEvent.TOPIC + "}")
          String topic) {
    this.kafka = kafka;
    this.json = json;
    this.clock = clock;
    this.topic = topic;
  }

  /**
   * Accepts a request for later decision.
   *
   * @param request the decision request
   * @return 202 with the identifier the decision will be recorded under
   */
  @PostMapping
  public ResponseEntity<Map<String, Object>> accept(@Valid @RequestBody DecisionRequest request) {
    String decisionId = UuidV7.generate(clock.instant()).toString();

    AsyncDecisionEvent event =
        new AsyncDecisionEvent(
            decisionId,
            request.tenantId(),
            request.requestId(),
            request.asOf() == null ? null : request.asOf().toString(),
            request.environment(),
            request.policyCode(),
            request.bundleHash(),
            request.facts());

    try {
      // Blocking on the broker's acknowledgement before answering 202. A 202 for a request that
      // never reached the topic is the worst answer this endpoint can give: the caller believes
      // the decision is coming and polls a decisionId that will never exist.
      kafka
          .send(topic, event.partitionKey(), json.writeValueAsString(event))
          .get(10, java.util.concurrent.TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while accepting an async decision");
    } catch (Exception failed) {
      throw new IllegalStateException(
          "the request could not be accepted for async decision, so it was not acknowledged."
              + " decisionId="
              + decisionId,
          failed);
    }

    log.info(
        "accepted async decision tenant={} requestId={} decisionId={}",
        request.tenantId(),
        request.requestId(),
        decisionId);

    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .header("Location", "/v1/decisions/" + decisionId)
        .body(
            Map.of(
                "decisionId",
                decisionId,
                "status",
                "ACCEPTED",
                "detail",
                "The decision will be made shortly. GET the Location header, or consume"
                    + " bre.decision.completed.v1 if you hold credentials for it."));
  }
}
