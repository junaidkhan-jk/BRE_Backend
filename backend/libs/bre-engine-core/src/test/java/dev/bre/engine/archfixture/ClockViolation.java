package dev.bre.engine.archfixture;

import java.time.LocalDate;

/**
 * DELIBERATE VIOLATION of LLD §1.2 rule 1 (determinism, D8). Exists so {@code EngineArchRulesTest}
 * can prove the rule fires. Never referenced by production code.
 */
public final class ClockViolation {

  private ClockViolation() {}

  /** Reads the wall clock instead of using {@code asOf}. This is the defect the rule catches. */
  public static LocalDate today() {
    return LocalDate.now();
  }
}
