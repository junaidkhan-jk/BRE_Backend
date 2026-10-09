package dev.bre.decision.api;

import dev.bre.engine.bel.BelSyntaxException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns failures into RFC 9457 {@code application/problem+json} (LLD §6.6).
 *
 * <p>Every handler here is careful about one thing: <b>no fact value reaches the response body</b>.
 * A validation message that quotes the offending input would put customer data into client logs,
 * proxies and error trackers, where the engine no longer controls retention (§9.6). Field
 * <em>names</em> and expression text are safe — an expression is policy a human authored, not
 * customer data — so those are what the client gets.
 */
@RestControllerAdvice
public class ApiErrorHandler {

  /** Problem type base, per §6.6. */
  private static final String TYPE_BASE = "https://bre.dev/problems/";

  /**
   * A malformed request body.
   *
   * @param exception the validation failure
   * @return a problem+json 400 naming the offending fields, never their values
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail onValidationFailure(MethodArgumentNotValidException exception) {
    String fields =
        exception.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + " " + error.getDefaultMessage())
            .sorted()
            .reduce((a, b) -> a + "; " + b)
            .orElse("the request body is not valid");

    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(URI.create(TYPE_BASE + "invalid-request"));
    problem.setTitle("Invalid request");
    problem.setDetail(fields);
    return problem;
  }

  /**
   * A policy that does not parse.
   *
   * @param exception the syntax error, which carries a line and column
   * @return a problem+json 422 with the position of the error
   */
  @ExceptionHandler(BelSyntaxException.class)
  public ProblemDetail onBelSyntaxError(BelSyntaxException exception) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    problem.setType(URI.create(TYPE_BASE + "invalid-expression"));
    problem.setTitle("Invalid BEL expression");
    problem.setDetail(exception.getMessage());
    problem.setProperty("line", exception.line());
    problem.setProperty("column", exception.column());
    return problem;
  }

  /**
   * A policy could not be resolved (§8.1, §8.3).
   *
   * <p>422 rather than 404: the request is well formed and the resource exists conceptually — this
   * pod will not serve that policy to that caller right now. The reason is named, because the three
   * causes need entirely different responses from the integrator. An unserved policy is a
   * deployment question; a refused pin usually means the grandfather window has closed and the
   * caller should stop pinning; no manifest at all is this pod's problem, not the caller's.
   *
   * <p>No bundle content, no facts — identifiers only.
   *
   * @param exception the failure
   * @return the problem body
   */
  @ExceptionHandler(dev.bre.decision.policy.PolicyResolutionException.class)
  public ProblemDetail onPolicyNotResolved(
      dev.bre.decision.policy.PolicyResolutionException exception) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    problem.setTitle("The policy could not be resolved");
    problem.setType(java.net.URI.create("https://bre.dev/problems/policy-not-resolved"));
    problem.setDetail(exception.getMessage());
    problem.setProperty("reason", exception.reason().name());
    return problem;
  }

  /**
   * A bundle failed verification (§2.6, §8.6).
   *
   * <p>502, not 422: the caller did nothing wrong. Something between the registry and this pod
   * produced a bundle that does not verify, and that is an operational incident — a tampered object
   * store, a misconfigured trust key, or a bundle signed by a key this installation does not know.
   *
   * @param exception the failure
   * @return the problem body
   */
  @ExceptionHandler(dev.bre.bundle.BundleException.class)
  public ProblemDetail onBundleNotVerified(dev.bre.bundle.BundleException exception) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
    problem.setTitle("The policy bundle could not be verified");
    problem.setType(java.net.URI.create("https://bre.dev/problems/bundle-not-verified"));
    problem.setDetail(exception.getMessage());
    problem.setProperty("reason", exception.reason().name());
    return problem;
  }

  /**
   * A request the service understood but cannot accept, such as an unknown {@code onMissing}.
   *
   * @param exception the rejection
   * @return a problem+json 400
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail onIllegalArgument(IllegalArgumentException exception) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(URI.create(TYPE_BASE + "invalid-request"));
    problem.setTitle("Invalid request");
    problem.setDetail(exception.getMessage());
    return problem;
  }
}
