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

  /**
   * Rule status, per 00 §3.5, each carrying its WORST-aggregation severity explicitly.
   *
   * <p>The rank is a field rather than the declaration order. Relying on {@code ordinal()} would
   * mean that reordering these constants — a change that looks cosmetic — silently changed which
   * status wins an aggregation. The engine's own {@code Severity} class exists for the same reason,
   * and there the order is genuinely counter-intuitive: DATA_MISSING outranks DEVIATION, and FAIL
   * outranks ERROR. A severity that can be changed by tidying an enum is a severity nobody can rely
   * on.
   */
  public enum Status {
    /** The rule did not apply. */
    NOT_APPLICABLE(0),
    /** The rule passed. */
    PASS(1),
    /** The rule passed within an approved tolerance. */
    DEVIATION(2),
    /** A fact the rule needed was missing. */
    DATA_MISSING(3),
    /** The rule failed. */
    FAIL(4),
    /** The rule could not be evaluated. */
    ERROR(5);

    private final int severity;

    Status(int severity) {
      this.severity = severity;
    }

    /**
     * Returns this status's WORST-aggregation severity; higher is more severe.
     *
     * @return the severity rank
     */
    public int severity() {
      return severity;
    }
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
