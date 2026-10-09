package dev.bre.registry.api;

import dev.bre.registry.auth.AuthService;
import dev.bre.registry.auth.Passwords;
import dev.bre.registry.policy.PolicyVersionService;
import dev.bre.registry.sign.BundleBuilder;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps every expected failure to RFC 9457 problem+json (§6.6, §10).
 *
 * <p><b>The point is that none of these is a 500.</b> A lifecycle refusal, a weak password, an
 * authorisation failure and a concurrent edit are all <em>normal outcomes of a correctly working
 * service</em>, and returning 500 for them has two costs: the caller cannot tell "you did something
 * not allowed" from "we are broken", and an alert on the 5xx rate becomes noise that gets muted.
 *
 * <p>Each handler names the status it chose and why, because the choice between 400, 403, 409 and
 * 422 is the part that gets picked arbitrarily and then relied on by clients.
 */
@RestControllerAdvice
public class ProblemHandler {

  private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);
  private static final String BASE = "https://bre.dev/problems/";

  /**
   * A lifecycle rule was broken — not a DRAFT, no test cases, reviewer is the author.
   *
   * <p><b>409 Conflict</b>, not 400: the request is well-formed and the caller is permitted. What
   * is wrong is the <em>state</em> of the resource, and a retry of the identical request will
   * succeed once that state changes.
   *
   * @param e the exception
   * @param request the request
   * @return the problem
   */
  @ExceptionHandler(PolicyVersionService.LifecycleException.class)
  public ProblemDetail lifecycle(
      PolicyVersionService.LifecycleException e, HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, "lifecycle", "Not allowed in this state", e, request);
  }

  /**
   * Two people edited one draft.
   *
   * <p><b>409</b>, with the instruction to re-read. The alternative — last write wins — loses the
   * other author's work with no error anywhere, and they find out when the policy misbehaves.
   *
   * @param e the exception
   * @param request the request
   * @return the problem
   */
  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ProblemDetail concurrentEdit(
      OptimisticLockingFailureException e, HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, "concurrent-edit", "Changed since you read it", e, request);
  }

  /**
   * The caller lacks a capability.
   *
   * <p><b>403</b>, and the body names the capability and which roles grant it. A bare "forbidden"
   * becomes a support ticket; a named role is something an administrator can act on.
   *
   * @param e the exception
   * @param request the request
   * @return the problem
   */
  @ExceptionHandler(Authorisation.ForbiddenException.class)
  public ProblemDetail forbidden(Authorisation.ForbiddenException e, HttpServletRequest request) {
    return problem(HttpStatus.FORBIDDEN, "forbidden", "Not permitted", e, request);
  }

  /**
   * A login failed, or a password change presented the wrong current password.
   *
   * <p><b>401</b>, with one message for every cause. See {@link AuthService} for why.
   *
   * @param e the exception
   * @param request the request
   * @return the problem
   */
  @ExceptionHandler(AuthService.AuthenticationException.class)
  public ProblemDetail unauthenticated(
      AuthService.AuthenticationException e, HttpServletRequest request) {
    return problem(HttpStatus.UNAUTHORIZED, "unauthenticated", "Not authenticated", e, request);
  }

  /**
   * A password was too short.
   *
   * <p><b>400</b>: the request itself is wrong and no change of state will make it acceptable.
   *
   * @param e the exception
   * @param request the request
   * @return the problem
   */
  @ExceptionHandler(Passwords.WeakPasswordException.class)
  public ProblemDetail weakPassword(Passwords.WeakPasswordException e, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "weak-password", "Password too short", e, request);
  }

  /**
   * An account already exists, or an action would lock the tenant out of administration.
   *
   * @param e the exception
   * @param request the request
   * @return the problem
   */
  @ExceptionHandler(UserAdminController.DuplicateUserException.class)
  public ProblemDetail duplicateUser(
      UserAdminController.DuplicateUserException e, HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, "user-conflict", "Not allowed", e, request);
  }

  /**
   * The bundle could not be built or signed.
   *
   * <p><b>409</b>: an approval that cannot produce a signed artefact has not happened, and the
   * transaction is rolled back. Logged at ERROR as well, because unlike the others this one may
   * mean the installation is misconfigured rather than the caller mistaken.
   *
   * @param e the exception
   * @param request the request
   * @return the problem
   */
  @ExceptionHandler(BundleBuilder.BuildException.class)
  public ProblemDetail buildFailed(BundleBuilder.BuildException e, HttpServletRequest request) {
    log.error("bundle build failed for {}", request.getRequestURI(), e);
    return problem(HttpStatus.CONFLICT, "bundle-build-failed", "Could not sign", e, request);
  }

  /**
   * No signing key is configured.
   *
   * <p><b>409</b> rather than 500, because it is a configuration state the operator can fix, and
   * the detail says which setting is missing.
   *
   * @param e the exception
   * @param request the request
   * @return the problem
   */
  @ExceptionHandler(dev.bre.registry.sign.SigningKeySource.NoSigningKeyException.class)
  public ProblemDetail noSigningKey(
      dev.bre.registry.sign.SigningKeySource.NoSigningKeyException e, HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, "no-signing-key", "Cannot sign", e, request);
  }

  /**
   * A database constraint refused the write.
   *
   * <p><b>409</b>, and the message carries the SQL state rather than the driver's exception text.
   * §5.2's CHECK constraints are a real part of the model — a policy code's pattern, one active
   * champion per environment — so a violation is usually a caller mistake, and the driver's message
   * quotes the offending row. Quoting it back could echo data (§9.6).
   *
   * @param e the exception
   * @param request the request
   * @return the problem
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ProblemDetail constraint(DataIntegrityViolationException e, HttpServletRequest request) {
    log.warn("constraint violation on {}", request.getRequestURI(), e);
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "the database refused this write. Likely causes: an identifier that does not match its"
                + " required pattern, a duplicate, or a uniqueness rule such as one active"
                + " champion per environment. SQL state: "
                + sqlState(e));
    problem.setType(URI.create(BASE + "constraint-violation"));
    problem.setTitle("Refused by the database");
    problem.setInstance(URI.create(request.getRequestURI()));
    return problem;
  }

  /**
   * The SQL state of the underlying failure.
   *
   * <p>{@code SQLException.getSQLState()} rather than the driver's constraint name. The driver is
   * {@code runtimeOnly} here on purpose — nothing in this service should compile against
   * PostgreSQL-specific types — and the standard five-character state is enough to tell the three
   * cases apart: {@code 23505} unique violation, {@code 23514} check violation, {@code 23503}
   * foreign key. The driver's own message is deliberately NOT included: it quotes the offending
   * row, which can carry data §9.6 keeps out of responses and logs.
   */
  private static String sqlState(DataIntegrityViolationException e) {
    Throwable cause = e.getMostSpecificCause();
    return cause instanceof java.sql.SQLException sql && sql.getSQLState() != null
        ? sql.getSQLState()
        : "unknown";
  }

  private static ProblemDetail problem(
      HttpStatus status,
      String slug,
      String title,
      RuntimeException e,
      HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
    problem.setType(URI.create(BASE + slug));
    problem.setTitle(title);
    problem.setInstance(URI.create(request.getRequestURI()));
    return problem;
  }
}
