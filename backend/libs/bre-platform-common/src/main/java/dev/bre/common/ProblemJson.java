package dev.bre.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * An RFC 9457 {@code application/problem+json} body (LLD §6.6, §10).
 *
 * <p>Hand-built rather than mapped from an exception, because what a decision API may say in an
 * error is a deliberate decision and not a serialisation default. §9.6 forbids fact values in logs,
 * and an error body is a log entry in every integration that records its calls — so this carries a
 * field <em>path</em> and never the value that failed validation.
 *
 * <p>{@code traceId} is on every problem on purpose: the first thing a lender's integration team
 * asks is "which request was that", and an error without a correlation handle turns a two-minute
 * lookup into a conversation.
 *
 * @param type a URI identifying the problem kind
 * @param title a short, stable summary
 * @param status the HTTP status
 * @param detail what went wrong, naming no fact value
 * @param instance the request's trace identifier
 * @param errors per-field problems, by field path
 */
public record ProblemJson(
    String type,
    String title,
    int status,
    String detail,
    Optional<String> instance,
    Map<String, String> errors) {

  /** The base of every BRE problem type URI. */
  public static final String TYPE_BASE = "https://bre.dev/problems/";

  /** The media type a problem body is served as. */
  public static final String MEDIA_TYPE = "application/problem+json";

  /** Creates a problem, copying the error map. */
  public ProblemJson {
    errors = Map.copyOf(errors);
  }

  /**
   * A problem with no per-field detail.
   *
   * @param kind the problem kind, appended to {@link #TYPE_BASE}
   * @param title a short summary
   * @param status the HTTP status
   * @param detail what went wrong
   * @return the problem
   */
  public static ProblemJson of(String kind, String title, int status, String detail) {
    return new ProblemJson(TYPE_BASE + kind, title, status, detail, Optional.empty(), Map.of());
  }

  /**
   * Returns a copy carrying a trace identifier.
   *
   * @param traceId the request's trace id
   * @return the problem with its instance set
   */
  public ProblemJson withTrace(String traceId) {
    return new ProblemJson(type, title, status, detail, Optional.ofNullable(traceId), errors);
  }

  /**
   * Returns a copy with one field-level error added.
   *
   * @param fieldPath the field's path — never its value (§9.6)
   * @param problem what is wrong with it
   * @return the problem with the error added
   */
  public ProblemJson withFieldError(String fieldPath, String problem) {
    Map<String, String> merged = new LinkedHashMap<>(errors);
    merged.put(fieldPath, problem);
    return new ProblemJson(type, title, status, detail, instance, merged);
  }
}
