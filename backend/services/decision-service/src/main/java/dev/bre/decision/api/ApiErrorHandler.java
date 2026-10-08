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
