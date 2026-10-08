package dev.bre.engine.decide;

/** Rule status and overall decision (00 §3.5). The two are separate enums, deliberately. */
public final class Outcomes {

  private Outcomes() {}

  /** The outcome of one rule for one entity. */
  public enum Status {
    /** The rule's {@code when} was FALSE: it did not apply. */
    NOT_APPLICABLE,
    /** The rule's {@code check} was TRUE. */
    PASS,
    /** The rule failed within a tolerance that an authority may approve. */
    DEVIATION,
    /** A fact the rule needed was missing. */
    DATA_MISSING,
    /** The rule's {@code check} was FALSE. */
    FAIL,
    /** The rule could not be evaluated — division by zero, fuel exhaustion, and so on. */
    ERROR
  }

  /** The overall decision. */
  public enum Decision {
    /** Straight-through approval. */
    APPROVE,
    /** Approved, with at least one deviation recorded. */
    APPROVE_WITH_DEVIATION,
    /** Sent for human review. */
    REFER,
    /** Declined. */
    DECLINE
  }

  /** What a rule does when a fact it needs is missing (LLD §4.6). */
  public enum OnMissing {
    /** Report DATA_MISSING, which refers the application. The default. */
    DATA_MISSING,
    /** Treat the rule as failed. */
    FAIL,
    /** Treat the rule as not applicable — an explicit author choice that CHK-009 warns about. */
    NOT_APPLICABLE
  }
}
