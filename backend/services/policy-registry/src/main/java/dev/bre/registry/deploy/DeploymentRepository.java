package dev.bre.registry.deploy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The {@code bre_ctl} deployment, approval and environment tables (§5.2). */
@Repository
public class DeploymentRepository {

  private final JdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc the control-plane database
   */
  public DeploymentRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * A deployment row.
   *
   * @param envCode the environment
   * @param policyCode the policy
   * @param bundleHash the bundle
   * @param mode CHAMPION, CHALLENGER or SHADOW
   * @param status the lifecycle state
   * @param requestedBy who requested it
   * @param expedited whether it is an emergency change
   * @param manifestVersion the manifest published on activation
   */
  public record Row(
      String envCode,
      String policyCode,
      String bundleHash,
      String mode,
      String status,
      String requestedBy,
      boolean expedited,
      Optional<Long> manifestVersion) {}

  /**
   * An approved version and its bundle.
   *
   * @param bundleHash the signed bundle
   */
  public record ApprovedVersion(String bundleHash) {}

  /**
   * Finds an APPROVED version's bundle.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version
   * @return the bundle hash, when the version is approved
   */
  public Optional<ApprovedVersion> findApprovedVersion(
      String tenantId, String policyCode, int version) {
    return jdbc
        .query(
            """
            SELECT bundle_hash FROM bre_ctl.policy_versions
             WHERE tenant_id = ? AND policy_code = ? AND version = ?
               AND status = 'APPROVED' AND bundle_hash IS NOT NULL
            """,
            (rs, rowNum) -> new ApprovedVersion(rs.getString(1)),
            tenantId,
            policyCode,
            version)
        .stream()
        .findFirst();
  }

  /**
   * Whether an environment exists.
   *
   * @param tenantId the tenant
   * @param envCode the environment
   * @return true when it does
   */
  public boolean environmentExists(String tenantId, String envCode) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM bre_ctl.environments WHERE tenant_id = ? AND env_code = ?",
            Integer.class,
            tenantId,
            envCode);
    return count != null && count > 0;
  }

  /**
   * How many approvals an environment needs.
   *
   * <p>An {@code EXPEDITED} change needs two regardless of the environment's own setting (00 §3.4),
   * because expedited exists to skip the queue rather than the control — a 2am fix is exactly when
   * a second pair of eyes is most valuable and most likely to be skipped.
   *
   * @param tenantId the tenant
   * @param envCode the environment
   * @param expedited whether this is an emergency change
   * @return the count
   */
  public int approvalsRequired(String tenantId, String envCode, boolean expedited) {
    Integer configured =
        jdbc
            .query(
                """
                SELECT min_deployment_approvers FROM bre_ctl.environments
                 WHERE tenant_id = ? AND env_code = ?
                """,
                (rs, rowNum) -> rs.getInt(1),
                tenantId,
                envCode)
            .stream()
            .findFirst()
            .orElse(1);
    return expedited ? Math.max(2, configured) : configured;
  }

  /**
   * Inserts a PENDING_APPROVAL deployment.
   *
   * @param tenantId the tenant
   * @param deploymentId the identifier
   * @param envCode the environment
   * @param policyCode the policy
   * @param bundleHash the bundle
   * @param mode the mode
   * @param reason why
   * @param requestedBy who
   * @param expedited whether it is an emergency change
   */
  public void insert(
      String tenantId,
      UUID deploymentId,
      String envCode,
      String policyCode,
      String bundleHash,
      DeploymentService.Mode mode,
      String reason,
      String requestedBy,
      boolean expedited,
      java.time.OffsetDateTime effectiveFrom) {
    jdbc.update(
        """
        INSERT INTO bre_ctl.deployments (
            tenant_id, deployment_id, env_code, policy_code, bundle_hash, mode,
            effective_from, status, expedited, reason, requested_by)
        VALUES (?, ?, ?, ?, ?, ?, coalesce(?::timestamptz, now()), 'PENDING_APPROVAL', ?, ?, ?)
        """,
        tenantId,
        deploymentId,
        envCode,
        policyCode,
        bundleHash,
        mode.name(),
        effectiveFrom,
        expedited,
        reason,
        requestedBy);
  }

  /**
   * Inserts a rollback deployment, pointing at a previous bundle.
   *
   * @param tenantId the tenant
   * @param deploymentId the new deployment
   * @param envCode the environment
   * @param policyCode the policy
   * @param bundleHash the bundle rolled back to
   * @param reason why
   * @param requestedBy who
   * @param rollbackOf the deployment being rolled back
   */
  public void insertRollback(
      String tenantId,
      UUID deploymentId,
      String envCode,
      String policyCode,
      String bundleHash,
      String reason,
      String requestedBy,
      UUID rollbackOf) {
    jdbc.update(
        """
        INSERT INTO bre_ctl.deployments (
            tenant_id, deployment_id, env_code, policy_code, bundle_hash, mode,
            effective_from, status, reason, requested_by, rollback_of)
        VALUES (?, ?, ?, ?, ?, 'CHAMPION', now(), 'PENDING_APPROVAL', ?, ?, ?)
        """,
        tenantId,
        deploymentId,
        envCode,
        policyCode,
        bundleHash,
        reason,
        requestedBy,
        rollbackOf);
  }

  /**
   * Finds a deployment.
   *
   * @param tenantId the tenant
   * @param deploymentId the deployment
   * @return the row, if it exists
   */
  public Optional<Row> find(String tenantId, UUID deploymentId) {
    return jdbc
        .query(
            """
            SELECT env_code, policy_code, bundle_hash, mode, status, requested_by, expedited,
                   manifest_version
              FROM bre_ctl.deployments
             WHERE tenant_id = ? AND deployment_id = ?
            """,
            (rs, rowNum) ->
                new Row(
                    rs.getString("env_code"),
                    rs.getString("policy_code"),
                    rs.getString("bundle_hash"),
                    rs.getString("mode"),
                    rs.getString("status"),
                    rs.getString("requested_by"),
                    rs.getBoolean("expedited"),
                    Optional.ofNullable((Long) rs.getObject("manifest_version"))),
            tenantId,
            deploymentId)
        .stream()
        .findFirst();
  }

  /**
   * Lists an environment's deployment ids, newest first.
   *
   * @param tenantId the tenant
   * @param envCode the environment
   * @return the ids
   */
  public List<UUID> listIds(String tenantId, String envCode) {
    return jdbc.query(
        """
        SELECT deployment_id FROM bre_ctl.deployments
         WHERE tenant_id = ? AND env_code = ? ORDER BY created_at DESC
        """,
        (rs, rowNum) -> (UUID) rs.getObject(1),
        tenantId,
        envCode);
  }

  /**
   * Everyone who authored or reviewed the version a deployment serves.
   *
   * <p>Joined through the bundle, not through a version column on the deployment: a deployment
   * names a <em>bundle</em>, and a bundle names exactly one policy version. Going via the bundle
   * means the segregation-of-duties check cannot be fooled by deploying the same version through a
   * differently-recorded route.
   *
   * @param tenantId the tenant
   * @param deploymentId the deployment
   * @return the subjects who may not approve it
   */
  public List<String> authorsAndReviewersOf(String tenantId, UUID deploymentId) {
    return jdbc.query(
        """
        SELECT e.editor AS subject
          FROM bre_ctl.deployments d
          JOIN bre_ctl.bundles b
            ON b.tenant_id = d.tenant_id AND b.bundle_hash = d.bundle_hash
          JOIN bre_ctl.policy_version_editors e
            ON e.tenant_id = b.tenant_id AND e.policy_code = b.policy_code
           AND e.policy_version = b.policy_version
         WHERE d.tenant_id = ? AND d.deployment_id = ?
        UNION
        SELECT r.reviewer AS subject
          FROM bre_ctl.deployments d
          JOIN bre_ctl.bundles b
            ON b.tenant_id = d.tenant_id AND b.bundle_hash = d.bundle_hash
          JOIN bre_ctl.policy_version_reviews r
            ON r.tenant_id = b.tenant_id AND r.policy_code = b.policy_code
           AND r.policy_version = b.policy_version
         WHERE d.tenant_id = ? AND d.deployment_id = ?
        """,
        (rs, rowNum) -> rs.getString("subject"),
        tenantId,
        deploymentId,
        tenantId,
        deploymentId);
  }

  /**
   * Records a deployment approval.
   *
   * @param tenantId the tenant
   * @param deploymentId the deployment
   * @param approver who approved
   * @param comment their comment
   */
  public void recordApproval(String tenantId, UUID deploymentId, String approver, String comment) {
    jdbc.update(
        """
        INSERT INTO bre_ctl.deployment_approvals (tenant_id, deployment_id, approver, comment)
        VALUES (?, ?, ?, ?)
        ON CONFLICT (tenant_id, deployment_id, approver) DO UPDATE SET comment = excluded.comment
        """,
        tenantId,
        deploymentId,
        approver,
        comment);
  }

  /**
   * How many distinct people approved a deployment.
   *
   * @param tenantId the tenant
   * @param deploymentId the deployment
   * @return the count
   */
  public int approvalCount(String tenantId, UUID deploymentId) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM bre_ctl.deployment_approvals
             WHERE tenant_id = ? AND deployment_id = ?
            """,
            Integer.class,
            tenantId,
            deploymentId);
    return count == null ? 0 : count;
  }

  /**
   * Whether a deployment's effective date is still in the future.
   *
   * <p>Asked of the <b>database's</b> clock rather than the JVM's. Two registry instances whose
   * clocks differ by a minute would otherwise disagree about whether a deployment is due, and the
   * one that is running fast would activate a policy change early.
   *
   * @param tenantId the tenant
   * @param deploymentId the deployment
   * @return true when it should wait in SCHEDULED
   */
  public boolean isFutureDated(String tenantId, UUID deploymentId) {
    Boolean future =
        jdbc.queryForObject(
            """
            SELECT effective_from > now() FROM bre_ctl.deployments
             WHERE tenant_id = ? AND deployment_id = ?
            """,
            Boolean.class,
            tenantId,
            deploymentId);
    return Boolean.TRUE.equals(future);
  }

  /**
   * Marks a fully approved deployment SCHEDULED (00 §3.4).
   *
   * @param tenantId the tenant
   * @param deploymentId the deployment
   */
  public void schedule(String tenantId, UUID deploymentId) {
    jdbc.update(
        """
        UPDATE bre_ctl.deployments
           SET status = 'SCHEDULED', scheduled_at = now(),
               row_version = row_version + 1, updated_at = now()
         WHERE tenant_id = ? AND deployment_id = ? AND status = 'PENDING_APPROVAL'
        """,
        tenantId,
        deploymentId);
  }

  /**
   * Claims every SCHEDULED deployment whose effective date has arrived.
   *
   * <p><b>{@code FOR UPDATE SKIP LOCKED} is the whole point.</b> Two registry instances polling the
   * same table at the same second is the normal case, not an edge one. Without the claim both would
   * activate the same deployment: two manifests published, two audit entries, and a superseding
   * race whose loser is whichever transaction commits second. With it, each row is handed to
   * exactly one instance and the other simply sees fewer rows.
   *
   * @param tenantId the tenant, already bound as the RLS scope
   * @param claimedBy an identifier for this instance, recorded on the row
   * @param limit how many to take in one pass
   * @return the claimed deployment ids
   */
  public List<UUID> claimDue(String tenantId, String claimedBy, int limit) {
    return jdbc.query(
        """
        UPDATE bre_ctl.deployments d
           SET activation_claimed_by = ?, row_version = row_version + 1, updated_at = now()
         WHERE (d.tenant_id, d.deployment_id) IN (
                 SELECT c.tenant_id, c.deployment_id FROM bre_ctl.deployments c
                  WHERE c.tenant_id = ? AND c.status = 'SCHEDULED' AND c.effective_from <= now()
                  ORDER BY c.effective_from
                  LIMIT ?
                  FOR UPDATE SKIP LOCKED)
        RETURNING d.deployment_id
        """,
        (rs, rowNum) -> (UUID) rs.getObject("deployment_id"),
        claimedBy,
        tenantId,
        limit);
  }

  /**
   * Which tenants have a deployment whose effective date has arrived.
   *
   * <p>Through a SECURITY DEFINER function because the activator is a timer: there is no principal,
   * so no tenant is in scope, and {@code deployments} is under FORCE row-level security. The
   * function returns tenant ids and nothing else, so the privileged surface is one column.
   *
   * @return the tenant ids
   */
  public List<String> tenantsWithDueDeployments() {
    return jdbc.query(
        "SELECT tenant_id FROM bre_ctl.tenants_with_due_deployments()",
        (rs, rowNum) -> rs.getString(1));
  }

  /**
   * Marks a deployment ACTIVE.
   *
   * @param tenantId the tenant
   * @param deploymentId the deployment
   */
  public void activate(String tenantId, UUID deploymentId) {
    jdbc.update(
        """
        UPDATE bre_ctl.deployments
           SET status = 'ACTIVE', activated_at = now(),
               row_version = row_version + 1, updated_at = now()
         WHERE tenant_id = ? AND deployment_id = ?
        """,
        tenantId,
        deploymentId);
  }

  /**
   * Sets a deployment's status.
   *
   * @param tenantId the tenant
   * @param deploymentId the deployment
   * @param status the new status
   */
  public void setStatus(String tenantId, UUID deploymentId, String status) {
    jdbc.update(
        """
        UPDATE bre_ctl.deployments
           SET status = ?, ended_at = CASE WHEN ? IN ('SUPERSEDED','ROLLED_BACK','CANCELLED')
                                           THEN now() ELSE ended_at END,
               row_version = row_version + 1, updated_at = now()
         WHERE tenant_id = ? AND deployment_id = ?
        """,
        status,
        status,
        tenantId,
        deploymentId);
  }

  /**
   * Records the manifest version a deployment activated under.
   *
   * @param tenantId the tenant
   * @param deploymentId the deployment
   * @param manifestVersion the manifest
   */
  public void setManifestVersion(String tenantId, UUID deploymentId, long manifestVersion) {
    jdbc.update(
        """
        UPDATE bre_ctl.deployments SET manifest_version = ?
         WHERE tenant_id = ? AND deployment_id = ?
        """,
        manifestVersion,
        tenantId,
        deploymentId);
  }

  /**
   * Supersedes every other ACTIVE champion of a policy in an environment.
   *
   * @param tenantId the tenant
   * @param envCode the environment
   * @param policyCode the policy
   * @param except the deployment taking over
   * @return how many were superseded
   */
  public int supersedeActiveChampions(
      String tenantId, String envCode, String policyCode, UUID except) {
    return jdbc.update(
        """
        UPDATE bre_ctl.deployments
           SET status = 'SUPERSEDED', ended_at = now(),
               row_version = row_version + 1, updated_at = now()
         WHERE tenant_id = ? AND env_code = ? AND policy_code = ?
           AND status = 'ACTIVE' AND mode = 'CHAMPION' AND deployment_id <> ?
        """,
        tenantId,
        envCode,
        policyCode,
        except);
  }

  /**
   * The bundle of the most recent superseded or active champion before a deployment.
   *
   * @param tenantId the tenant
   * @param envCode the environment
   * @param policyCode the policy
   * @param before the deployment being rolled back
   * @return the previous bundle hash
   */
  public Optional<String> previousActiveBundle(
      String tenantId, String envCode, String policyCode, UUID before) {
    return jdbc
        .query(
            """
            SELECT bundle_hash FROM bre_ctl.deployments
             WHERE tenant_id = ? AND env_code = ? AND policy_code = ?
               AND deployment_id <> ? AND status IN ('SUPERSEDED','ROLLED_BACK')
             ORDER BY activated_at DESC NULLS LAST, created_at DESC
             LIMIT 1
            """,
            (rs, rowNum) -> rs.getString(1),
            tenantId,
            envCode,
            policyCode,
            before)
        .stream()
        .findFirst();
  }

  /**
   * Everything a cell is currently serving, for the Active Pointer Manifest (§8.1).
   *
   * @param cellId the cell
   * @return one entry per tenant, environment and policy with an ACTIVE deployment
   */
  public List<ManifestEntry> activeForCell(int cellId) {
    return jdbc.query(
        """
        SELECT d.tenant_id, d.env_code, d.policy_code, d.bundle_hash, d.deployment_id,
               d.mode, d.experiment_id, d.bucket_from, d.bucket_to,
               t.time_zone, p.pinning_allowed
          FROM bre_ctl.deployments d
          JOIN bre_ctl.tenants  t ON t.tenant_id = d.tenant_id
          JOIN bre_ctl.policies p ON p.tenant_id = d.tenant_id AND p.policy_code = d.policy_code
         WHERE t.cell_id = ? AND d.status = 'ACTIVE'
         ORDER BY d.tenant_id, d.env_code, d.policy_code, d.mode
        """,
        (rs, rowNum) ->
            new ManifestEntry(
                rs.getString("tenant_id"),
                rs.getString("env_code"),
                rs.getString("policy_code"),
                rs.getString("bundle_hash"),
                rs.getObject("deployment_id").toString(),
                rs.getString("mode"),
                rs.getString("experiment_id"),
                (Integer) rs.getObject("bucket_from"),
                (Integer) rs.getObject("bucket_to"),
                rs.getString("time_zone"),
                rs.getBoolean("pinning_allowed")),
        cellId);
  }

  /**
   * Every bundle a pinned request may still ask for (§8.1 {@code servable}).
   *
   * <p>Champions and superseded champions, with the time each stopped being champion. The
   * grandfather window is applied by the pod against the request's arrival time, not here — doing
   * it here would bake "now" into a manifest that is read for hours.
   *
   * @param cellId the cell
   * @return one entry per servable bundle
   */
  public List<ServableEntry> servableForCell(int cellId) {
    return jdbc.query(
        """
        SELECT d.tenant_id, d.env_code, d.policy_code, d.bundle_hash, d.deployment_id,
               CASE WHEN d.status = 'ACTIVE' THEN NULL
                    ELSE to_char(d.ended_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
               END AS champion_until
          FROM bre_ctl.deployments d
          JOIN bre_ctl.tenants t ON t.tenant_id = d.tenant_id
         WHERE t.cell_id = ? AND d.mode = 'CHAMPION'
           AND d.status IN ('ACTIVE','SUPERSEDED','ROLLED_BACK')
         ORDER BY d.tenant_id, d.env_code, d.policy_code, d.created_at DESC
        """,
        (rs, rowNum) ->
            new ServableEntry(
                rs.getString("tenant_id"),
                rs.getString("env_code"),
                rs.getString("policy_code"),
                rs.getString("bundle_hash"),
                rs.getObject("deployment_id").toString(),
                rs.getString("champion_until")),
        cellId);
  }

  /**
   * One active deployment, as the manifest needs it.
   *
   * @param tenantId the tenant
   * @param envCode the environment
   * @param policyCode the policy
   * @param bundleHash the bundle
   * @param deploymentId the deployment
   * @param mode CHAMPION, CHALLENGER or SHADOW
   * @param experimentId the experiment, for a challenger
   * @param bucketFrom inclusive lower bucket, for a challenger
   * @param bucketTo exclusive upper bucket, for a challenger
   * @param timeZone the tenant's zone, for day boundaries
   * @param pinningAllowed whether a caller may pin a bundle hash
   */
  public record ManifestEntry(
      String tenantId,
      String envCode,
      String policyCode,
      String bundleHash,
      String deploymentId,
      String mode,
      String experimentId,
      Integer bucketFrom,
      Integer bucketTo,
      String timeZone,
      boolean pinningAllowed) {}

  /**
   * One servable bundle.
   *
   * @param tenantId the tenant
   * @param envCode the environment
   * @param policyCode the policy
   * @param bundleHash the bundle
   * @param deploymentId the deployment that served it
   * @param championUntil when it stopped being champion, or null while it still is
   */
  public record ServableEntry(
      String tenantId,
      String envCode,
      String policyCode,
      String bundleHash,
      String deploymentId,
      String championUntil) {}

  /**
   * The next manifest version for a cell.
   *
   * @param cellId the cell
   * @return one more than the highest published, or 1
   */
  public long nextManifestVersion(int cellId) {
    Long next =
        jdbc.queryForObject(
            """
            SELECT coalesce(max(manifest_version), 0) + 1 FROM bre_ctl.pointer_manifests
             WHERE cell_id = ?
            """,
            Long.class,
            cellId);
    return next == null ? 1L : next;
  }

  /**
   * Stores a published manifest.
   *
   * @param cellId the cell
   * @param manifestVersion the version
   * @param body the manifest JSON
   * @param bodySha256 its hash
   * @param signature the signature object
   */
  public void insertManifest(
      int cellId, long manifestVersion, String body, String bodySha256, String signature) {
    jdbc.update(
        """
        INSERT INTO bre_ctl.pointer_manifests (
            cell_id, manifest_version, body, body_sha256, signature)
        VALUES (?, ?, ?::jsonb, ?, ?::jsonb)
        """,
        cellId,
        manifestVersion,
        body,
        bodySha256,
        signature);
  }
}
