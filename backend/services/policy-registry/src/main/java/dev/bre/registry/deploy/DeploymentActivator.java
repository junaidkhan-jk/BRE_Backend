package dev.bre.registry.deploy;

import dev.bre.common.TenantContext;
import dev.bre.registry.audit.AuditLog;
import dev.bre.registry.auth.Principal;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Promotes SCHEDULED deployments to ACTIVE when their effective date arrives (00 §3.4, M3-06).
 *
 * <p><b>Why a timer and not a request.</b> A deployment's effective date is the moment a lender
 * chose for a policy change to take effect — the start of the next business day, the first of the
 * month, after a bureau cutover. Nobody is going to be at a keyboard at 00:00 to press a button,
 * and asking somebody to be is how a scheduled change becomes an unscheduled one.
 *
 * <p><b>Each deployment is claimed before it is activated.</b> Two registry instances polling the
 * same table at the same second is the normal case. The claim is a {@code FOR UPDATE SKIP LOCKED}
 * update, so a row is handed to exactly one instance; without it both would publish a manifest and
 * both would audit an activation, and the superseding race's loser would be whichever transaction
 * committed second.
 *
 * <p><b>It activates through {@link DeploymentService#activate}</b> rather than writing the status
 * itself. Going live means superseding the champion this replaces, publishing a signed manifest and
 * recording the audit entry; a timer path that did two of those three would produce a deployment
 * the database calls ACTIVE and no pod can see.
 *
 * <p><b>The tenant scope and the transaction are both established explicitly.</b> A
 * {@code @Transactional} method called from another method of the same bean is not proxied, so the
 * annotation would do nothing and every statement would autocommit on its own connection — which
 * also means the transaction-local {@code bre.tenant_id} would be gone by the next statement, row
 * level security would filter everything, and the activator would report no due deployments forever
 * while the database plainly held one. So: {@link TenantContext} (which {@code
 * TenantScopedDataSource} reads when it hands out a connection) and an explicit {@link
 * TransactionTemplate}.
 *
 * <p><b>The actor is the platform, and says so.</b> The audit entry for a timed activation names
 * {@code system:deployment-activator}, not the person who approved it hours earlier. An auditor
 * reading the chain can then tell a human activation from a scheduled one, which is exactly the
 * distinction the effective date exists to make.
 */
@Component
public class DeploymentActivator {

  private static final Logger log = LoggerFactory.getLogger(DeploymentActivator.class);

  /** The actor recorded for an activation nobody triggered. */
  public static final String SYSTEM_ACTOR = "system:deployment-activator";

  /**
   * How many deployments one pass takes per tenant.
   *
   * <p>Bounded so a backlog — a registry that was down over a weekend with fifty scheduled changes
   * — cannot hold a transaction open publishing fifty manifests. The next pass takes the next
   * batch, seconds later.
   */
  private static final int BATCH = 20;

  private final DeploymentService deployments;
  private final DeploymentRepository repository;
  private final TransactionTemplate transactions;
  private final AuditLog audit;
  private final String instanceId;

  /**
   * Creates the activator.
   *
   * @param deployments the deployment service, which owns what activation means
   * @param repository the deployment tables
   * @param transactionManager makes each tenant's activation one transaction
   * @param audit the audit log
   * @param instanceId identifies this instance on a claimed row; the pod name in a cluster
   */
  public DeploymentActivator(
      DeploymentService deployments,
      DeploymentRepository repository,
      org.springframework.transaction.PlatformTransactionManager transactionManager,
      AuditLog audit,
      @Value("${bre.instance-id:local}") String instanceId) {
    this.deployments = deployments;
    this.repository = repository;
    this.transactions = new TransactionTemplate(transactionManager);
    this.audit = audit;
    this.instanceId = instanceId;
  }

  /**
   * Looks for deployments whose time has come.
   *
   * <p>Every thirty seconds. The granularity a lender cares about is "the start of the business
   * day", so a sub-minute poll is already far finer than the requirement — and a tighter loop would
   * buy nothing but load on a table that is almost always empty of due rows.
   */
  @Scheduled(fixedDelayString = "${bre.deployment.activation-poll-ms:30000}")
  public void activateDueDeployments() {
    List<String> tenants;
    try {
      tenants = repository.tenantsWithDueDeployments();
    } catch (RuntimeException unavailable) {
      // The exception, not just its message. A getMessage()-only log here cost half an hour:
      // "bad SQL grammar" is Spring's translation of a whole family of SQLStates, and the nested
      // cause — which names the actual object and the actual problem — was the only useful part.
      log.warn("could not look for due deployments", unavailable);
      return;
    }

    for (String tenantId : tenants) {
      try {
        activateFor(tenantId);
      } catch (RuntimeException failed) {
        // One tenant's failure must not stop the others'. A manifest that cannot be written for
        // one lender is that lender's incident; the next tenant's scheduled change is unrelated.
        log.error(
            "scheduled activation failed for tenant={} — the deployment stays SCHEDULED and the"
                + " next pass will retry it",
            tenantId,
            failed);
      }
    }
  }

  /**
   * Claims and activates every due deployment for one tenant.
   *
   * <p>One transaction per tenant, not one spanning all of them: a failure for one lender must not
   * roll back another's activation, and the audit chain head is taken {@code FOR UPDATE} per
   * tenant, so a single long transaction would serialise every tenant's writes behind the slowest.
   *
   * @param tenantId the tenant
   */
  public void activateFor(String tenantId) {
    TenantContext.runIn(
        tenantId,
        (Runnable)
            () ->
                transactions.executeWithoutResult(
                    status -> {
                      List<UUID> due = repository.claimDue(tenantId, instanceId, BATCH);
                      for (UUID deploymentId : due) {
                        int approvals = repository.approvalCount(tenantId, deploymentId);
                        audit.recordSystem(
                            tenantId,
                            "DEPLOYMENT.ACTIVATED_ON_SCHEDULE",
                            "deployment",
                            deploymentId.toString(),
                            "{\"claimedBy\":\"" + instanceId + "\"}");
                        deployments.activate(
                            tenantId,
                            deploymentId,
                            systemPrincipal(tenantId),
                            approvals,
                            approvals);
                        log.info(
                            "scheduled deployment activated tenant={} deployment={} by={}",
                            tenantId,
                            deploymentId,
                            instanceId);
                      }
                    }));
  }

  /**
   * A principal standing for the platform itself.
   *
   * <p>It carries no capabilities. The authorisation decision was made when a person approved the
   * deployment; this principal exists only so the activation's audit entry has a subject, and
   * giving it capabilities would create a principal that can do anything with nobody behind it.
   */
  private static Principal systemPrincipal(String tenantId) {
    return new Principal(SYSTEM_ACTOR, tenantId, SYSTEM_ACTOR, java.util.Set.of(), false);
  }
}
