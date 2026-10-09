package dev.bre.engine.check;

import java.util.List;

/**
 * One static-check result (LLD §4.13.2).
 *
 * @param code the catalogue code, e.g. {@code CHK-002}
 * @param severity how much it matters
 * @param component the component it concerns
 * @param message what is wrong
 * @param witnesses concrete inputs that demonstrate it, where the check can produce them
 */
public record CheckFinding(
    String code, Severity severity, String component, String message, List<String> witnesses) {

  /** How much a finding matters. */
  public enum Severity {
    /** Blocks approval. */
    ERROR,
    /** Reported, does not block. */
    WARNING,
    /** Context only. */
    INFO
  }

  /** Creates a finding, copying the witness list. */
  public CheckFinding {
    witnesses = List.copyOf(witnesses);
  }

  /**
   * A finding with no witnesses.
   *
   * @param code the catalogue code
   * @param severity how much it matters
   * @param component the component
   * @param message what is wrong
   * @return the finding
   */
  public static CheckFinding of(String code, Severity severity, String component, String message) {
    return new CheckFinding(code, severity, component, message, List.of());
  }

  /** Whether this finding blocks approval. */
  public boolean isError() {
    return severity == Severity.ERROR;
  }

  @Override
  public String toString() {
    String base = code + " " + severity + " [" + component + "] " + message;
    return witnesses.isEmpty() ? base : base + " e.g. " + witnesses;
  }
}
