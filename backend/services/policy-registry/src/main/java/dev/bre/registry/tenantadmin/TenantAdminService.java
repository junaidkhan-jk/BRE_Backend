package dev.bre.registry.tenantadmin;

import dev.bre.common.TenantContext;
import dev.bre.registry.audit.AuditLog;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenants, environments and quotas — the platform operator's side (M3-08, FR-ADM-001/003/005).
 *
 * <p><b>Deviation from LLD §1.1, recorded.</b> The specification makes this a separate {@code
 * tenant-admin-service}. It is a package in policy-registry instead, for one reason worth stating:
 * policy-registry already owns the {@code bre_ctl} migrations, already authenticates against its
 * users and roles, and already carries the row-level-security plumbing these tables need. A second
 * deployable would mean two services writing one schema, two migration owners, and a second copy of
 * the auth stack — and the thing that makes it a separate service in production is a deployment
 * boundary, which is a packaging change rather than a code one. Splitting it later is moving a
 * package; building it twice now is maintaining two.
 *
 * <p><b>PLATFORM_MANAGE, not TENANT_SETTINGS.</b> A lender's own administrator holds
 * TENANT_SETTINGS and configures their time zone, their retention, their reviewer counts.
 * Provisioning a tenant, assigning a cell and setting a quota are the platform operator's, and that
 * separation is the whole reason quotas live in their own table: otherwise the capability a lender
 * holds to set their own time zone would be the capability that raises their own rate limit.
 */
@Service
public class TenantAdminService {

  private static final Logger log = LoggerFactory.getLogger(TenantAdminService.class);

  private final JdbcTemplate jdbc;
  private final AuditLog audit;

  /**
   * Creates the service.
   *
   * @param jdbc the control-plane database
   * @param audit the hash-chained audit log
   */
  public TenantAdminService(JdbcTemplate jdbc, AuditLog audit) {
    this.jdbc = jdbc;
    this.audit = audit;
  }

  /** A rule was broken — a duplicate tenant, an impossible quota. Mapped to 409, never 500. */
  public static final class TenantAdminException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what rule was broken, and why it exists
     */
    public TenantAdminException(String message) {
      super(message);
    }
  }

  /**
   * A tenant as the API returns it.
   *
   * @param tenantId the identifier, which is also the row-level-security key
   * @param displayName the lender's name
   * @param status ONBOARDING, ACTIVE, SUSPENDED, OFFBOARDING or CLOSED
   * @param cellId which cell serves it
   * @param timeZone the zone that decides its business dates
   */
  public record Tenant(
      String tenantId, String displayName, String status, int cellId, String timeZone) {}

  /**
   * One environment.
   *
   * @param envCode DEV, UAT, PROD or whatever the tenant added
   * @param isProduction whether a real borrower is affected by a decision here
   * @param promotionOrder where it sits in the promotion path
   * @param minDeploymentApprovers how many approvals a deployment needs
   * @param dryRunAllowed whether a deployment may be rehearsed
   */
  public record Environment(
      String envCode,
      boolean isProduction,
      int promotionOrder,
      int minDeploymentApprovers,
      boolean dryRunAllowed) {}

  /**
   * What the platform grants a tenant.
   *
   * @param decisionsPerSecond the sustained rate
   * @param burstDecisions the bucket depth
   * @param bulkConcurrency how many bulk jobs may run at once
   * @param bundleMemoryMb the share of a pod's bundle cache
   */
  public record Quota(
      int decisionsPerSecond, int burstDecisions, int bulkConcurrency, int bundleMemoryMb) {}

  /**
   * Provisions a tenant with its environments and default quota (AC-ADM-001).
   *
   * <p><b>One call, one transaction.</b> A tenant with no environments cannot deploy anything, and
   * a tenant with no quota cannot be rate-limited — so creating them separately produces a
   * half-provisioned tenant, which is the state an operator discovers at the worst moment. The
   * database function does all three or none.
   *
   * @param tenantId the identifier
   * @param displayName the lender's name
   * @param cellId which cell serves it
   * @param timeZone the zone that decides its business dates
   * @param actor the platform operator doing it
   * @return the tenant
   */
  @Transactional
  public Tenant provision(
      String tenantId, String displayName, int cellId, String timeZone, String actor) {

    if (exists(tenantId)) {
      throw new TenantAdminException(
          "tenant "
              + tenantId
              + " already exists. Provisioning is not idempotent on purpose:"
              + " silently returning the existing tenant would let a typo in a new tenant's id"
              + " hand an operator somebody else's tenant.");
    }

    try {
      // query(), not update(). The function is invoked with SELECT and therefore returns a row —
      // one NULL, since it returns void — and JdbcTemplate.update refuses that with "A result was
      // returned when none was expected". The row is discarded; what matters is that the function
      // ran.
      jdbc.query(
          "SELECT bre_ctl.provision_tenant(?, ?, ?::smallint, ?, ?)",
          rs -> {
            // Nothing to read: the function returns void.
          },
          tenantId,
          displayName,
          cellId,
          timeZone,
          actor);
    } catch (org.springframework.dao.DataIntegrityViolationException refused) {
      throw new TenantAdminException(
          "tenant " + tenantId + " could not be provisioned: " + rootMessage(refused));
    }

    // Audited INSIDE the new tenant's own scope, so the entry is in that tenant's chain rather
    // than the operator's. "Who created this tenant" is a question asked of the tenant's own
    // trail, by an auditor who has access to that tenant and no other.
    TenantContext.runIn(
        tenantId,
        () ->
            audit.record(
                tenantId,
                actor,
                "TENANT.PROVISIONED",
                "tenant",
                tenantId,
                "{\"cellId\":" + cellId + ",\"timeZone\":\"" + timeZone + "\"}"));

    log.info("provisioned tenant={} cell={} by={}", tenantId, cellId, actor);
    return read(tenantId);
  }

  /**
   * Adds or updates an environment (AC-ADM-003).
   *
   * @param tenantId the tenant
   * @param environment the environment
   * @param actor who is changing it
   * @return every environment the tenant now has
   */
  @Transactional
  public List<Environment> upsertEnvironment(
      String tenantId, Environment environment, String actor) {

    if (environment.isProduction() && environment.minDeploymentApprovers() < 1) {
      // Also a database CHECK. Enforced here as well so the caller gets a sentence rather than a
      // constraint name, and in the database so a direct UPDATE cannot do it either.
      throw new TenantAdminException(
          "a production environment needs at least one deployment approver. An environment that"
              + " affects real borrowers and approves its own deployments is not a control.");
    }

    try {
      jdbc.update(
          """
          INSERT INTO bre_ctl.environments (tenant_id, env_code, is_production, promotion_order,
              min_deployment_approvers, dry_run_allowed)
          VALUES (?, ?, ?, ?, ?, ?)
          ON CONFLICT (tenant_id, env_code) DO UPDATE
             SET is_production = excluded.is_production,
                 promotion_order = excluded.promotion_order,
                 min_deployment_approvers = excluded.min_deployment_approvers,
                 dry_run_allowed = excluded.dry_run_allowed
          """,
          tenantId,
          environment.envCode(),
          environment.isProduction(),
          environment.promotionOrder(),
          environment.minDeploymentApprovers(),
          environment.dryRunAllowed());
    } catch (org.springframework.dao.DataIntegrityViolationException refused) {
      throw new TenantAdminException(
          "environment " + environment.envCode() + " was refused: " + rootMessage(refused));
    }

    audit.record(
        tenantId,
        actor,
        "ENVIRONMENT.CHANGED",
        "environment",
        environment.envCode(),
        "{\"approvers\":"
            + environment.minDeploymentApprovers()
            + ",\"production\":"
            + environment.isProduction()
            + "}");

    return environments(tenantId);
  }

  /**
   * The tenant's environments, in promotion order.
   *
   * @param tenantId the tenant
   * @return the environments
   */
  public List<Environment> environments(String tenantId) {
    return jdbc.query(
        """
        SELECT env_code, is_production, promotion_order, min_deployment_approvers, dry_run_allowed
          FROM bre_ctl.environments WHERE tenant_id = ? ORDER BY promotion_order
        """,
        (rs, rowNum) ->
            new Environment(
                rs.getString("env_code"),
                rs.getBoolean("is_production"),
                rs.getInt("promotion_order"),
                rs.getInt("min_deployment_approvers"),
                rs.getBoolean("dry_run_allowed")),
        tenantId);
  }

  /**
   * Sets a tenant's quota.
   *
   * @param tenantId the tenant
   * @param quota what to grant
   * @param actor the platform operator
   * @return the quota now in force
   */
  @Transactional
  public Quota setQuota(String tenantId, Quota quota, String actor) {
    if (quota.burstDecisions() < quota.decisionsPerSecond()) {
      throw new TenantAdminException(
          "a burst below the sustained rate can only ever reject traffic the tenant is entitled"
              + " to. Set the burst at or above "
              + quota.decisionsPerSecond()
              + ".");
    }
    int updated =
        jdbc.update(
            """
            UPDATE bre_ctl.tenant_quotas
               SET decisions_per_second = ?, burst_decisions = ?, bulk_concurrency = ?,
                   bundle_memory_mb = ?, updated_by = ?, updated_at = now(),
                   row_version = row_version + 1
             WHERE tenant_id = ?
            """,
            quota.decisionsPerSecond(),
            quota.burstDecisions(),
            quota.bulkConcurrency(),
            quota.bundleMemoryMb(),
            actor,
            tenantId);
    if (updated == 0) {
      throw new TenantAdminException("tenant " + tenantId + " has no quota row to update.");
    }

    audit.record(
        tenantId,
        actor,
        "TENANT.QUOTA_CHANGED",
        "tenant",
        tenantId,
        "{\"decisionsPerSecond\":"
            + quota.decisionsPerSecond()
            + ",\"burst\":"
            + quota.burstDecisions()
            + ",\"bulkConcurrency\":"
            + quota.bulkConcurrency()
            + ",\"bundleMemoryMb\":"
            + quota.bundleMemoryMb()
            + "}");

    return quota(tenantId);
  }

  /**
   * The tenant's quota.
   *
   * @param tenantId the tenant
   * @return the quota
   */
  public Quota quota(String tenantId) {
    return jdbc.queryForObject(
        """
        SELECT decisions_per_second, burst_decisions, bulk_concurrency, bundle_memory_mb
          FROM bre_ctl.tenant_quotas WHERE tenant_id = ?
        """,
        (rs, rowNum) ->
            new Quota(
                rs.getInt("decisions_per_second"),
                rs.getInt("burst_decisions"),
                rs.getInt("bulk_concurrency"),
                rs.getInt("bundle_memory_mb")),
        tenantId);
  }

  /**
   * Reads a tenant.
   *
   * @param tenantId the tenant
   * @return the tenant
   */
  public Tenant read(String tenantId) {
    return jdbc.queryForObject(
        """
        SELECT tenant_id, display_name, status, cell_id, time_zone
          FROM bre_ctl.tenants WHERE tenant_id = ?
        """,
        (rs, rowNum) ->
            new Tenant(
                rs.getString("tenant_id"),
                rs.getString("display_name"),
                rs.getString("status"),
                rs.getInt("cell_id"),
                rs.getString("time_zone")),
        tenantId);
  }

  private boolean exists(String tenantId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT exists(SELECT 1 FROM bre_ctl.tenants WHERE tenant_id = ?)",
            Boolean.class,
            tenantId));
  }

  private static String rootMessage(Throwable thrown) {
    Throwable cause = thrown;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    // The database's own message, which names the constraint and never a value a caller sent.
    // First line only: PostgreSQL appends Detail and Hint lines that quote the offending ROW,
    // which for a tenants insert is the display name a caller sent.
    String message = cause.getMessage();
    if (message == null) {
      return thrown.getClass().getSimpleName();
    }
    int newline = message.indexOf('\n');
    return newline < 0 ? message : message.substring(0, newline);
  }
}
