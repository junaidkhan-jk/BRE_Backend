package dev.bre.registry.deploy;

import dev.bre.registry.audit.AuditLog;
import dev.bre.registry.auth.Principal;
import dev.bre.registry.auth.Role;
import dev.bre.registry.policy.PolicyVersionService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The deployment lifecycle (00 §3.4).
 *
 * <pre>
 *   PENDING_APPROVAL ──approvals(n)──▶ SCHEDULED ──effective──▶ ACTIVE ──next──▶ SUPERSEDED
 *          │                                                        │
 *          └──cancel──▶ CANCELLED                                   └──rollback──▶ ROLLED_BACK
 * </pre>
 *
 * <p><b>Nothing is ever edited in place.</b> A rollback is a <em>new deployment</em> pointing at a
 * previous bundle, not a mutation of the current one. That is the difference between a system that
 * can answer "what was serving at 14:30 last Tuesday" and one that cannot — and for a lender that
 * question arrives from a regulator, not from an engineer.
 *
 * <p><b>Approval counts come from the tenant, per environment.</b> 00 §3.3 defaults them to DEV 0,
 * UAT 1, PROD 2. DEV being zero is the useful part: a change goes to DEV immediately and needs two
 * people to reach PROD, so the control sits where the risk is instead of slowing down every step
 * equally.
 *
 * <p><b>A deployment approver can be neither the version's author nor its reviewer</b> (00 §3.3).
 * Three distinct people for a PROD change, which is the point — and the administrator override
 * applies here too, audited the same way.
 *
 * <p><b>An effective date in the future means SCHEDULED, not ACTIVE.</b> Approving the last
 * required approval does not put a policy live if the author asked for it to start on Monday: the
 * deployment waits in SCHEDULED and {@link DeploymentActivator} promotes it when the time comes. A
 * lender scheduling a rate change for the start of the next business day and getting it mid-day is
 * not a cosmetic bug — every application decided in between was decided under the wrong policy.
 *
 * <p><b>Activation publishes a signed Active Pointer Manifest.</b> Until that manifest is written
 * and signed, a deployment row is a statement of intent that no pod can see: pods adopt policy from
 * the manifest and from nothing else (§8.1, §8.5). So the two happen in one transaction, and a
 * manifest that cannot be signed rolls the activation back.
 */
@Service
public class DeploymentService {

  private static final Logger log = LoggerFactory.getLogger(DeploymentService.class);

  private final DeploymentRepository repository;
  private final ManifestPublisher manifests;
  private final AuditLog audit;

  /**
   * Creates the service.
   *
   * @param repository the deployment tables
   * @param manifests publishes the Active Pointer Manifest
   * @param audit the hash-chained audit log
   */
  public DeploymentService(
      DeploymentRepository repository, ManifestPublisher manifests, AuditLog audit) {
    this.repository = repository;
    this.manifests = manifests;
    this.audit = audit;
  }

  /** How a bundle is served (§5.2). */
  public enum Mode {
    /** The default for everybody. */
    CHAMPION,
    /** Served to a bucket range, against an experiment (§4.11). */
    CHALLENGER,
    /** Evaluated but not returned, for comparison. */
    SHADOW
  }

  /**
   * A deployment, as the API returns it.
   *
   * @param deploymentId the identifier
   * @param envCode the environment
   * @param policyCode the policy
   * @param bundleHash the bundle being served
   * @param mode champion, challenger or shadow
   * @param status the lifecycle state
   * @param approvals how many approvals are in
   * @param approvalsRequired how many this environment needs
   * @param manifestVersion the manifest version published on activation, when it has activated
   */
  public record Deployment(
      UUID deploymentId,
      String envCode,
      String policyCode,
      String bundleHash,
      Mode mode,
      String status,
      int approvals,
      int approvalsRequired,
      Optional<Long> manifestVersion) {}

  /**
   * Requests a deployment of an approved version.
   *
   * <p>Activates immediately when the environment needs no approvals — which DEV does by default.
   * The alternative, requiring a self-approval in DEV, teaches people that approving is a formality
   * and that habit does not stay in DEV.
   *
   * @param principal who is requesting
   * @param envCode the environment
   * @param policyCode the policy
   * @param version the approved version to deploy
   * @param mode champion, challenger or shadow
   * @param reason why — recorded, and required
   * @param expedited whether this is an emergency change (2 approvers, mandatory
   *     post-implementation review)
   * @param effectiveFrom when it should take effect, or null for immediately. A future date means
   *     the deployment waits in SCHEDULED once fully approved, and {@link DeploymentActivator}
   *     promotes it when the time comes (00 §3.4).
   * @return the deployment
   */
  @Transactional
  public Deployment request(
      Principal principal,
      String envCode,
      String policyCode,
      int version,
      Mode mode,
      String reason,
      boolean expedited,
      java.time.OffsetDateTime effectiveFrom) {

    String tenantId = principal.tenantId();
    DeploymentRepository.ApprovedVersion approved =
        repository
            .findApprovedVersion(tenantId, policyCode, version)
            .orElseThrow(
                () ->
                    new PolicyVersionService.LifecycleException(
                        "version "
                            + version
                            + " of "
                            + policyCode
                            + " is not APPROVED, so it cannot be deployed. Only an approved"
                            + " version has a signed bundle, and a pod will not serve anything"
                            + " else (§8.3)."));

    if (!repository.environmentExists(tenantId, envCode)) {
      throw new PolicyVersionService.LifecycleException(
          "no environment " + envCode + " for this tenant. Environments are tenant settings.");
    }

    UUID deploymentId = dev.bre.common.UuidV7.generate(java.time.Instant.now());
    int required = repository.approvalsRequired(tenantId, envCode, expedited);

    repository.insert(
        tenantId,
        deploymentId,
        envCode,
        policyCode,
        approved.bundleHash(),
        mode,
        reason,
        principal.subject(),
        expedited,
        effectiveFrom);

    audit.record(
        tenantId,
        principal.subject(),
        "DEPLOYMENT.REQUESTED",
        "deployment",
        deploymentId.toString(),
        "{\"env\":\""
            + envCode
            + "\",\"policy\":\""
            + policyCode
            + "\",\"version\":"
            + version
            + ",\"bundleHash\":\""
            + approved.bundleHash()
            + "\",\"approvalsRequired\":"
            + required
            + ",\"expedited\":"
            + expedited
            + ",\"effectiveFrom\":\""
            + (effectiveFrom == null ? "immediate" : effectiveFrom)
            + "\"}");

    if (required == 0) {
      return activateOrSchedule(tenantId, deploymentId, principal, 0, required);
    }
    return read(tenantId, deploymentId);
  }

  /**
   * Approves a deployment, activating it when the count is met.
   *
   * @param principal who is approving
   * @param deploymentId the deployment
   * @param comment their comment
   * @return the deployment
   */
  @Transactional
  public Deployment approve(Principal principal, UUID deploymentId, String comment) {
    String tenantId = principal.tenantId();
    DeploymentRepository.Row row = require(tenantId, deploymentId);

    if (!"PENDING_APPROVAL".equals(row.status())) {
      throw new PolicyVersionService.LifecycleException(
          "deployment "
              + deploymentId
              + " is "
              + row.status()
              + ", so there is nothing to approve.");
    }

    // 00 §3.3: an approver can be neither the version's author nor its reviewer. Three people for
    // a PROD change. The requester is checked too — approving your own request is the same control.
    List<String> conflicted = repository.authorsAndReviewersOf(tenantId, deploymentId);
    boolean isOwnWork =
        conflicted.contains(principal.subject()) || row.requestedBy().equals(principal.subject());

    if (isOwnWork) {
      if (!principal.mayOverrideSegregationOfDuties()) {
        throw new PolicyVersionService.LifecycleException(
            "a deployment approver can be neither the version's author nor its reviewer, nor the"
                + " person who requested the deployment (00 §3.3). "
                + principal.subject()
                + " is one of those, so somebody else has to approve this."
                + (principal.can(Role.Capability.SOD_OVERRIDE)
                    ? " This tenant has allow_admin_sod_override switched off, so an administrator"
                        + " cannot bypass it either."
                    : ""));
      }
      audit.record(
          tenantId,
          principal.subject(),
          "DEPLOYMENT.SOD_OVERRIDDEN",
          "deployment",
          deploymentId.toString(),
          "{\"control\":\"APPROVER_IS_AUTHOR_OR_REQUESTER\",\"authority\":\"TENANT_ADMIN\"}");
      log.warn(
          "SEGREGATION OF DUTIES OVERRIDDEN: {} is approving a deployment they authored or"
              + " requested. tenant={} deployment={}. Permitted by allow_admin_sod_override and"
              + " recorded in the audit log.",
          principal.subject(),
          tenantId,
          deploymentId);
    }

    repository.recordApproval(tenantId, deploymentId, principal.subject(), comment);
    int approvals = repository.approvalCount(tenantId, deploymentId);
    int required = repository.approvalsRequired(tenantId, row.envCode(), row.expedited());

    audit.record(
        tenantId,
        principal.subject(),
        "DEPLOYMENT.APPROVED",
        "deployment",
        deploymentId.toString(),
        "{\"approvals\":" + approvals + ",\"required\":" + required + "}");

    if (approvals >= required) {
      return activateOrSchedule(tenantId, deploymentId, principal, approvals, required);
    }
    return read(tenantId, deploymentId);
  }

  /**
   * Cancels a deployment that has not activated.
   *
   * @param principal who is cancelling
   * @param deploymentId the deployment
   * @return the deployment
   */
  @Transactional
  public Deployment cancel(Principal principal, UUID deploymentId) {
    String tenantId = principal.tenantId();
    DeploymentRepository.Row row = require(tenantId, deploymentId);
    if (!"PENDING_APPROVAL".equals(row.status()) && !"SCHEDULED".equals(row.status())) {
      throw new PolicyVersionService.LifecycleException(
          "only a PENDING_APPROVAL or SCHEDULED deployment can be cancelled; this one is "
              + row.status()
              + ". An ACTIVE deployment is replaced by a rollback, which is a new deployment —"
              + " nothing is edited in place (00 §3.4).");
    }
    repository.setStatus(tenantId, deploymentId, "CANCELLED");
    audit.record(
        tenantId,
        principal.subject(),
        "DEPLOYMENT.CANCELLED",
        "deployment",
        deploymentId.toString(),
        "{}");
    return read(tenantId, deploymentId);
  }

  /**
   * Rolls back to a previous bundle by creating a new deployment (00 §3.4).
   *
   * @param principal who is rolling back
   * @param deploymentId the deployment to roll back from
   * @param reason why — required, because a rollback is the event most worth explaining later
   * @return the new deployment
   */
  @Transactional
  public Deployment rollback(Principal principal, UUID deploymentId, String reason) {
    String tenantId = principal.tenantId();
    DeploymentRepository.Row row = require(tenantId, deploymentId);
    if (!"ACTIVE".equals(row.status())) {
      throw new PolicyVersionService.LifecycleException(
          "only an ACTIVE deployment can be rolled back; this one is " + row.status() + ".");
    }

    String previousBundle =
        repository
            .previousActiveBundle(tenantId, row.envCode(), row.policyCode(), deploymentId)
            .orElseThrow(
                () ->
                    new PolicyVersionService.LifecycleException(
                        "there is no earlier deployment of "
                            + row.policyCode()
                            + " in "
                            + row.envCode()
                            + " to roll back to. Deploy a different approved version instead."));

    UUID rollbackId = dev.bre.common.UuidV7.generate(java.time.Instant.now());
    repository.insertRollback(
        tenantId,
        /* deploymentId= */ rollbackId,
        row.envCode(),
        row.policyCode(),
        previousBundle,
        reason,
        principal.subject(),
        /* rollbackOf= */ deploymentId);

    repository.setStatus(tenantId, deploymentId, "ROLLED_BACK");
    audit.record(
        tenantId,
        principal.subject(),
        "DEPLOYMENT.ROLLED_BACK",
        "deployment",
        deploymentId.toString(),
        "{\"replacedBy\":\"" + rollbackId + "\",\"bundleHash\":\"" + previousBundle + "\"}");

    // A rollback activates immediately. Waiting for approvals on a rollback is the wrong trade: the
    // state being rolled back FROM is the one somebody has already decided is wrong, and the state
    // being rolled back TO was approved and deployed before. The audit entry is the control.
    return activate(tenantId, rollbackId, principal, 0, 0);
  }

  /**
   * Reads a deployment.
   *
   * @param tenantId the tenant
   * @param deploymentId the deployment
   * @return the deployment
   */
  @Transactional(readOnly = true)
  public Deployment read(String tenantId, UUID deploymentId) {
    DeploymentRepository.Row row = require(tenantId, deploymentId);
    return new Deployment(
        deploymentId,
        row.envCode(),
        row.policyCode(),
        row.bundleHash(),
        Mode.valueOf(row.mode()),
        row.status(),
        repository.approvalCount(tenantId, deploymentId),
        repository.approvalsRequired(tenantId, row.envCode(), row.expedited()),
        row.manifestVersion());
  }

  /**
   * Lists an environment's deployments, newest first.
   *
   * @param tenantId the tenant
   * @param envCode the environment
   * @return the deployments
   */
  @Transactional(readOnly = true)
  public List<Deployment> list(String tenantId, String envCode) {
    return repository.listIds(tenantId, envCode).stream().map(id -> read(tenantId, id)).toList();
  }

  // ---------------------------------------------------------------------------------------------

  /**
   * Activates now, or schedules for the effective date — 00 §3.4's SCHEDULED state.
   *
   * <p>The comparison is against the database's clock, not the JVM's, so two registry instances
   * with drifting clocks cannot disagree about whether a deployment is due.
   */
  private Deployment activateOrSchedule(
      String tenantId, UUID deploymentId, Principal principal, int approvals, int required) {

    if (repository.isFutureDated(tenantId, deploymentId)) {
      repository.schedule(tenantId, deploymentId);
      audit.record(
          tenantId,
          principal.subject(),
          "DEPLOYMENT.SCHEDULED",
          "deployment",
          deploymentId.toString(),
          "{\"approvals\":" + approvals + ",\"required\":" + required + "}");
      log.info(
          "deployment SCHEDULED tenant={} deployment={} — fully approved, waiting for its"
              + " effective date",
          tenantId,
          deploymentId);
      return read(tenantId, deploymentId);
    }
    return activate(tenantId, deploymentId, principal, approvals, required);
  }

  /**
   * Activates a deployment: supersede the champion it replaces, publish the manifest, audit it.
   *
   * <p>Package-private so {@link DeploymentActivator} can call it for a deployment whose time has
   * come, rather than reimplementing superseding and manifest publication on the timer path — two
   * implementations of "what it means to go live" would eventually disagree.
   */
  Deployment activate(
      String tenantId, UUID deploymentId, Principal principal, int approvals, int required) {

    DeploymentRepository.Row row = require(tenantId, deploymentId);

    // Supersede the champion this one replaces BEFORE activating, because §5.2's exclusion
    // constraint allows one ACTIVE champion per (tenant, env, policy). The constraint is DEFERRABLE
    // INITIALLY DEFERRED precisely so the order within the transaction does not matter, but doing
    // it in the sensible order means a mistake surfaces as a constraint violation rather than as
    // two champions briefly coexisting.
    if ("CHAMPION".equals(row.mode())) {
      int superseded =
          repository.supersedeActiveChampions(
              tenantId, row.envCode(), row.policyCode(), deploymentId);
      if (superseded > 0) {
        audit.recordSystem(
            tenantId,
            "DEPLOYMENT.SUPERSEDED",
            "deployment",
            deploymentId.toString(),
            "{\"superseded\":" + superseded + "}");
      }
    }

    repository.activate(tenantId, deploymentId);

    // The manifest is what pods actually read. A deployment row nobody can see is not a deployment.
    long manifestVersion = manifests.publish(tenantId);
    repository.setManifestVersion(tenantId, deploymentId, manifestVersion);

    audit.record(
        tenantId,
        principal.subject(),
        "DEPLOYMENT.ACTIVATED",
        "deployment",
        deploymentId.toString(),
        "{\"manifestVersion\":"
            + manifestVersion
            + ",\"approvals\":"
            + approvals
            + ",\"required\":"
            + required
            + "}");
    log.info(
        "deployment ACTIVE tenant={} env={} policy={} bundle={} manifestVersion={}",
        tenantId,
        row.envCode(),
        row.policyCode(),
        row.bundleHash(),
        manifestVersion);

    return read(tenantId, deploymentId);
  }

  private DeploymentRepository.Row require(String tenantId, UUID deploymentId) {
    return repository
        .find(tenantId, deploymentId)
        .orElseThrow(
            () ->
                new PolicyVersionService.LifecycleException(
                    "no deployment " + deploymentId + " for this tenant."));
  }
}
