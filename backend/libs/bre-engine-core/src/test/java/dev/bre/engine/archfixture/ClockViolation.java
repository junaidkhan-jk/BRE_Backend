package dev.bre.engine.archfixture;

import java.time.LocalDate;

/**
 * DELIBERATE VIOLATION of LLD §1.2 rule 1 (determinism, D8). Exists so {@code EngineArchRulesTest}
 * can prove the rule fires. Never referenced by production code.
 */
public final class ClockViolation {

  private ClockViolation() {}

  /**
   * Reads the wall clock instead of using {@code asOf}. This is the defect the rule catches.
   *
   * <p>Error Prone's {@code JavaTimeDefaultTimeZone} flags this independently, which is a useful
   * confirmation rather than a problem: two different tools agree that reading the clock here is
   * wrong. Suppressed because the whole purpose of this file is to contain the violation — if the
   * call were removed to satisfy the compiler, {@code EngineArchRulesTest} would pass whether or
   * not the ArchUnit rule actually works.
   */
  @SuppressWarnings("JavaTimeDefaultTimeZone")
  public static LocalDate today() {
    return LocalDate.now();
  }
}
