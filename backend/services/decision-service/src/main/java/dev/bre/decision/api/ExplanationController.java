package dev.bre.decision.api;

import dev.bre.decision.explain.ReplayService;
import dev.bre.decision.log.DecisionLog;
import dev.bre.facts.LendingFacts;
import dev.bre.facts.Masker;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/decisions/{id}/explanation} — why a decision came out that way (LLD §6.4, §9.4).
 *
 * <p>The explanation is produced by <b>replaying</b> the decision against its own stored snapshot
 * and its own pinned bundle, then comparing (D7). That is what makes it an explanation rather than
 * a reconstruction: if the replay disagrees with the record, the response says so instead of
 * presenting the replay as what happened.
 *
 * <p><b>The masked view is the only view, for now.</b> §9.4 lets an {@code AUDITOR} unmask with an
 * {@code X-Unmask-Reason} header, recording a {@code DECISION.UNMASKED} audit event. That is not
 * implemented here and <b>is not a small omission left for later</b>: unmasking is a privilege, and
 * there is no authentication anywhere in this service yet (M3-09) — {@code X-Tenant-Id} is an
 * unverified header. An unmask path gated on nothing would be an unmask path available to anyone
 * who can reach the port. So the endpoint masks unconditionally, and the capability arrives with
 * the identity provider that can tell an auditor from a stranger.
 */
@RestController
@RequestMapping("/v1/decisions/{decisionId}/explanation")
public class ExplanationController {

  private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
      new com.fasterxml.jackson.databind.ObjectMapper();

  private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> MAP_TYPE =
      new com.fasterxml.jackson.core.type.TypeReference<>() {};

  private final DecisionLog decisionLog;
  private final ReplayService replayService;

  /**
   * Creates the controller.
   *
   * @param decisionLog where the decision and its snapshot come from
   * @param replayService the replay
   */
  public ExplanationController(DecisionLog decisionLog, ReplayService replayService) {
    this.decisionLog = decisionLog;
    this.replayService = replayService;
  }

  /**
   * An explanation.
   *
   * @param decisionId the decision
   * @param decision the decision as recorded
   * @param matched whether the replay agreed with the record
   * @param mismatches each disagreement, including {@code SHREDDED} and {@code INTEGRITY} reasons
   * @param bundleHash the policy bundle the explanation was produced against
   * @param steps each component's replayed status
   * @param facts the fact snapshot, <b>masked</b> per §9.4, as plain maps and lists
   */
  public record Explanation(
      String decisionId,
      String decision,
      boolean matched,
      List<String> mismatches,
      String bundleHash,
      List<String> steps,
      // Plain structures, not a JsonNode. Returning a Jackson 2 JsonNode made the HTTP converter
      // bean-serialise it — the response carried {"array":false,"bigDecimal":false,...} instead of
      // the facts — because the converter's mapper is not the one that built the node. Converting
      // here removes the coupling between this API's wire format and whichever Jackson the web
      // layer happens to use.
      Map<String, Object> facts) {

    /** Creates an explanation, copying the lists. */
    public Explanation {
      mismatches = List.copyOf(mismatches);
      steps = List.copyOf(steps);
    }
  }

  /**
   * Explains a decision.
   *
   * @param tenantId the tenant, from {@code X-Tenant-Id}
   * @param decisionId the decision
   * @return the explanation; 404 when the decision is not this tenant's, 409 when its facts were
   *     never stored
   */
  @GetMapping
  public ResponseEntity<?> explain(
      @RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String decisionId) {

    var record = decisionLog.find(tenantId, decisionId);
    if (record.isEmpty()) {
      // A correct id under the wrong tenant is a 404, not a 403 (D1): the answer that leaks least.
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    Optional<ReplayService.Replay> replayed = replayService.replayFromStore(record.get(), true);
    if (replayed.isEmpty()) {
      // Not a 404: the decision exists. 409, because the state of the resource is the problem —
      // this installation chose not to keep the snapshot, and no retry changes that.
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(
              ReplayService.cannotReplay(Optional.empty())
                  .orElse("this decision cannot be explained"));
    }

    ReplayService.Replay replay = replayed.get();
    Map<String, Object> maskedFacts =
        decisionLog
            .facts(tenantId, decisionId)
            .map(
                stored ->
                    MAPPER.convertValue(
                        new Masker(LendingFacts.schema())
                            // Masked at the asOf the DECISION was taken on, not today: an AGE_BAND
                            // computed from today would make an old explanation change every
                            // birthday.
                            .mask(stored.facts(), record.get().response().asOf()),
                        MAP_TYPE))
            .orElse(null);

    return ResponseEntity.ok(
        new Explanation(
            decisionId,
            record.get().response().decision(),
            replay.matched(),
            replay.mismatches(),
            replay.bundleHash(),
            replay.steps().stream().map(step -> step.ref() + ": " + step.status()).toList(),
            maskedFacts));
  }
}
