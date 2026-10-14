package dev.bre.engine.cover;

import dev.bre.engine.ir.Ir.RuntimeStatus;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Records which parts of a policy a run actually exercised, or does nothing at all (FR-TEST-003).
 *
 * <p><b>{@link #NONE} is the default and costs nothing.</b> This follows {@link
 * dev.bre.engine.trace.TraceRecorder} exactly, and for the same reason: coverage is a studio
 * question, asked while an author is deciding whether their test cases are adequate. A decision in
 * production must not pay for it. The no-op subclass means the decision path's calls compile to a
 * virtual dispatch and an empty method, rather than to a null check every caller could forget.
 *
 * <p><b>What gets recorded is what FIRED, not what matched.</b> Under a FIRST or PRIORITY hit
 * policy several rows can match while only one produces the result; counting the losers as covered
 * would report a row as tested when nothing it says has ever affected an outcome. Under COLLECT
 * every matching row contributes, so every matching row is recorded. That distinction is the whole
 * value of the measurement: AC-TEST-003 asks for a row "never hit by any test" to be listed, and a
 * row whose outputs were discarded has not been tested.
 *
 * <p><b>It only accumulates.</b> Nothing here can change what a component decides, which is what
 * keeps D8 determinism intact: attaching a probe cannot alter the decision, so coverage measured in
 * the studio describes the same evaluation that will run in production.
 */
public class CoverageProbe {

  /** A probe that records nothing. The decision path uses this. */
  public static final CoverageProbe NONE =
      new CoverageProbe() {
        @Override
        public boolean isRecording() {
          return false;
        }

        @Override
        public void component(String code, RuntimeStatus status) {
          // nothing
        }

        @Override
        public void tableRow(String tableCode, int rowIndex) {
          // nothing
        }

        @Override
        public void scorecardBin(String scorecardCode, String characteristic, int binIndex) {
          // nothing
        }
      };

  private final Set<Outcome> outcomes = new LinkedHashSet<>();
  private final Set<Row> rows = new LinkedHashSet<>();
  private final Set<Bin> bins = new LinkedHashSet<>();

  /**
   * One status a component was observed to reach.
   *
   * @param componentCode the rule, table or scorecard code
   * @param status the status it produced
   */
  public record Outcome(String componentCode, RuntimeStatus status) {}

  /**
   * One decision-table row that produced a result.
   *
   * @param tableCode the table
   * @param rowIndex the row's zero-based position in the table as authored
   */
  public record Row(String tableCode, int rowIndex) {}

  /**
   * One scorecard bin that scored.
   *
   * @param scorecardCode the scorecard
   * @param characteristic the characteristic's name
   * @param binIndex the bin's zero-based position within that characteristic
   */
  public record Bin(String scorecardCode, String characteristic, int binIndex) {}

  /**
   * Whether this probe is recording.
   *
   * @return false only for {@link #NONE}
   */
  public boolean isRecording() {
    return true;
  }

  /**
   * Records that a component reached a status.
   *
   * @param code the component's code
   * @param status the status
   */
  public void component(String code, RuntimeStatus status) {
    outcomes.add(new Outcome(code, status));
  }

  /**
   * Records that a decision-table row produced a result.
   *
   * @param tableCode the table
   * @param rowIndex the row's position
   */
  public void tableRow(String tableCode, int rowIndex) {
    rows.add(new Row(tableCode, rowIndex));
  }

  /**
   * Records that a scorecard bin scored.
   *
   * @param scorecardCode the scorecard
   * @param characteristic the characteristic
   * @param binIndex the bin's position
   */
  public void scorecardBin(String scorecardCode, String characteristic, int binIndex) {
    bins.add(new Bin(scorecardCode, characteristic, binIndex));
  }

  /**
   * Every component status observed.
   *
   * @return the set, in first-observed order
   */
  public Set<Outcome> outcomes() {
    return Set.copyOf(outcomes);
  }

  /**
   * Every decision-table row that fired.
   *
   * @return the set
   */
  public Set<Row> rows() {
    return Set.copyOf(rows);
  }

  /**
   * Every scorecard bin that scored.
   *
   * @return the set
   */
  public Set<Bin> bins() {
    return Set.copyOf(bins);
  }
}
