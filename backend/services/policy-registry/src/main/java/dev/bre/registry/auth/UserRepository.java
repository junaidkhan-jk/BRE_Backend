package dev.bre.registry.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The {@code bre_ctl.users}, {@code role_assignments} and {@code user_sessions} tables. */
@Repository
public class UserRepository {

  private final JdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc the control-plane database
   */
  public UserRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * A user row.
   *
   * @param subject the account identifier
   * @param tenantId the tenant
   * @param email the login identity
   * @param fullName the person's name
   * @param passwordHash the stored hash, parameters included
   * @param mustChangePassword whether the next login has to set a new password
   * @param status ACTIVE, SUSPENDED, LOCKED or CLOSED
   * @param failedAttempts consecutive failures
   * @param lockedUntil when a lockout expires, if one is in force
   */
  public record User(
      String subject,
      String tenantId,
      String email,
      String fullName,
      String passwordHash,
      boolean mustChangePassword,
      String status,
      int failedAttempts,
      Optional<Instant> lockedUntil) {}

  /**
   * Demographics as the admin API returns them.
   *
   * @param subject the account
   * @param email the login identity
   * @param fullName the name
   * @param employeeRef the lender's staff id
   * @param department the department
   * @param designation the job title
   * @param phone the contact number
   * @param branchCode the branch
   * @param status the account status
   * @param roles the granted roles
   * @param lastLoginAt the last successful login
   */
  public record Profile(
      String subject,
      String email,
      String fullName,
      String employeeRef,
      String department,
      String designation,
      String phone,
      String branchCode,
      String status,
      Set<Role> roles,
      Optional<Instant> lastLoginAt) {}

  /**
   * Finds a user by email within a tenant.
   *
   * @param tenantId the tenant
   * @param email the lowercased email
   * @return the user, if one exists
   */
  public Optional<User> findByEmail(String tenantId, String email) {
    return jdbc
        .query(
            """
            SELECT subject, tenant_id, email, full_name, password_hash, must_change_password,
                   status, failed_attempts, locked_until
              FROM bre_ctl.users WHERE tenant_id = ? AND email = ?
            """,
            UserRepository::mapUser,
            tenantId,
            email)
        .stream()
        .findFirst();
  }

  /**
   * Finds a user by email across every tenant.
   *
   * <p>Needed because a login form asks for an email and a password, not a tenant: a person should
   * not have to know their lender's internal tenant identifier to sign in. The <b>uniqueness
   * constraint is per tenant</b>, so the same email may legitimately exist twice — a consultant at
   * two lenders — and this returns all of them for the caller to disambiguate. Returning only the
   * first would let one tenant's row shadow another's account.
   *
   * @param email the lowercased email
   * @return every account with that email, across tenants
   */
  public List<User> findByEmailAcrossTenants(String email) {
    // Through bre_ctl.login_lookup, not a direct SELECT. `users` is under tenant row-level
    // security and authentication happens BEFORE the tenant is known, so this read has to cross
    // tenants — and a SECURITY DEFINER function with one parameter and nine returned columns is
    // the narrowest way to allow exactly that one crossing. See V11 for the alternatives and why
    // they are worse.
    return jdbc.query("SELECT * FROM bre_ctl.login_lookup(?)", UserRepository::mapUser, email);
  }

  /**
   * Finds a user by subject.
   *
   * @param subject the account identifier
   * @return the user, if one exists
   */
  public Optional<User> findBySubject(String subject) {
    return jdbc
        .query(
            """
            SELECT subject, tenant_id, email, full_name, password_hash, must_change_password,
                   status, failed_attempts, locked_until
              FROM bre_ctl.users WHERE subject = ?
            """,
            UserRepository::mapUser,
            subject)
        .stream()
        .findFirst();
  }

  /**
   * Finds a user by subject without a tenant in scope, for the authentication path.
   *
   * <p>Separate from {@link #findBySubject} on purpose. That one is a normal tenant-scoped read and
   * must stay subject to row-level security. This one runs while resolving a bearer token — the
   * tenant is not established yet, which is what the resolution is for — so it goes through the
   * same narrow SECURITY DEFINER route as the login lookup, filtered to one subject.
   *
   * @param subject the account identifier
   * @return the user, if one exists
   */
  public Optional<User> findBySubjectPreTenant(String subject) {
    return jdbc
        .query("SELECT * FROM bre_ctl.login_lookup_by_subject(?)", UserRepository::mapUser, subject)
        .stream()
        .findFirst();
  }

  private static User mapUser(ResultSet rs, int rowNum) throws SQLException {
    return new User(
        rs.getString("subject"),
        rs.getString("tenant_id"),
        rs.getString("email"),
        rs.getString("full_name"),
        rs.getString("password_hash"),
        rs.getBoolean("must_change_password"),
        rs.getString("status"),
        rs.getInt("failed_attempts"),
        Optional.ofNullable(rs.getTimestamp("locked_until")).map(java.sql.Timestamp::toInstant));
  }

  /**
   * Creates a user.
   *
   * @param subject the account identifier
   * @param tenantId the tenant
   * @param email the lowercased email
   * @param fullName the name
   * @param passwordHash the stored hash
   * @param mustChangePassword whether to force a change on first login
   * @param employeeRef optional staff id
   * @param department optional
   * @param designation optional
   * @param phone optional
   * @param branchCode optional
   * @param createdBy who created it
   */
  public void insert(
      String subject,
      String tenantId,
      String email,
      String fullName,
      String passwordHash,
      boolean mustChangePassword,
      String employeeRef,
      String department,
      String designation,
      String phone,
      String branchCode,
      String createdBy) {
    jdbc.update(
        """
        INSERT INTO bre_ctl.users (
            subject, tenant_id, email, full_name, password_hash, must_change_password,
            employee_ref, department, designation, phone, branch_code, created_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        subject,
        tenantId,
        email,
        fullName,
        passwordHash,
        mustChangePassword,
        employeeRef,
        department,
        designation,
        phone,
        branchCode,
        createdBy);
  }

  /**
   * Lists a tenant's users with their roles.
   *
   * @param tenantId the tenant
   * @return the profiles, by email
   */
  public List<Profile> listUsers(String tenantId) {
    List<Profile> out = new ArrayList<>();
    jdbc.query(
        """
        SELECT subject, email, full_name, employee_ref, department, designation, phone,
               branch_code, status, last_login_at
          FROM bre_ctl.users WHERE tenant_id = ? ORDER BY email
        """,
        rs -> {
          String subject = rs.getString("subject");
          out.add(
              new Profile(
                  subject,
                  rs.getString("email"),
                  rs.getString("full_name"),
                  rs.getString("employee_ref"),
                  rs.getString("department"),
                  rs.getString("designation"),
                  rs.getString("phone"),
                  rs.getString("branch_code"),
                  rs.getString("status"),
                  rolesOf(tenantId, subject),
                  Optional.ofNullable(rs.getTimestamp("last_login_at"))
                      .map(java.sql.Timestamp::toInstant)));
        },
        tenantId);
    return out;
  }

  /**
   * The roles granted to an account in a tenant.
   *
   * @param tenantId the tenant
   * @param subject the account
   * @return the roles; unknown role strings are ignored rather than failing, so a role added to the
   *     database by a newer version does not break an older service
   */
  public Set<Role> rolesOf(String tenantId, String subject) {
    Set<Role> roles = EnumSet.noneOf(Role.class);
    jdbc.query(
        "SELECT * FROM bre_ctl.roles_of(?, ?)",
        rs -> {
          try {
            roles.add(Role.valueOf(rs.getString("role")));
          } catch (IllegalArgumentException unknownToThisVersion) {
            // Deliberately ignored. A role this build does not know cannot grant a capability
            // this build can check, so the safe reading is "no capability from this grant".
          }
        },
        tenantId,
        subject);
    return roles;
  }

  /**
   * Grants a role.
   *
   * @param tenantId the tenant
   * @param subject the account
   * @param role the role
   * @param grantedBy who granted it
   */
  public void grant(String tenantId, String subject, Role role, String grantedBy) {
    jdbc.update(
        """
        INSERT INTO bre_ctl.role_assignments (tenant_id, subject, role, granted_by)
        VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
        """,
        tenantId,
        subject,
        role.name(),
        grantedBy);
  }

  /**
   * Revokes a role.
   *
   * @param tenantId the tenant
   * @param subject the account
   * @param role the role
   * @return true when a grant was removed
   */
  public boolean revoke(String tenantId, String subject, Role role) {
    return jdbc.update(
            "DELETE FROM bre_ctl.role_assignments WHERE tenant_id = ? AND subject = ? AND role = ?",
            tenantId,
            subject,
            role.name())
        > 0;
  }

  /**
   * Sets an account's status.
   *
   * @param subject the account
   * @param status ACTIVE, SUSPENDED, LOCKED or CLOSED
   */
  public void setStatus(String subject, String status) {
    jdbc.update(
        "UPDATE bre_ctl.users SET status = ?, updated_at = now() WHERE subject = ?",
        status,
        subject);
  }

  /**
   * Replaces a password and clears any lockout.
   *
   * @param subject the account
   * @param passwordHash the new hash
   * @param mustChange whether the next login must change it again
   */
  public void setPassword(String subject, String passwordHash, boolean mustChange) {
    jdbc.update(
        """
        UPDATE bre_ctl.users
           SET password_hash = ?, password_set_at = now(), must_change_password = ?,
               failed_attempts = 0, locked_until = NULL, updated_at = now()
         WHERE subject = ?
        """,
        passwordHash,
        mustChange,
        subject);
  }

  /**
   * Records a failed login and locks the account when the threshold is reached.
   *
   * @param subject the account
   * @param threshold how many consecutive failures trigger a lockout
   * @param lockMinutes how long the lockout lasts
   * @return the new failure count
   */
  public int recordFailedLogin(String subject, int threshold, int lockMinutes) {
    // Pre-tenant, like the lookup: a failed login has not established a tenant. One statement
    // inside the function, so a burst of concurrent attempts cannot interleave a read and a write
    // and lose counts — which is exactly the situation a brute-force attempt creates.
    Integer attempts =
        jdbc.queryForObject(
            "SELECT bre_ctl.login_record_failure(?, ?, ?)",
            Integer.class,
            subject,
            threshold,
            lockMinutes);
    return attempts == null ? 0 : attempts;
  }

  /**
   * Records a successful login, clearing the failure counter.
   *
   * @param subject the account
   */
  public void recordSuccessfulLogin(String subject) {
    // queryForObject, not update: a SELECT over a function returns a result set even when the
    // function returns void, and JdbcTemplate.update refuses a statement that produced one.
    jdbc.queryForObject("SELECT bre_ctl.login_record_success(?)", String.class, subject);
  }

  // --- sessions ---------------------------------------------------------------------------

  /**
   * Stores a session by its token digest.
   *
   * @param tokenDigest the SHA-256 of the bearer token
   * @param subject the account
   * @param tenantId the tenant
   * @param expiresAt when it expires
   */
  public void insertSession(
      byte[] tokenDigest, String subject, String tenantId, Instant expiresAt) {
    jdbc.update(
        """
        INSERT INTO bre_ctl.user_sessions (token_sha256, subject, tenant_id, expires_at)
        VALUES (?, ?, ?, ?)
        """,
        tokenDigest,
        subject,
        tenantId,
        java.sql.Timestamp.from(expiresAt));
  }

  /**
   * Resolves a live session.
   *
   * @param tokenDigest the SHA-256 of the presented token
   * @return the subject and tenant, when the session exists, has not expired and was not revoked
   */
  public Optional<String[]> findLiveSession(byte[] tokenDigest) {
    // Pre-tenant: resolving the token is what establishes the tenant, so it cannot already be set.
    // The function also stamps last_seen_at, which is why touchSession is now a no-op.
    return jdbc
        .query(
            "SELECT * FROM bre_ctl.session_lookup(?)",
            (rs, rowNum) -> new String[] {rs.getString("subject"), rs.getString("tenant_id")},
            tokenDigest)
        .stream()
        .findFirst();
  }

  /**
   * Marks a session's last use.
   *
   * @param tokenDigest the session
   */
  public void touchSession(byte[] tokenDigest) {
    // Nothing to do: bre_ctl.session_lookup stamps last_seen_at in the same statement that
    // resolves the token. Kept as a method so the call site still reads as "this session was
    // used", and so that moving the stamp back out of the function later changes one place.
  }

  /**
   * Revokes one session — a logout.
   *
   * @param tokenDigest the session
   * @param revokedBy who revoked it
   */
  public void revokeSession(byte[] tokenDigest, String revokedBy) {
    jdbc.update(
        """
        UPDATE bre_ctl.user_sessions SET revoked_at = now(), revoked_by = ?
         WHERE token_sha256 = ? AND revoked_at IS NULL
        """,
        revokedBy,
        tokenDigest);
  }

  /**
   * Revokes every live session of an account.
   *
   * <p>Called when an account is suspended or its password changes. Without it, suspending a leaver
   * would leave their existing session working until it expired — which is the one moment the
   * control exists for.
   *
   * @param subject the account
   * @param revokedBy who revoked them
   * @return how many were revoked
   */
  public int revokeAllSessions(String subject, String revokedBy) {
    return jdbc.update(
        """
        UPDATE bre_ctl.user_sessions SET revoked_at = now(), revoked_by = ?
         WHERE subject = ? AND revoked_at IS NULL AND expires_at > now()
        """,
        revokedBy,
        subject);
  }

  /**
   * Whether a tenant still permits a §3.3 override.
   *
   * @param tenantId the tenant
   * @return the setting
   */
  public boolean sodOverrideAllowed(String tenantId) {
    // Pre-tenant: this lands on the Principal, which is built before bre.tenant_id is set.
    Boolean allowed =
        jdbc.queryForObject("SELECT bre_ctl.tenant_sod_override(?)", Boolean.class, tenantId);
    return Boolean.TRUE.equals(allowed);
  }

  /**
   * Whether a tenant has any account at all.
   *
   * @param tenantId the tenant
   * @return true when at least one user exists
   */
  public boolean hasAnyUser(String tenantId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM bre_ctl.users WHERE tenant_id = ?", Integer.class, tenantId);
    return count != null && count > 0;
  }
}
