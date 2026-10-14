package dev.bre.registry.api;

import dev.bre.registry.audit.AuditLog;
import dev.bre.registry.auth.Principal;
import dev.bre.registry.auth.Role;
import dev.bre.registry.policy.PolicyVersionService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tenant settings a lender administers for itself (FR-ADM-005).
 *
 * <p><b>A narrow surface on purpose.</b> {@code tenants} holds retention periods, cell placement,
 * routing mode and the segregation-of-duties override, and most of those are not a self-service
 * setting: moving a tenant between cells is a data migration, and shortening a retention period
 * destroys records a regulator may still ask for. What is here is what a credit-risk function
 * genuinely owns — how many reviewers a policy needs, and how much test coverage is enough.
 *
 * <p><b>{@code allow_admin_sod_override} can be switched OFF here and never back ON.</b> Turning
 * maker-checker on is a tightening; turning it off again is a weakening that an administrator
 * should not be able to do to themselves at the moment they are blocked by it. Re-enabling it is a
 * deliberate database change with somebody else watching, which is the right amount of friction for
 * a control a regulator expects to find working.
 *
 * <p>Every change is audited with its before and after value, because FR-ADM-005's acceptance
 * criterion is exactly that: every setting change is audited and takes effect without a restart.
 */
@RestController
@RequestMapping("/v1/tenant/settings")
public class TenantSettingsController {

  private final JdbcTemplate jdbc;
  private final AuditLog audit;

  /**
   * Creates the controller.
   *
   * @param jdbc the control-plane database
   * @param audit the hash-chained audit log
   */
  public TenantSettingsController(JdbcTemplate jdbc, AuditLog audit) {
    this.jdbc = jdbc;
    this.audit = audit;
  }

  /**
   * The settings a tenant administers.
   *
   * @param tenantId the tenant
   * @param displayName its name
   * @param timeZone the zone its business dates are interpreted in
   * @param minPolicyReviewers how many reviewers a policy version needs
   * @param minCoveragePercent the submit coverage threshold (FR-TEST-003)
   * @param allowAdminSodOverride whether an administrator may review their own work
   */
  public record Settings(
      String tenantId,
      String displayName,
      String timeZone,
      int minPolicyReviewers,
      BigDecimal minCoveragePercent,
      boolean allowAdminSodOverride) {}

  /**
   * A change. Every field is optional; an absent field is left alone.
   *
   * @param minPolicyReviewers how many reviewers a policy version needs, 1 to 5
   * @param minCoveragePercent the submit threshold, 0 to 100
   * @param allowAdminSodOverride only {@code false} is accepted — see the class comment
   */
  public record Change(
      Integer minPolicyReviewers, BigDecimal minCoveragePercent, Boolean allowAdminSodOverride) {}

  /**
   * Reads this tenant's settings.
   *
   * @param request the request
   * @return the settings
   */
  @GetMapping
  public Settings read(HttpServletRequest request) {
    Principal caller = Authorisation.require(request, Role.Capability.TENANT_SETTINGS);
    return current(caller.tenantId());
  }

  /**
   * Changes this tenant's settings.
   *
   * @param request the request
   * @param change what to change
   * @return the settings as they now are
   */
  @PatchMapping
  public Settings update(HttpServletRequest request, @RequestBody Change change) {
    Principal caller = Authorisation.require(request, Role.Capability.TENANT_SETTINGS);
    Settings before = current(caller.tenantId());

    if (change.minPolicyReviewers() != null) {
      int reviewers = change.minPolicyReviewers();
      if (reviewers < 1 || reviewers > 5) {
        throw new PolicyVersionService.LifecycleException(
            "minPolicyReviewers must be between 1 and 5. Zero would mean a policy version reaches"
                + " production without anybody but its author having read it.");
      }
      set(caller.tenantId(), "min_policy_reviewers", reviewers);
    }

    if (change.minCoveragePercent() != null) {
      BigDecimal coverage = change.minCoveragePercent();
      if (coverage.compareTo(BigDecimal.ZERO) < 0
          || coverage.compareTo(new BigDecimal("100")) > 0) {
        throw new PolicyVersionService.LifecycleException(
            "minCoveragePercent must be between 0 and 100.");
      }
      set(caller.tenantId(), "min_coverage_percent", coverage);
    }

    if (Boolean.FALSE.equals(change.allowAdminSodOverride())) {
      set(caller.tenantId(), "allow_admin_sod_override", false);
    } else if (Boolean.TRUE.equals(change.allowAdminSodOverride())) {
      throw new PolicyVersionService.LifecycleException(
          "segregation of duties cannot be re-enabled for override through this API. Switching it"
              + " off is a tightening and is allowed; switching it back on is a weakening, and an"
              + " administrator blocked by maker-checker must not be able to remove it for"
              + " themselves. Re-enabling it is a deliberate database change.");
    }

    Settings after = current(caller.tenantId());
    audit.record(
        caller.tenantId(),
        caller.subject(),
        "TENANT.SETTINGS_CHANGED",
        "tenant",
        caller.tenantId(),
        // Before and after, because "who lowered the coverage threshold to 5% the week before that
        // policy went live" is the question this entry exists to answer.
        "{\"before\":{\"minPolicyReviewers\":"
            + before.minPolicyReviewers()
            + ",\"minCoveragePercent\":"
            + before.minCoveragePercent()
            + ",\"allowAdminSodOverride\":"
            + before.allowAdminSodOverride()
            + "},\"after\":{\"minPolicyReviewers\":"
            + after.minPolicyReviewers()
            + ",\"minCoveragePercent\":"
            + after.minCoveragePercent()
            + ",\"allowAdminSodOverride\":"
            + after.allowAdminSodOverride()
            + "}}");
    return after;
  }

  /** Column names are a fixed set from the branches above, never caller input. */
  private void set(String tenantId, String column, Object value) {
    jdbc.update(
        "UPDATE bre_ctl.tenants SET "
            + column
            + " = ?, row_version = row_version + 1, updated_at = now() WHERE tenant_id = ?",
        value,
        tenantId);
  }

  private Settings current(String tenantId) {
    return Optional.ofNullable(
            jdbc
                .query(
                    """
                    SELECT tenant_id, display_name, time_zone, min_policy_reviewers,
                           min_coverage_percent, allow_admin_sod_override
                      FROM bre_ctl.tenants WHERE tenant_id = ?
                    """,
                    (rs, rowNum) ->
                        new Settings(
                            rs.getString("tenant_id"),
                            rs.getString("display_name"),
                            rs.getString("time_zone"),
                            rs.getInt("min_policy_reviewers"),
                            rs.getBigDecimal("min_coverage_percent"),
                            rs.getBoolean("allow_admin_sod_override")),
                    tenantId)
                .stream()
                .findFirst()
                .orElse(null))
        .orElseThrow(
            () ->
                new PolicyVersionService.LifecycleException(
                    "no tenant row is visible for this caller's tenant scope."));
  }
}
