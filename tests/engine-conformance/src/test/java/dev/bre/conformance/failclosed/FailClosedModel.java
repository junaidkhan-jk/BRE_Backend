package dev.bre.conformance.failclosed;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A deliberately tiny model of the decision outcome, enough to state the fail-closed property
 * before the real engine exists.
 *
 * <p>This is not a second implementation of the engine and must never grow into one. It exists so
 * that LLD §13 property 2 — "removing any fact never turns a non-APPROVE decision into APPROVE" —
 * is written down as executable code at M0, and so that the harness can be shown to catch a
 * fail-open engine. M1-09 points the same property at the real engine and deletes the stubs.
 */
public final class FailClosedModel {

  private FailClosedModel() {}

  /** Rule status, per 00 §3.5. Ordered least to most severe for WORST aggregation. */
  public enum Status {
    /** The rule did not apply. */
    NOT_APPLICABLE,
    /** The rule passed. */
    PASS,
    /** The rule passed within an approved tolerance. */
    DEVIATION,
    /** A fact the rule needed was missing. */
    DATA_MISSING,
    /** The rule failed. */
    FAIL,
    /** The rule could not be evaluated. */
    ERROR
  }

  /** Overall decision, per 00 §3.5. */
  public enum Decision {
    /** Straight-through approval. */
    APPROVE,
    /** Approved, with at least one deviation. */
    APPROVE_WITH_DEVIATION,
    /** Sent for human review. */
    REFER,
    /** Declined. */
    DECLINE
  }

  /** What a rule does when a fact it needs is missing (00 §3.5, LLD §4.6). */
  public enum OnMissing {
    /** Refer the application for review. */
    REFER,
    /** Treat the rule as failed. */
    FAIL
  }

  /**
   * One rule in the toy policy: it reads a single fact and passes when that fact is at least its
   * threshold.
   *
   * @param factName the fact this rule reads
   * @param threshold the value the fact must reach to pass
   * @param onMissing what happens when the fact is absent
   */
  public record Rule(String factName, long threshold, OnMissing onMissing) {}

  /**
   * A fact snapshot. Absent keys are missing facts; there is no explicit null, matching the vector
   * format.
   *
   * @param values the present facts
   */
  public record Facts(Map<String, Long> values) {

    /** Returns a copy of these facts with one key removed. */
    public Facts without(String factName) {
      Map<String, Long> copy = new LinkedHashMap<>(values);
      copy.remove(factName);
      return new Facts(Map.copyOf(copy));
    }

    /** Returns the value of a fact, empty when it is missing. */
    public Optional<Long> get(String factName) {
      return Optional.ofNullable(values.get(factName));
    }
  }

  /** An engine under test, reduced to the one method the property needs. */
  public interface Engine {
    /**
     * Decides on a policy and a fact snapshot.
     *
     * @param policy the rules to evaluate
     * @param facts the fact snapshot
     * @return the overall decision
     */
    Decision decide(java.util.List<Rule> policy, Facts facts);
  }
}
