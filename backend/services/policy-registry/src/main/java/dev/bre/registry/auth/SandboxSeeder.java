package dev.bre.registry.auth;

import dev.bre.common.TenantContext;
import dev.bre.registry.audit.AuditLog;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a sandbox tenant with one account per role, for an integrator to try against (M6-05).
 *
 * <p><b>Why a whole tenant and not a few extra accounts.</b> The thing an integrator most needs to
 * exercise is maker-checker: author a version, have somebody else review it, have a third person
 * approve the deployment. That cannot be tried with one account, and the administrator account the
 * bootstrap creates deliberately bypasses the control — so trying it there would demonstrate the
 * override rather than the rule. This tenant has {@code allow_admin_sod_override} switched
 * <b>off</b>, which means the segregation of duties here behaves the way it will in production.
 *
 * <p><b>Off by default, and it says so loudly when on.</b> Four accounts with published passwords
 * is precisely what must never exist in a production installation. Three things guard it: the
 * property is unset by default, the tenant id is fixed at {@code sandbox} so it can never be
 * mistaken for a real lender, and every account is created with {@code mustChangePassword} false —
 * which is wrong for a real account and right for one whose password is in a README.
 *
 * <p><b>It seeds no policies.</b> An empty sandbox with working accounts is a place to write your
 * own rules; a sandbox pre-filled with somebody else's is a place to read them. The reference
 * policy is in the repository for anyone who wants a worked example to import.
 */
@Component
// After Bootstrap: both create a cell, and the sandbox is pointless on an installation whose first
// administrator does not exist yet.
@Order(100)
public class SandboxSeeder {

  private static final Logger log = LoggerFactory.getLogger(SandboxSeeder.class);

  /** The sandbox tenant's id. Fixed, so it cannot be confused with a real tenant. */
  public static final String TENANT_ID = "sandbox";

  /**
   * One seeded account.
   *
   * @param subject the account id
   * @param role the single role it holds
   * @param fullName a readable name
   * @param jobTitle what the role means in a lender's words
   */
  private record Account(String subject, Role role, String fullName, String jobTitle) {}

  /**
   * One account per role in the lifecycle, each holding exactly one.
   *
   * <p>Exactly one is the point. An account holding both POLICY_AUTHOR and POLICY_REVIEWER can
   * submit and approve its own work subject only to the author check — which would let an
   * integrator build an integration that works in the sandbox and is refused in production.
   */
  private static final List<Account> ACCOUNTS =
      List.of(
          new Account("author", Role.POLICY_AUTHOR, "Sandbox Author", "Credit Policy Analyst"),
          new Account("reviewer", Role.POLICY_REVIEWER, "Sandbox Reviewer", "Credit Policy Head"),
          new Account(
              "approver", Role.DEPLOYMENT_APPROVER, "Sandbox Approver", "Head of Credit Risk"),
          new Account("viewer", Role.OPS_VIEWER, "Sandbox Viewer", "Branch Operations"),
          new Account("auditor", Role.AUDITOR, "Sandbox Auditor", "Internal Audit"));

  private final JdbcTemplate jdbc;
  private final UserRepository users;
  private final AuditLog audit;
  private final boolean enabled;
  private final String password;

  /**
   * Creates the seeder.
   *
   * @param jdbc the control-plane database
   * @param users the user store
   * @param audit the hash-chained audit log
   * @param enabled whether to seed at all
   * @param password the password every sandbox account gets, or blank to generate one
   */
  public SandboxSeeder(
      JdbcTemplate jdbc,
      UserRepository users,
      AuditLog audit,
      @Value("${bre.registry.sandbox-enabled:false}") boolean enabled,
      @Value("${bre.registry.sandbox-password:}") String password) {
    this.jdbc = jdbc;
    this.users = users;
    this.audit = audit;
    this.enabled = enabled;
    this.password = password;
  }

  /** Seeds after startup, when enabled and when the sandbox tenant has no accounts. */
  @EventListener(ApplicationReadyEvent.class)
  public void seedIfEnabled() {
    if (!enabled) {
      return;
    }
    TenantContext.runIn(TENANT_ID, (Runnable) this::seedInScope);
  }

  @Transactional
  void seedInScope() {
    jdbc.update(
        """
        INSERT INTO bre_ctl.cells (cell_id, region_pair, status, hot_store_shards)
        VALUES (1, 'ap-south-1/ap-south-2', 'ACTIVE', 1) ON CONFLICT (cell_id) DO NOTHING
        """);

    // allow_admin_sod_override false is the whole reason this is a separate tenant: here,
    // maker-checker cannot be overridden by anybody, which is how a lender runs it once more than
    // one person has an account. min_coverage_percent stays at the 80% default.
    jdbc.update(
        """
        INSERT INTO bre_ctl.tenants (tenant_id, display_name, status, cell_id,
                                     allow_admin_sod_override)
        VALUES (?, 'Sandbox (integration testing)', 'ACTIVE', 1, false)
        ON CONFLICT (tenant_id) DO NOTHING
        """,
        TENANT_ID);

    if (users.hasAnyUser(TENANT_ID)) {
      log.info("sandbox tenant already seeded; leaving it alone");
      return;
    }

    seedEnvironments();
    seedFactSchema();

    boolean generated = password == null || password.isBlank();
    String sharedPassword = generated ? Passwords.generate() : password;

    for (Account account : ACCOUNTS) {
      users.insert(
          account.subject(),
          TENANT_ID,
          account.subject() + "@sandbox.invalid",
          account.fullName(),
          Passwords.hash(sharedPassword.toCharArray()),
          // Not required to change it: the password is documentation here, and forcing a change
          // would mean every integrator's first API call is a password change they did not ask for.
          false,
          null,
          null,
          account.jobTitle(),
          null,
          null,
          "system");
      users.grant(TENANT_ID, account.subject(), account.role(), "system");
      audit.recordSystem(
          TENANT_ID,
          "USER.SANDBOX_SEEDED",
          "user",
          account.subject(),
          "{\"role\":\"" + account.role().name() + "\"}");
    }

    log.warn(
        """

        ============================================================================
          SANDBOX TENANT SEEDED — tenant '{}'

            {} accounts, each holding exactly ONE role, all with the same password:

              author@sandbox.invalid     POLICY_AUTHOR
              reviewer@sandbox.invalid   POLICY_REVIEWER
              approver@sandbox.invalid   DEPLOYMENT_APPROVER
              viewer@sandbox.invalid     OPS_VIEWER
              auditor@sandbox.invalid    AUDITOR

              password  {}

          Segregation of duties is NOT overridable in this tenant, so the sandbox
          behaves the way production does: the author cannot review, and neither can
          approve the deployment.

          THIS MUST NOT BE ENABLED IN PRODUCTION. Five accounts with a shared,
          published password is the finding an audit opens with. The switch is
          bre.registry.sandbox-enabled and it defaults to false.
        ============================================================================
        """,
        TENANT_ID,
        ACCOUNTS.size(),
        sharedPassword);
  }

  private void seedEnvironments() {
    record Environment(String code, boolean production, int order, int approvers, boolean dryRun) {}
    for (Environment environment :
        List.of(
            new Environment("DEV", false, 1, 0, true),
            new Environment("UAT", false, 2, 1, true),
            new Environment("PROD", true, 3, 2, false))) {
      jdbc.update(
          """
          INSERT INTO bre_ctl.environments (
              tenant_id, env_code, is_production, promotion_order,
              min_deployment_approvers, dry_run_allowed)
          VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, env_code) DO NOTHING
          """,
          TENANT_ID,
          environment.code(),
          environment.production(),
          environment.order(),
          environment.approvers(),
          environment.dryRun());
    }
  }

  private void seedFactSchema() {
    String document = dev.bre.facts.LendingFacts.schemaJson();
    jdbc.update(
        """
        INSERT INTO bre_ctl.fact_schemas (
            tenant_id, schema_code, version, status, json_schema, content_hash, author, approved_at)
        VALUES (?, ?, ?, 'APPROVED', ?::jsonb, ?, 'system', now())
        ON CONFLICT (tenant_id, schema_code, version) DO NOTHING
        """,
        TENANT_ID,
        dev.bre.facts.LendingFacts.CODE,
        dev.bre.facts.LendingFacts.VERSION,
        document,
        "sha256:" + sha256Hex(document));
  }

  private static String sha256Hex(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable on this platform", e);
    }
  }

  /** Which roles the sandbox seeds, for a test asserting one account cannot do two jobs. */
  public static Set<Role> seededRoles() {
    return ACCOUNTS.stream()
        .map(Account::role)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }
}
