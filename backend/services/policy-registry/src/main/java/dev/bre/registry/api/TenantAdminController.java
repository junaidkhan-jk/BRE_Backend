package dev.bre.registry.api;

import dev.bre.common.TenantContext;
import dev.bre.registry.auth.Principal;
import dev.bre.registry.auth.Role;
import dev.bre.registry.tenantadmin.TenantAdminService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The platform operator's API: tenants, environments and quotas (M3-08, FR-ADM-001/003/005).
 *
 * <p><b>PLATFORM_MANAGE on every route, and that is the whole access-control story here.</b> A
 * lender's own administrator holds TENANT_ADMIN, which carries TENANT_SETTINGS — their time zone,
 * their retention, their reviewer counts. None of it reaches this controller. Provisioning a
 * tenant, assigning a cell and granting a quota belong to whoever runs the platform, and keeping
 * them apart is why quotas are in their own table: otherwise the capability a lender holds to set
 * their own time zone would be the capability that raises their own rate limit.
 *
 * <p><b>The tenant is in the PATH here, unlike everywhere else in this service.</b> Every other
 * controller scopes to the caller's own tenant and a cross-tenant read is impossible by
 * construction (D1). This one is cross-tenant by definition — a platform operator provisions a
 * tenant that does not exist yet and adjusts one that is not theirs. That is exactly why it is
 * capability-gated rather than scoped, and why every action is audited into the SUBJECT tenant's
 * own chain rather than the operator's: "who changed our quota" is a question a lender asks of
 * their own trail.
 */
@RestController
@RequestMapping("/v1/platform/tenants")
public class TenantAdminController {

  private final TenantAdminService tenants;

  /**
   * Creates the controller.
   *
   * @param tenants the tenant administration service
   */
  public TenantAdminController(TenantAdminService tenants) {
    this.tenants = tenants;
  }

  /**
   * What provisioning a tenant needs.
   *
   * @param tenantId the identifier, lower-case and stable for the life of the tenant
   * @param displayName the lender's name
   * @param cellId which cell serves it
   * @param timeZone the zone that decides its business dates
   */
  public record ProvisionRequest(
      String tenantId, String displayName, Integer cellId, String timeZone) {}

  /**
   * Provisions a tenant with DEV, UAT and PROD and a default quota (AC-ADM-001).
   *
   * @param request the request
   * @param body what to create
   * @return 201 with the tenant
   */
  @PostMapping
  public ResponseEntity<TenantAdminService.Tenant> provision(
      HttpServletRequest request, @RequestBody ProvisionRequest body) {

    Principal caller = Authorisation.require(request, Role.Capability.PLATFORM_MANAGE);
    TenantAdminService.Tenant tenant =
        tenants.provision(
            body.tenantId(),
            body.displayName(),
            body.cellId() == null ? 1 : body.cellId(),
            body.timeZone() == null || body.timeZone().isBlank() ? "Asia/Kolkata" : body.timeZone(),
            caller.subject());

    return ResponseEntity.status(HttpStatus.CREATED)
        .header("Location", "/v1/platform/tenants/" + tenant.tenantId())
        .body(tenant);
  }

  /**
   * Reads a tenant.
   *
   * @param request the request
   * @param tenantId the tenant
   * @return the tenant
   */
  @GetMapping("/{tenantId}")
  public TenantAdminService.Tenant read(HttpServletRequest request, @PathVariable String tenantId) {
    Authorisation.require(request, Role.Capability.PLATFORM_MANAGE);
    return inTenant(tenantId, () -> tenants.read(tenantId));
  }

  /**
   * The tenant's environments, in promotion order.
   *
   * @param request the request
   * @param tenantId the tenant
   * @return the environments
   */
  @GetMapping("/{tenantId}/environments")
  public List<TenantAdminService.Environment> environments(
      HttpServletRequest request, @PathVariable String tenantId) {
    Authorisation.require(request, Role.Capability.PLATFORM_MANAGE);
    return inTenant(tenantId, () -> tenants.environments(tenantId));
  }

  /**
   * Adds or updates an environment (AC-ADM-003).
   *
   * @param request the request
   * @param tenantId the tenant
   * @param environment the environment
   * @return every environment the tenant now has
   */
  @PutMapping("/{tenantId}/environments")
  public List<TenantAdminService.Environment> upsertEnvironment(
      HttpServletRequest request,
      @PathVariable String tenantId,
      @RequestBody TenantAdminService.Environment environment) {

    Principal caller = Authorisation.require(request, Role.Capability.PLATFORM_MANAGE);
    return inTenant(
        tenantId, () -> tenants.upsertEnvironment(tenantId, environment, caller.subject()));
  }

  /**
   * The tenant's quota.
   *
   * @param request the request
   * @param tenantId the tenant
   * @return the quota
   */
  @GetMapping("/{tenantId}/quota")
  public TenantAdminService.Quota quota(HttpServletRequest request, @PathVariable String tenantId) {
    Authorisation.require(request, Role.Capability.PLATFORM_MANAGE);
    return inTenant(tenantId, () -> tenants.quota(tenantId));
  }

  /**
   * Sets the tenant's quota.
   *
   * @param request the request
   * @param tenantId the tenant
   * @param quota what to grant
   * @return the quota now in force
   */
  @PutMapping("/{tenantId}/quota")
  public TenantAdminService.Quota setQuota(
      HttpServletRequest request,
      @PathVariable String tenantId,
      @RequestBody TenantAdminService.Quota quota) {

    Principal caller = Authorisation.require(request, Role.Capability.PLATFORM_MANAGE);
    return inTenant(tenantId, () -> tenants.setQuota(tenantId, quota, caller.subject()));
  }

  /**
   * Runs in the SUBJECT tenant's scope, not the caller's.
   *
   * <p>Every table here is under row-level security keyed on {@code bre.tenant_id}, and the filter
   * bound the OPERATOR's tenant. Without rebinding, a platform operator reading another tenant's
   * quota gets an empty result — which looks exactly like a tenant that has no quota, and is the
   * kind of silence that gets debugged as a missing row.
   */
  private <T> T inTenant(String tenantId, java.util.concurrent.Callable<T> action) {
    return TenantContext.runIn(tenantId, action);
  }
}
