package dev.bre.registry.api;

import dev.bre.registry.auth.Principal;
import dev.bre.registry.auth.Role;
import dev.bre.registry.deploy.DeploymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Requesting, approving, cancelling and rolling back deployments (00 §3.4). */
@RestController
@RequestMapping("/v1/deployments")
public class DeploymentController {

  private final DeploymentService deployments;

  /**
   * Creates the controller.
   *
   * @param deployments the deployment lifecycle
   */
  public DeploymentController(DeploymentService deployments) {
    this.deployments = deployments;
  }

  /**
   * A deployment request.
   *
   * @param envCode the environment
   * @param policyCode the policy
   * @param version the APPROVED version to deploy
   * @param mode champion, challenger or shadow; champion by default
   * @param reason why — required, because a deployment with no recorded reason is the one an
   *     auditor asks about
   * @param expedited an emergency change: two approvers and a mandatory post-implementation review.
   *     Boxed, not a primitive {@code boolean}: Jackson maps an ABSENT field to null when binding a
   *     record's canonical constructor, and null into a primitive is a 400 — so a client that
   *     simply omits an optional flag gets "JSON parse error" instead of the default. Boxed with an
   *     explicit default at the call site says "absent means false" in the one place that decides
   *     it.
   */
  public record DeployRequest(
      @NotBlank String envCode,
      @NotBlank String policyCode,
      int version,
      DeploymentService.Mode mode,
      @NotBlank String reason,
      Boolean expedited,
      // When it should take effect. Absent means now; a future instant means the deployment waits
      // in SCHEDULED until then, even once fully approved (00 §3.4). A lender scheduling a rate
      // change for the start of the next business day needs this to be a request field rather
      // than something an operator stays up to press a button for.
      java.time.OffsetDateTime effectiveFrom) {}

  /**
   * Requests a deployment, activating immediately when the environment needs no approvals.
   *
   * @param request the request
   * @param body the deployment
   * @return 201 with the deployment
   */
  @PostMapping
  public ResponseEntity<DeploymentService.Deployment> request(
      HttpServletRequest request, @Valid @RequestBody DeployRequest body) {
    Principal requester = Authorisation.require(request, Role.Capability.DEPLOY_REQUEST);
    DeploymentService.Deployment deployment =
        deployments.request(
            requester,
            body.envCode(),
            body.policyCode(),
            body.version(),
            body.mode() == null ? DeploymentService.Mode.CHAMPION : body.mode(),
            body.reason(),
            Boolean.TRUE.equals(body.expedited()),
            body.effectiveFrom());
    return ResponseEntity.status(HttpStatus.CREATED).body(deployment);
  }

  /**
   * An approval.
   *
   * @param comment the approver's comment
   */
  public record ApprovalRequest(String comment) {}

  /**
   * Approves a deployment, activating it when the count is met.
   *
   * @param request the request
   * @param deploymentId the deployment
   * @param body the comment
   * @return the deployment
   */
  @PostMapping("/{deploymentId}/approvals")
  public DeploymentService.Deployment approve(
      HttpServletRequest request,
      @PathVariable UUID deploymentId,
      @RequestBody(required = false) ApprovalRequest body) {
    Principal approver = Authorisation.require(request, Role.Capability.DEPLOY_APPROVE);
    return deployments.approve(approver, deploymentId, body == null ? null : body.comment());
  }

  /**
   * Cancels a deployment that has not activated.
   *
   * @param request the request
   * @param deploymentId the deployment
   * @return the deployment
   */
  @PostMapping("/{deploymentId}/cancel")
  public DeploymentService.Deployment cancel(
      HttpServletRequest request, @PathVariable UUID deploymentId) {
    Principal actor = Authorisation.require(request, Role.Capability.DEPLOY_REQUEST);
    return deployments.cancel(actor, deploymentId);
  }

  /**
   * A rollback.
   *
   * @param reason why — required
   */
  public record RollbackRequest(@NotBlank String reason) {}

  /**
   * Rolls back, by creating a new deployment pointing at the previous bundle.
   *
   * @param request the request
   * @param deploymentId the deployment to roll back
   * @param body the reason
   * @return the new deployment
   */
  @PostMapping("/{deploymentId}/rollback")
  public DeploymentService.Deployment rollback(
      HttpServletRequest request,
      @PathVariable UUID deploymentId,
      @Valid @RequestBody RollbackRequest body) {
    Principal actor = Authorisation.require(request, Role.Capability.DEPLOY_ROLLBACK);
    return deployments.rollback(actor, deploymentId, body.reason());
  }

  /**
   * Reads a deployment.
   *
   * @param request the request
   * @param deploymentId the deployment
   * @return the deployment
   */
  @GetMapping("/{deploymentId}")
  public DeploymentService.Deployment read(
      HttpServletRequest request, @PathVariable UUID deploymentId) {
    Principal caller = Authorisation.require(request, Role.Capability.POLICY_READ);
    return deployments.read(caller.tenantId(), deploymentId);
  }

  /**
   * Lists an environment's deployments, newest first.
   *
   * @param request the request
   * @param envCode the environment
   * @return the deployments
   */
  @GetMapping
  public List<DeploymentService.Deployment> list(
      HttpServletRequest request, @RequestParam String envCode) {
    Principal caller = Authorisation.require(request, Role.Capability.POLICY_READ);
    return deployments.list(caller.tenantId(), envCode);
  }

  /**
   * Everything waiting to take effect, across every environment, soonest first.
   *
   * <p>Deliberately not scoped to an environment: "what lands this week" is the question, and no
   * per-environment list can answer it.
   *
   * @param request the request
   * @return the pending and scheduled deployments
   */
  @GetMapping("/schedule")
  public List<DeploymentService.Deployment> schedule(HttpServletRequest request) {
    Principal caller = Authorisation.require(request, Role.Capability.POLICY_READ);
    return deployments.listDue(caller.tenantId());
  }
}
