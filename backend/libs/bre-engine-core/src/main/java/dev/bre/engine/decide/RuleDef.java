package dev.bre.engine.decide;

import dev.bre.engine.decide.Outcomes.OnMissing;
import java.util.Objects;
import java.util.Optional;

/**
 * One rule, in the subset of the IR the walking skeleton evaluates (LLD §2.2 {@code $defs/rule}).
 *
 * <p>Field names follow the IR: the identifier is {@code code}, not {@code id}. Decision tables,
 * scorecards, flows and per-entity iteration are the rest of M1-07/M1-08 and are not here yet.
 *
 * @param code the rule's identifier, unique within the policy
 * @param check the BEL expression that must be TRUE for the rule to pass
 * @param when an optional BEL guard; when it is FALSE the rule is NOT_APPLICABLE
 * @param onMissing what to do when the check cannot be evaluated for want of a fact
 * @param reasonCode the reason attached to a non-PASS outcome
 */
public record RuleDef(
    String code,
    String check,
    Optional<String> when,
    OnMissing onMissing,
    Optional<String> reasonCode) {

  /** Creates a rule, defaulting {@code onMissing} to DATA_MISSING as the IR does. */
  public RuleDef {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(check, "check");
    when = when == null ? Optional.empty() : when;
    reasonCode = reasonCode == null ? Optional.empty() : reasonCode;
    onMissing = onMissing == null ? OnMissing.DATA_MISSING : onMissing;
  }

  /**
   * Creates a rule with the default {@code onMissing} and no guard.
   *
   * @param code the rule's identifier
   * @param check the BEL check expression
   * @return the rule
   */
  public static RuleDef of(String code, String check) {
    return new RuleDef(code, check, Optional.empty(), OnMissing.DATA_MISSING, Optional.empty());
  }
}
