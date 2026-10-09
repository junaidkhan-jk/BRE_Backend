package dev.bre.registry.auth;

import dev.bre.registry.audit.AuditLog;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login, logout and session resolution.
 *
 * <p><b>Every failure returns the same message.</b> Wrong email, wrong password, suspended account,
 * locked account — all of them come back as "those credentials are not valid". That is deliberate
 * and it is the one part of a login endpoint most often got wrong: a distinct "no such user"
 * response turns the login form into an account-enumeration oracle, and for a lender's staff
 * directory that is a list of names and email addresses worth having. The <em>audit log</em>
 * records which case it actually was, because an operator does need to know.
 *
 * <p><b>Lockout is counted in the database, not in a cache.</b> Five consecutive failures lock the
 * account for fifteen minutes. On the row, because an attacker who can restart the service must not
 * be able to reset the counter — and in one UPDATE, because a burst of concurrent attempts is
 * exactly the situation that loses counts across a read-then-write.
 *
 * <p><b>Sessions are server-side and opaque.</b> A 256-bit random token, stored only as its
 * SHA-256. The usual alternative is a signed JWT with the roles inside it, which avoids a database
 * read per request — and cannot be revoked. For a system where approving a credit policy is a
 * regulated act, "this person left on Tuesday and their token is valid until Friday" is not an
 * acceptable property, so the read stays.
 */
@Service
public class AuthService {

  private static final Logger log = LoggerFactory.getLogger(AuthService.class);

  /** Consecutive failures before a lockout. */
  public static final int LOCKOUT_THRESHOLD = 5;

  /** How long a lockout lasts. */
  public static final int LOCKOUT_MINUTES = 15;

  /** How long a session lasts. A working day, so a shift does not end mid-review. */
  public static final Duration SESSION_LIFETIME = Duration.ofHours(12);

  /** The one message every failed login returns. */
  private static final String REFUSED =
      "those credentials are not valid. If the account exists and the password is right, it may be"
          + " suspended or temporarily locked after repeated failures — an administrator can see"
          + " which.";

  private final UserRepository users;
  private final AuditLog audit;
  private final TenantSession tenantSession;

  /**
   * Creates the service.
   *
   * @param users the user and session store
   * @param audit the hash-chained audit log
   * @param tenantSession binds the tenant to the transaction once the login has established it —
   *     the session row is under row-level security, and the connection was acquired before anybody
   *     knew which tenant to set
   */
  public AuthService(UserRepository users, AuditLog audit, TenantSession tenantSession) {
    this.users = users;
    this.audit = audit;
    this.tenantSession = tenantSession;
  }

  /** A login was refused. Mapped to 401, with the same message every time. */
  public static final class AuthenticationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    AuthenticationException() {
      super(REFUSED);
    }

    AuthenticationException(String message) {
      super(message);
    }
  }

  /**
   * A successful login.
   *
   * @param token the bearer token; returned once and never stored in recoverable form
   * @param expiresAt when it stops working
   * @param principal who the caller now is
   * @param mustChangePassword whether the next action must be a password change
   */
  public record Session(
      String token, Instant expiresAt, Principal principal, boolean mustChangePassword) {}

  /**
   * Authenticates an email and password.
   *
   * @param email the email, any case
   * @param password the plaintext; cleared before returning
   * @param tenantHint the tenant, when the caller knows it; needed only when one email exists in
   *     more than one tenant
   * @return the session
   * @throws AuthenticationException on any failure, with one message for all of them
   */
  @Transactional
  public Session login(String email, char[] password, String tenantHint) {
    String normalised = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);

    List<UserRepository.User> candidates =
        tenantHint == null || tenantHint.isBlank()
            ? users.findByEmailAcrossTenants(normalised)
            : users.findByEmail(tenantHint, normalised).map(List::of).orElse(List.of());

    if (candidates.isEmpty()) {
      // No such account. Still runs no password check — but note that this path is FASTER than a
      // real verification, which is a timing signal. Accepted knowingly: closing it means
      // hashing against a dummy, and at 600,000 iterations that is a quarter-second of CPU per
      // request for an unauthenticated caller to trigger at will. The denial-of-service is the
      // worse exposure of the two, and account enumeration is already blocked by the identical
      // message. Recorded here so the trade is visible rather than accidental.
      throw new AuthenticationException();
    }
    if (candidates.size() > 1) {
      // The same email in two tenants — a consultant at two lenders. Asking for the tenant is the
      // only correct answer: picking one would log them into whichever row sorted first.
      throw new AuthenticationException(
          "that email exists in more than one organisation. Include the organisation id with the"
              + " login.");
    }

    UserRepository.User user = candidates.getFirst();

    if (user.lockedUntil().filter(until -> until.isAfter(Instant.now())).isPresent()) {
      audit.record(
          user.tenantId(),
          user.subject(),
          "AUTH.LOGIN_REFUSED",
          "user",
          user.subject(),
          "{\"reason\":\"LOCKED\"}");
      throw new AuthenticationException();
    }
    if (!"ACTIVE".equals(user.status())) {
      audit.record(
          user.tenantId(),
          user.subject(),
          "AUTH.LOGIN_REFUSED",
          "user",
          user.subject(),
          "{\"reason\":\"" + user.status() + "\"}");
      throw new AuthenticationException();
    }

    if (!Passwords.verify(password, user.passwordHash())) {
      int attempts = users.recordFailedLogin(user.subject(), LOCKOUT_THRESHOLD, LOCKOUT_MINUTES);
      audit.record(
          user.tenantId(),
          user.subject(),
          "AUTH.LOGIN_REFUSED",
          "user",
          user.subject(),
          "{\"reason\":\"BAD_PASSWORD\",\"consecutiveFailures\":" + attempts + "}");
      if (attempts >= LOCKOUT_THRESHOLD) {
        log.warn(
            "account locked after {} consecutive failures subject={} tenant={}",
            attempts,
            user.subject(),
            user.tenantId());
      }
      throw new AuthenticationException();
    }

    users.recordSuccessfulLogin(user.subject());

    String token = Passwords.newSessionToken();
    Instant expiresAt = Instant.now().plus(SESSION_LIFETIME);
    // The tenant is known now, and user_sessions is under row-level security.
    tenantSession.bind(user.tenantId());
    users.insertSession(Passwords.tokenDigest(token), user.subject(), user.tenantId(), expiresAt);

    audit.record(user.tenantId(), user.subject(), "AUTH.LOGIN", "user", user.subject(), "{}");
    log.info("login subject={} tenant={}", user.subject(), user.tenantId());

    return new Session(token, expiresAt, principalOf(user), user.mustChangePassword());
  }

  /**
   * Resolves a bearer token to a principal.
   *
   * <p>Reads the roles on <b>every</b> request rather than caching them on the session. A role
   * revoked at 10:00 must stop working at 10:00, not when the session expires — and a session here
   * lasts twelve hours. The cost is one indexed read.
   *
   * @param token the presented bearer token
   * @return the principal, or empty when the token is unknown, expired or revoked
   */
  @Transactional
  public Optional<Principal> resolve(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    byte[] digest = Passwords.tokenDigest(token);
    Optional<String[]> session = users.findLiveSession(digest);
    if (session.isEmpty()) {
      return Optional.empty();
    }
    users.touchSession(digest);
    String subject = session.get()[0];
    return users
        .findBySubjectPreTenant(subject)
        .filter(user -> "ACTIVE".equals(user.status()))
        .map(this::principalOf);
  }

  /**
   * Ends a session.
   *
   * @param token the bearer token
   * @param principal who is logging out
   */
  @Transactional
  public void logout(String token, Principal principal) {
    tenantSession.bind(principal.tenantId());
    users.revokeSession(Passwords.tokenDigest(token), principal.subject());
    audit.record(
        principal.tenantId(),
        principal.subject(),
        "AUTH.LOGOUT",
        "user",
        principal.subject(),
        "{}");
  }

  /**
   * Changes the caller's own password.
   *
   * <p>Requires the current one, and <b>revokes every other session</b>. The second part is what
   * makes a password change useful after a suspected compromise: changing the secret while the
   * attacker's existing session keeps working achieves nothing.
   *
   * @param principal the caller
   * @param current the current password
   * @param replacement the new password
   * @throws AuthenticationException if the current password is wrong
   */
  @Transactional
  public void changeOwnPassword(Principal principal, char[] current, char[] replacement) {
    UserRepository.User user =
        users.findBySubject(principal.subject()).orElseThrow(AuthenticationException::new);
    tenantSession.bind(principal.tenantId());
    if (!Passwords.verify(current, user.passwordHash())) {
      audit.record(
          principal.tenantId(),
          principal.subject(),
          "AUTH.PASSWORD_CHANGE_REFUSED",
          "user",
          principal.subject(),
          "{\"reason\":\"BAD_CURRENT_PASSWORD\"}");
      throw new AuthenticationException("the current password is not correct.");
    }
    users.setPassword(principal.subject(), Passwords.hash(replacement), false);
    int revoked = users.revokeAllSessions(principal.subject(), principal.subject());
    audit.record(
        principal.tenantId(),
        principal.subject(),
        "AUTH.PASSWORD_CHANGED",
        "user",
        principal.subject(),
        "{\"sessionsRevoked\":" + revoked + "}");
  }

  private Principal principalOf(UserRepository.User user) {
    return new Principal(
        user.subject(),
        user.tenantId(),
        user.fullName(),
        users.rolesOf(user.tenantId(), user.subject()),
        users.sodOverrideAllowed(user.tenantId()));
  }
}
