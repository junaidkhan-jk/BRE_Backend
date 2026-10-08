package dev.bre.decision.api;

import dev.bre.decision.api.DecisionApi.DecisionRequest;
import dev.bre.decision.api.DecisionApi.DecisionResponse;
import dev.bre.decision.app.DecideUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The sync Decision API over REST (LLD §6.2).
 *
 * <p>A gRPC surface over the same use case is the other half of D12 and arrives with {@code
 * bre-contracts} in M2/M3; the handler logic lives in {@link DecideUseCase} precisely so that the
 * second transport adds no second copy of the decision path.
 */
@RestController
@RequestMapping("/v1/decisions")
public class DecisionController {

  private final DecideUseCase decideUseCase;

  /**
   * Creates the controller.
   *
   * @param decideUseCase the decision path
   */
  public DecisionController(DecideUseCase decideUseCase) {
    this.decideUseCase = decideUseCase;
  }

  /**
   * Evaluates a policy against a fact snapshot and records the decision.
   *
   * @param request the decision request
   * @return the decision, with 201 and a Location header
   */
  @PostMapping
  public ResponseEntity<DecisionResponse> decide(@Valid @RequestBody DecisionRequest request) {
    DecisionResponse response = decideUseCase.decide(request);
    return ResponseEntity.created(java.net.URI.create("/v1/decisions/" + response.decisionId()))
        .body(response);
  }

  /**
   * Reads a recorded decision.
   *
   * <p>The tenant comes from a header rather than the path, and the lookup is scoped by it: a
   * decision is never readable across tenants (D1), so a correct id under the wrong tenant is a
   * 404, not a 403 — which is also the answer that leaks least.
   *
   * @param tenantId the tenant, from {@code X-Tenant-Id}
   * @param decisionId the decision identifier
   * @return the decision, or 404
   */
  @GetMapping("/{decisionId}")
  public ResponseEntity<DecisionResponse> find(
      @RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String decisionId) {
    return decideUseCase
        .find(tenantId, decisionId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
  }
}
