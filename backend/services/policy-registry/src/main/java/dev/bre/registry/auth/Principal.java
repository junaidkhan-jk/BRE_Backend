package dev.bre.registry.auth;

import java.util.EnumSet;
import java.util.Set;

/**
 * Who is making a request, and what they may do.
 *
 * <p>Resolved once per request by {@link AuthFilter} from the bearer token and put on the request;
 * no handler re-reads the token or re-queries the roles. That is not only efficiency — it means
 * there is exactly one place that decides who somebody is, so there is exactly one place to get
 * that decision right.
 *
 * @param subject the account identifier, which is also what every audit entry records as the actor
 * @param tenantId the tenant. <b>Taken from the session, never from a header.</b> {@code
 *     X-Tenant-Id} on the decision service is an unverified claim; here the tenant is whatever the
 *     authenticated account belongs to, so a request cannot act on another lender's policies by
 *     changing a header.
 * @param displayName the person's name, for messages
 * @param roles the roles granted to this account in this tenant
 * @param sodOverrideAllowed whether the tenant still permits a §3.3 override at all
 */
public record Principal(
    String subject,
    String tenantId,
    String displayName,
    Set<Role> roles,
    boolean sodOverrideAllowed) {

  /** The request attribute the filter stores this under. */
  public static final String ATTRIBUTE = "bre.principal";

  /** Creates a principal, copying the role set. */
  public Principal {
    roles = roles.isEmpty() ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(roles);
  }

  /**
   * Whether this principal holds a capability through any of its roles.
   *
   * @param capability the capability
   * @return true when any granted role confers it
   */
  public boolean can(Role.Capability capability) {
    return roles.stream().anyMatch(role -> role.capabilities().contains(capability));
  }

  /**
   * Whether this principal may bypass segregation of duties, and the tenant still allows it.
   *
   * <p>Both halves are required. A tenant that has switched the override off keeps an
   * administrator's other capabilities and loses this one — which is the setting's whole purpose:
   * once a lender has two people who can review, an auditor expects the bypass to be unavailable
   * rather than merely unused.
   *
   * @return true when an SoD refusal would instead become an audited override
   */
  public boolean mayOverrideSegregationOfDuties() {
    return sodOverrideAllowed && can(Role.Capability.SOD_OVERRIDE);
  }

  /**
   * Whether this principal holds a role.
   *
   * @param role the role
   * @return true when granted
   */
  public boolean hasRole(Role role) {
    return roles.contains(role);
  }
}
