package dev.bre.registry.auth;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The roles of 00 §3.12.
 *
 * <p><b>Roles are a flat set, not a hierarchy, and that is deliberate.</b> It is tempting to make
 * TENANT_ADMIN "contain" POLICY_REVIEWER which "contains" POLICY_AUTHOR, because organisation
 * charts look like that. Authorisation here does not: the whole point of segregation of duties (00
 * §3.3) is that holding one capability must be able to <em>exclude</em> another. In a hierarchy an
 * administrator automatically holds every role below, so the question "may this person review their
 * own work" is answered yes by the structure before any rule gets to say no.
 *
 * <p>So: each grant is explicit, a person may hold several, and the one role that does confer
 * breadth — {@link #TENANT_ADMIN} — does it through a <em>recorded, disableable override</em>
 * rather than by implication. {@link #capabilities()} says what each role may do; it is a map, not
 * a tree.
 */
public enum Role {

  /** Edit DRAFT versions and assets, run tests and simulations. */
  POLICY_AUTHOR(
      Capability.POLICY_EDIT,
      Capability.POLICY_SUBMIT,
      Capability.POLICY_READ,
      Capability.TEST_EDIT,
      Capability.SIMULATE),

  /** Approve a version or request changes. Cannot approve their own work. */
  POLICY_REVIEWER(Capability.POLICY_REVIEW, Capability.POLICY_READ, Capability.SIMULATE),

  /** Approve deployments and roll back. */
  DEPLOYMENT_APPROVER(
      Capability.DEPLOY_REQUEST,
      Capability.DEPLOY_APPROVE,
      Capability.DEPLOY_ROLLBACK,
      Capability.POLICY_READ),

  /** Back-tests and impact analyses; decisions in the masked view. */
  RISK_ANALYST(Capability.POLICY_READ, Capability.SIMULATE, Capability.DECISION_READ_MASKED),

  /** Reads everything, including the full audit trail. Unmasks only with a recorded reason. */
  AUDITOR(
      Capability.POLICY_READ,
      Capability.DECISION_READ_MASKED,
      Capability.AUDIT_READ,
      Capability.DECISION_UNMASK),

  /** Dashboards and decision lookup, masked. */
  OPS_VIEWER(Capability.POLICY_READ, Capability.DECISION_READ_MASKED),

  /**
   * Users, roles, environments and tenant settings.
   *
   * <p>Note what is <b>not</b> here: POLICY_EDIT, POLICY_REVIEW, DEPLOY_APPROVE. An administrator
   * administers; the ability to also author and approve comes from the §3.3 override, which is
   * audited and can be switched off per tenant. Granting those capabilities to the role directly
   * would make the bypass invisible and permanent.
   */
  TENANT_ADMIN(
      Capability.USER_MANAGE,
      Capability.ROLE_MANAGE,
      Capability.TENANT_SETTINGS,
      Capability.POLICY_READ,
      Capability.AUDIT_READ,
      Capability.SOD_OVERRIDE),

  /** Cells, tenants and quotas — the operator side, across tenants. */
  PLATFORM_ADMIN(
      Capability.PLATFORM_MANAGE,
      Capability.USER_MANAGE,
      Capability.ROLE_MANAGE,
      Capability.TENANT_SETTINGS,
      Capability.POLICY_READ,
      Capability.AUDIT_READ,
      Capability.SOD_OVERRIDE);

  /** What a role may do. Checked at the endpoint; never inferred from role names. */
  public enum Capability {
    /** Save a DRAFT policy version. */
    POLICY_EDIT,
    /** Submit a DRAFT for review. */
    POLICY_SUBMIT,
    /** Approve a version or request changes. */
    POLICY_REVIEW,
    /** Read policies and versions. */
    POLICY_READ,
    /** Create and edit test cases. */
    TEST_EDIT,
    /** Run a simulation or back-test. */
    SIMULATE,
    /** Request a deployment. */
    DEPLOY_REQUEST,
    /** Approve a deployment. */
    DEPLOY_APPROVE,
    /** Roll a deployment back. */
    DEPLOY_ROLLBACK,
    /** Read decisions in the masked view. */
    DECISION_READ_MASKED,
    /** Unmask a decision's facts, with a recorded reason (§9.4). */
    DECISION_UNMASK,
    /** Read the audit trail. */
    AUDIT_READ,
    /** Create, suspend and reset users. */
    USER_MANAGE,
    /** Grant and revoke roles. */
    ROLE_MANAGE,
    /** Change tenant settings. */
    TENANT_SETTINGS,
    /** Operator-side: cells, tenants, quotas. */
    PLATFORM_MANAGE,
    /**
     * Bypass 00 §3.3 segregation of duties.
     *
     * <p>Holding this does not grant POLICY_REVIEW or DEPLOY_APPROVE. It means that <em>when</em>
     * such an action would be refused because the actor is also the author, it proceeds and is
     * audited as an override — if the tenant still allows it.
     */
    SOD_OVERRIDE
  }

  // An unmodifiable EnumSet rather than Set.of. Error Prone's ImmutableEnumChecker rejects a
  // Set field on an enum because Set is a mutable INTERFACE — it cannot see that Set.of returns an
  // immutable one. Wrapping makes the immutability visible to the checker as well as true.
  @SuppressWarnings("ImmutableEnumChecker")
  private final Set<Capability> capabilities;

  Role(Capability... capabilities) {
    this.capabilities =
        capabilities.length == 0
            ? Collections.unmodifiableSet(EnumSet.noneOf(Capability.class))
            : Collections.unmodifiableSet(EnumSet.of(capabilities[0], capabilities));
  }

  /**
   * What this role may do.
   *
   * @return the capabilities
   */
  public Set<Capability> capabilities() {
    return capabilities;
  }
}
