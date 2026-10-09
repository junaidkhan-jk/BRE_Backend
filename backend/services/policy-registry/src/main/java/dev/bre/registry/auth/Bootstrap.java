package dev.bre.registry.auth;

import dev.bre.common.TenantContext;
import dev.bre.registry.audit.AuditLog;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first administrator, once, on an empty installation.
 *
 * <p><b>The chicken-and-egg problem this solves:</b> creating a user needs {@code USER_MANAGE},
 * which needs a role grant, which needs somebody with {@code ROLE_MANAGE}. On a fresh database
 * there is nobody, so without a bootstrap the only way in is a manual {@code INSERT} by whoever
 * holds the production database password — which is both worse (no audit entry, a hand-computed
 * password hash) and the kind of step that gets written down in a wiki and then shared.
 *
 * <p><b>Three rules keep it from being a back door:</b>
 *
 * <ol>
 *   <li>It runs <b>only when the tenant has no users at all</b>. Not "no admin" — no users. An
 *       installation that has ever had an account is never bootstrapped again, so this cannot be
 *       used to mint a second administrator later.
 *   <li>The password is either configured or <b>generated and printed once to the log</b>, and the
 *       account is flagged {@code mustChangePassword}. A generated password in a startup log is not
 *       wonderful; it is better than a default password, which is the alternative that gets shipped
 *       and never changed.
 *   <li>It writes an audit entry like any other account creation, with {@code system} as the actor.
 *       A bootstrapped administrator is visible in the same place every other grant is.
 * </ol>
 *
 * <p>Switched off by leaving {@code bre.registry.bootstrap-tenant} unset, which is what a
 * deployment using an identity provider should do.
 */
@Component
public class Bootstrap {

  private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

  private final JdbcTemplate jdbc;
  private final UserRepository users;
  private final AuditLog audit;
  private final String tenantId;
  private final String displayName;
  private final String adminEmail;
  private final String adminName;
  private final String adminPassword;

  /**
   * Creates the bootstrap.
   *
   * @param jdbc the control-plane database
   * @param users the user store
   * @param audit the hash-chained audit log
   * @param tenantId the tenant to create, or blank to do nothing
   * @param displayName the tenant's display name
   * @param adminEmail the administrator's email
   * @param adminName the administrator's name
   * @param adminPassword an initial password, or blank to generate one
   */
  public Bootstrap(
      JdbcTemplate jdbc,
      UserRepository users,
      AuditLog audit,
      @Value("${bre.registry.bootstrap-tenant:}") String tenantId,
      @Value("${bre.registry.bootstrap-tenant-name:Bootstrap Tenant}") String displayName,
      @Value("${bre.registry.bootstrap-admin-email:}") String adminEmail,
      @Value("${bre.registry.bootstrap-admin-name:Administrator}") String adminName,
      @Value("${bre.registry.bootstrap-admin-password:}") String adminPassword) {
    this.jdbc = jdbc;
    this.users = users;
    this.audit = audit;
    this.tenantId = tenantId;
    this.displayName = displayName;
    this.adminEmail = adminEmail;
    this.adminName = adminName;
    this.adminPassword = adminPassword;
  }

  /**
   * Runs after startup, when configured and when the tenant has no users.
   *
   * <p>On {@code ApplicationReadyEvent} rather than in a constructor or {@code @PostConstruct}:
   * Flyway has to have migrated first, and a bean that writes rows during context startup races the
   * migration.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void bootstrapIfEmpty() {
    if (tenantId == null || tenantId.isBlank() || adminEmail == null || adminEmail.isBlank()) {
      return;
    }
    // Every write below touches a table under FORCE ROW LEVEL SECURITY, and there is no request
    // to have established a tenant — so the scope is bound here. Without it the inserts fail with
    // "new row violates row-level security policy", which is the policy working exactly as
    // intended against code that forgot.
    TenantContext.runIn(tenantId, (Runnable) this::bootstrapInScope);
  }

  @Transactional
  void bootstrapInScope() {
    ensureCellExists();
    ensureTenantExists();

    if (users.hasAnyUser(tenantId)) {
      // Not "no administrator" — no users at all. See the class comment: this must not be a way to
      // mint a second administrator on a running installation.
      log.info(
          "bootstrap skipped: tenant {} already has accounts. This runs only on an empty tenant.",
          tenantId);
      return;
    }

    ensureEnvironmentsExist();
    ensureFactSchemaExists();

    String email = adminEmail.trim().toLowerCase(Locale.ROOT);
    String subject = email.substring(0, email.indexOf('@')).replaceAll("[^a-z0-9._+-]", "-");
    boolean generated = adminPassword == null || adminPassword.isBlank();
    String password = generated ? Passwords.generate() : adminPassword;

    users.insert(
        subject,
        tenantId,
        email,
        adminName,
        Passwords.hash(password.toCharArray()),
        generated,
        null,
        null,
        "Administrator",
        null,
        null,
        "system");

    // TENANT_ADMIN, plus the three roles that let one person actually run the whole process on a
    // new installation. Granted explicitly rather than implied by TENANT_ADMIN, because the roles
    // are a flat set on purpose (see Role) — and because an administrator who later hands authoring
    // to somebody else should be able to give up POLICY_AUTHOR without giving up administration.
    Set<Role> roles =
        Set.of(
            Role.TENANT_ADMIN,
            Role.POLICY_AUTHOR,
            Role.POLICY_REVIEWER,
            Role.DEPLOYMENT_APPROVER,
            Role.AUDITOR);
    roles.forEach(role -> users.grant(tenantId, subject, role, "system"));

    audit.recordSystem(
        tenantId,
        "USER.BOOTSTRAPPED",
        "user",
        subject,
        "{\"roles\":"
            + "["
            + String.join(",", roles.stream().map(r -> "\"" + r.name() + "\"").toList())
            + "],\"passwordGenerated\":"
            + generated
            + "}");

    if (generated) {
      // Printed once, at WARN so it is not filtered out, with the instruction to change it. A
      // default password would be worse; an emailed invitation would be better and needs a mail
      // path this build does not have.
      log.warn(
          """

          ============================================================================
            FIRST ADMINISTRATOR CREATED for tenant '{}'

              email     {}
              password  {}

            This password was generated and is shown ONCE. It is not recoverable — the
            database holds only a PBKDF2 hash. The account must change it at first login.

            It holds TENANT_ADMIN, POLICY_AUTHOR, POLICY_REVIEWER, DEPLOYMENT_APPROVER
            and AUDITOR, so one person can walk the whole process on a new installation.
            Segregation of duties (00 §3.3) is bypassed by this account and every bypass
            is written to the audit log. Once a second person can review, create their
            account, drop this one's authoring roles, and set the tenant's
            allow_admin_sod_override to false.
          ============================================================================
          """,
          tenantId,
          email,
          password);
    } else {
      log.warn(
          "FIRST ADMINISTRATOR CREATED for tenant '{}' with email {} and a CONFIGURED password."
              + " A password in configuration is a password in a file; change it at first login"
              + " and remove the setting.",
          tenantId,
          email);
    }
  }

  /**
   * The cell a tenant belongs to. One cell on a single-node installation.
   *
   * <p>{@code region_pair} rather than a single region: §5.2 names it that way because a cell is
   * deployed across a region PAIR for disaster recovery (ADR-013), and recording one region would
   * describe only half of where a tenant's data lives. One shard, because a single-node
   * installation has one.
   */
  private void ensureCellExists() {
    jdbc.update(
        """
        INSERT INTO bre_ctl.cells (cell_id, region_pair, status, hot_store_shards)
        VALUES (1, 'ap-south-1/ap-south-2', 'ACTIVE', 1) ON CONFLICT (cell_id) DO NOTHING
        """);
  }

  private void ensureTenantExists() {
    jdbc.update(
        """
        INSERT INTO bre_ctl.tenants (tenant_id, display_name, status, cell_id)
        VALUES (?, ?, 'ACTIVE', 1) ON CONFLICT (tenant_id) DO NOTHING
        """,
        tenantId,
        displayName);
  }

  /**
   * The canonical lending fact schema, registered for this tenant.
   *
   * <p>{@code policy_versions} has a foreign key to {@code fact_schemas}, so a tenant with no
   * registered schema cannot hold a policy version at all — the first draft fails with a foreign
   * key violation naming a table the author has never heard of. The schema itself ships inside
   * {@code bre-fact-model} as a resource; this row is the tenant's registration of it.
   *
   * <p>Registered as APPROVED with its content hash, because it is not a draft anybody is editing:
   * it is the published schema this build of the engine reads facts through. §5.2's CHECK requires
   * the hash exactly when the status is APPROVED, which is the constraint saying the same thing.
   */
  private void ensureFactSchemaExists() {
    String document = dev.bre.facts.LendingFacts.schemaJson();
    jdbc.update(
        """
        INSERT INTO bre_ctl.fact_schemas (
            tenant_id, schema_code, version, status, json_schema, content_hash, author, approved_at)
        VALUES (?, ?, ?, 'APPROVED', ?::jsonb, ?, 'system', now())
        ON CONFLICT (tenant_id, schema_code, version) DO NOTHING
        """,
        tenantId,
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

  /**
   * The three environments 00 §3.3 names, with its own approval counts.
   *
   * <p>DEV 0, UAT 1, PROD 2. Created here because a tenant with no environments cannot deploy
   * anything, and a new installation would otherwise fail its first deployment with "no environment
   * DEV" — which is accurate and unhelpful.
   */
  private void ensureEnvironmentsExist() {
    record Environment(String code, boolean production, int order, int approvers, boolean dryRun) {}
    for (Environment environment :
        List.of(
            new Environment("DEV", false, 1, 0, true),
            new Environment("UAT", false, 2, 1, true),
            // is_production forbids dry_run_allowed (§5.2 CHECK), and requires at least one
            // approver. Two, per 00 §3.3.
            new Environment("PROD", true, 3, 2, false))) {
      jdbc.update(
          """
          INSERT INTO bre_ctl.environments (
              tenant_id, env_code, is_production, promotion_order,
              min_deployment_approvers, dry_run_allowed)
          VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, env_code) DO NOTHING
          """,
          tenantId,
          environment.code(),
          environment.production(),
          environment.order(),
          environment.approvers(),
          environment.dryRun());
    }
  }
}
