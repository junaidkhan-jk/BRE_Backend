package dev.bre.registry.api;

import dev.bre.registry.audit.AuditLog;
import dev.bre.registry.auth.Passwords;
import dev.bre.registry.auth.Principal;
import dev.bre.registry.auth.Role;
import dev.bre.registry.auth.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff accounts and role grants — the {@code TENANT_ADMIN} surface (00 §3.12).
 *
 * <p><b>A created account's password is returned once and never again.</b> There is no email
 * delivery in this build, so an administrator has to hand the credential over, and that is the
 * honest mechanism: generated here, shown once in the response, stored only as a PBKDF2 hash, and
 * flagged {@code mustChangePassword} so the person replaces it on first login. An administrator who
 * sets the initial password themselves knows it; a generated one they only pass on is marginally
 * better, and neither is as good as the invitation link an identity provider would send.
 *
 * <p><b>An administrator cannot remove their own last administrator role.</b> That is the one
 * self-inflicted lockout worth refusing outright: a tenant with no TENANT_ADMIN cannot create one,
 * because creating users needs the role that was just removed, and recovery means a direct database
 * write by somebody with production credentials.
 */
@RestController
@RequestMapping("/v1/users")
public class UserAdminController {

  private final UserRepository users;
  private final AuditLog audit;

  /**
   * Creates the controller.
   *
   * @param users the user store
   * @param audit the hash-chained audit log
   */
  public UserAdminController(UserRepository users, AuditLog audit) {
    this.users = users;
    this.audit = audit;
  }

  /**
   * A request to create an account.
   *
   * @param email the login identity
   * @param fullName the person's name
   * @param roles the roles to grant
   * @param employeeRef the lender's own staff id
   * @param department optional
   * @param designation optional
   * @param phone optional, digits only
   * @param branchCode optional
   * @param password an initial password; when absent one is generated and returned once
   */
  public record CreateUserRequest(
      @NotBlank @Email String email,
      @NotBlank String fullName,
      Set<Role> roles,
      String employeeRef,
      String department,
      String designation,
      String phone,
      String branchCode,
      String password) {}

  /**
   * A created account.
   *
   * @param subject the account identifier, which every audit entry records
   * @param email the login identity
   * @param roles the granted roles
   * @param initialPassword shown once; never recoverable afterwards
   */
  public record CreatedUser(
      String subject, String email, Set<Role> roles, String initialPassword) {}

  /**
   * Creates an account and grants its roles.
   *
   * @param request the request
   * @param body the account
   * @return the account, with its one-time password
   */
  @PostMapping
  @Transactional
  public ResponseEntity<CreatedUser> create(
      HttpServletRequest request, @Valid @RequestBody CreateUserRequest body) {

    Principal admin = Authorisation.require(request, Role.Capability.USER_MANAGE);
    String email = body.email().trim().toLowerCase(Locale.ROOT);

    if (users.findByEmail(admin.tenantId(), email).isPresent()) {
      throw new DuplicateUserException(
          "an account with that email already exists in this organisation. Grant it another role"
              + " rather than creating a second account — two accounts for one person means two"
              + " sets of grants and one of them will be forgotten when they leave.");
    }

    String subject = subjectFor(email);
    String password =
        body.password() == null || body.password().isBlank()
            ? Passwords.generate()
            : body.password();

    users.insert(
        subject,
        admin.tenantId(),
        email,
        body.fullName().trim(),
        Passwords.hash(password.toCharArray()),
        // Forced change on first login when WE generated it: the administrator has seen it, and a
        // credential two people know is not a credential. Not forced when the administrator chose
        // it, because they may be setting it up on the person's behalf deliberately.
        body.password() == null || body.password().isBlank(),
        blankToNull(body.employeeRef()),
        blankToNull(body.department()),
        blankToNull(body.designation()),
        blankToNull(body.phone()),
        blankToNull(body.branchCode()),
        admin.subject());

    Set<Role> granted = body.roles() == null ? Set.of() : body.roles();
    granted.forEach(role -> users.grant(admin.tenantId(), subject, role, admin.subject()));

    audit.record(
        admin.tenantId(),
        admin.subject(),
        "USER.CREATED",
        "user",
        subject,
        "{\"roles\":" + jsonRoles(granted) + "}");

    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new CreatedUser(subject, email, granted, password));
  }

  /**
   * Lists this tenant's accounts and their roles.
   *
   * @param request the request
   * @return the profiles
   */
  @GetMapping
  public List<UserRepository.Profile> list(HttpServletRequest request) {
    Principal admin = Authorisation.require(request, Role.Capability.USER_MANAGE);
    return users.listUsers(admin.tenantId());
  }

  /**
   * Grants a role.
   *
   * @param request the request
   * @param subject the account
   * @param role the role
   * @return 204
   */
  @PostMapping("/{subject}/roles/{role}")
  @Transactional
  public ResponseEntity<Void> grant(
      HttpServletRequest request, @PathVariable String subject, @PathVariable Role role) {
    Principal admin = Authorisation.require(request, Role.Capability.ROLE_MANAGE);
    requireSameTenant(admin, subject);
    users.grant(admin.tenantId(), subject, role, admin.subject());
    audit.record(
        admin.tenantId(),
        admin.subject(),
        "USER.ROLE_GRANTED",
        "user",
        subject,
        "{\"role\":\"" + role + "\"}");
    return ResponseEntity.noContent().build();
  }

  /**
   * Revokes a role.
   *
   * @param request the request
   * @param subject the account
   * @param role the role
   * @return 204, or 404 when the grant did not exist
   */
  @DeleteMapping("/{subject}/roles/{role}")
  @Transactional
  public ResponseEntity<Void> revoke(
      HttpServletRequest request, @PathVariable String subject, @PathVariable Role role) {
    Principal admin = Authorisation.require(request, Role.Capability.ROLE_MANAGE);
    requireSameTenant(admin, subject);

    if (role == Role.TENANT_ADMIN && admin.subject().equals(subject)) {
      long remaining =
          users.listUsers(admin.tenantId()).stream()
              .filter(profile -> profile.roles().contains(Role.TENANT_ADMIN))
              .count();
      if (remaining <= 1) {
        throw new DuplicateUserException(
            "you are this organisation's only administrator, so removing your own TENANT_ADMIN"
                + " would leave nobody who can create users or grant roles — and recovering from"
                + " that needs a direct database write. Grant the role to somebody else first.");
      }
    }

    boolean removed = users.revoke(admin.tenantId(), subject, role);
    if (removed) {
      audit.record(
          admin.tenantId(),
          admin.subject(),
          "USER.ROLE_REVOKED",
          "user",
          subject,
          "{\"role\":\"" + role + "\"}");
    }
    return removed
        ? ResponseEntity.noContent().build()
        : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
  }

  /**
   * Suspends an account and revokes its live sessions.
   *
   * <p>The session revocation is the part that matters. Suspending a leaver while their existing
   * session keeps working until it expires — twelve hours here — defeats the point of suspending
   * them at the moment it is done.
   *
   * @param request the request
   * @param subject the account
   * @return 204
   */
  @PostMapping("/{subject}/suspend")
  @Transactional
  public ResponseEntity<Void> suspend(HttpServletRequest request, @PathVariable String subject) {
    Principal admin = Authorisation.require(request, Role.Capability.USER_MANAGE);
    requireSameTenant(admin, subject);
    users.setStatus(subject, "SUSPENDED");
    int revoked = users.revokeAllSessions(subject, admin.subject());
    audit.record(
        admin.tenantId(),
        admin.subject(),
        "USER.SUSPENDED",
        "user",
        subject,
        "{\"sessionsRevoked\":" + revoked + "}");
    return ResponseEntity.noContent().build();
  }

  /**
   * Reactivates a suspended or locked account.
   *
   * @param request the request
   * @param subject the account
   * @return 204
   */
  @PostMapping("/{subject}/activate")
  @Transactional
  public ResponseEntity<Void> activate(HttpServletRequest request, @PathVariable String subject) {
    Principal admin = Authorisation.require(request, Role.Capability.USER_MANAGE);
    requireSameTenant(admin, subject);
    users.setStatus(subject, "ACTIVE");
    audit.record(admin.tenantId(), admin.subject(), "USER.ACTIVATED", "user", subject, "{}");
    return ResponseEntity.noContent().build();
  }

  /**
   * Resets an account's password, returning the new one once.
   *
   * @param request the request
   * @param subject the account
   * @return the one-time password
   */
  @PostMapping("/{subject}/reset-password")
  @Transactional
  public CreatedUser resetPassword(HttpServletRequest request, @PathVariable String subject) {
    Principal admin = Authorisation.require(request, Role.Capability.USER_MANAGE);
    requireSameTenant(admin, subject);

    String password = Passwords.generate();
    users.setPassword(subject, Passwords.hash(password.toCharArray()), true);
    // Every session goes. A reset exists because the credential may be compromised, and leaving
    // the compromised session alive would make the reset cosmetic.
    int revoked = users.revokeAllSessions(subject, admin.subject());
    users.setStatus(subject, "ACTIVE");

    audit.record(
        admin.tenantId(),
        admin.subject(),
        "USER.PASSWORD_RESET",
        "user",
        subject,
        "{\"sessionsRevoked\":" + revoked + "}");

    return new CreatedUser(
        subject,
        users.findBySubject(subject).map(UserRepository.User::email).orElse(""),
        users.rolesOf(admin.tenantId(), subject),
        password);
  }

  /** An account already exists, or an action would lock the tenant out. Mapped to 409. */
  public static final class DuplicateUserException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    DuplicateUserException(String message) {
      super(message);
    }
  }

  private void requireSameTenant(Principal admin, String subject) {
    boolean sameTenant =
        users
            .findBySubject(subject)
            .map(UserRepository.User::tenantId)
            .filter(admin.tenantId()::equals)
            .isPresent();
    if (!sameTenant) {
      // 404 rather than 403 (D1): an administrator of one lender must not be able to discover
      // whether an account exists at another by the shape of the refusal.
      throw new org.springframework.web.server.ResponseStatusException(
          HttpStatus.NOT_FOUND, "no such account in this organisation");
    }
  }

  /**
   * Derives an account identifier from an email.
   *
   * <p>The local part, lowercased, with anything outside the subject pattern replaced. Readable on
   * purpose: this string appears as the {@code actor} on every audit entry the person ever
   * produces, and an audit trail of opaque identifiers is an audit trail somebody has to join
   * before they can read it. A collision is resolved by the caller's duplicate-email check above,
   * which happens first.
   */
  private static String subjectFor(String email) {
    String local = email.substring(0, email.indexOf('@')).toLowerCase(Locale.ROOT);
    String cleaned = local.replaceAll("[^a-z0-9._+-]", "-");
    // The pattern requires at least three characters and a leading alphanumeric.
    String safe = cleaned.length() >= 3 ? cleaned : cleaned + "-user";
    return safe.matches("^[a-z0-9].*") ? safe : "u-" + safe;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String jsonRoles(Set<Role> roles) {
    return "["
        + String.join(",", roles.stream().map(role -> "\"" + role.name() + "\"").toList())
        + "]";
  }
}
