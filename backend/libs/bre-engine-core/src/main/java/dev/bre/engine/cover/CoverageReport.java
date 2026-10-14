package dev.bre.engine.cover;

import dev.bre.engine.ir.Ir;
import dev.bre.engine.ir.Ir.RuntimeStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a test suite exercised, against what the policy contains (FR-TEST-003).
 *
 * <p>Three measurements, because they answer three different questions and one number would hide
 * all of them:
 *
 * <ul>
 *   <li><b>Rule statuses.</b> Not "did this rule run" — a rule that only ever PASSES in every test
 *       case has never had its failure path exercised, and the failure path is the one that
 *       declines a borrower. So the denominator is every status a rule can reach, and the three
 *       bands a rule has are typically PASS, its {@code onFail} status, and whatever {@code
 *       onMissing} does.
 *   <li><b>Decision-table rows.</b> AC-TEST-003 asks for a row never hit by any test to be listed
 *       by name. An untested row in a pricing or authority grid is a policy nobody has ever seen
 *       run.
 *   <li><b>Scorecard bins.</b> Same argument per characteristic: a band that has never scored is a
 *       band whose points nobody has checked.
 * </ul>
 *
 * <p><b>The missing-data path is counted deliberately.</b> A rule whose {@code onMissing} refers
 * only reaches DATA_MISSING when a test case omits the fact, and "what happens when the bureau is
 * down" is the least-tested and most-consequential path in a credit policy. Including it means a
 * suite that tests only happy paths does not score 100%.
 *
 * <p><b>ERROR is never a target.</b> A runtime error is a defect, not a branch, so demanding a test
 * that provokes one would push authors to write test cases asserting broken behaviour.
 *
 * @param ruleStatusesTotal how many (rule, reachable status) pairs the policy has
 * @param ruleStatusesCovered how many of them a test case produced
 * @param rowsTotal how many decision-table rows the policy has
 * @param rowsCovered how many of them produced a result
 * @param binsTotal how many scorecard bins the policy has
 * @param binsCovered how many of them scored
 * @param uncoveredRuleStatuses the pairs no test reached, as {@code CODE:STATUS}
 * @param uncoveredRows the rows no test fired, as {@code TABLE#index (label)}
 * @param uncoveredBins the bins no test scored, as {@code SCORECARD.characteristic#index}
 */
public record CoverageReport(
    int ruleStatusesTotal,
    int ruleStatusesCovered,
    int rowsTotal,
    int rowsCovered,
    int binsTotal,
    int binsCovered,
    List<String> uncoveredRuleStatuses,
    List<String> uncoveredRows,
    List<String> uncoveredBins) {

  /**
   * How many uncovered items of each kind are named; beyond this the counts still tell the story.
   */
  public static final int MAX_LISTED = 50;

  /** Creates a report, copying every list. */
  public CoverageReport {
    uncoveredRuleStatuses = List.copyOf(uncoveredRuleStatuses);
    uncoveredRows = List.copyOf(uncoveredRows);
    uncoveredBins = List.copyOf(uncoveredBins);
  }

  /** A report for a policy that has nothing measurable in it. */
  public static CoverageReport empty() {
    return new CoverageReport(0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of());
  }

  /**
   * Measures what a probe observed against what a policy contains.
   *
   * @param policy the policy, which supplies every denominator
   * @param probe the probe a test suite ran with
   * @return the report
   */
  public static CoverageReport measure(Ir.PolicyIr policy, CoverageProbe probe) {
    List<String> uncoveredStatuses = new ArrayList<>();
    int statusTotal = 0;
    int statusCovered = 0;
    for (Ir.RuleDef rule : policy.rules()) {
      for (RuntimeStatus status : reachable(rule)) {
        statusTotal++;
        if (probe.outcomes().contains(new CoverageProbe.Outcome(rule.code(), status))) {
          statusCovered++;
        } else if (uncoveredStatuses.size() < MAX_LISTED) {
          uncoveredStatuses.add(rule.code() + ":" + status);
        }
      }
    }

    List<String> uncoveredRows = new ArrayList<>();
    int rowTotal = 0;
    int rowCovered = 0;
    for (Ir.DecisionTableDef table : policy.decisionTables()) {
      for (int index = 0; index < table.rows().size(); index++) {
        rowTotal++;
        if (probe.rows().contains(new CoverageProbe.Row(table.code(), index))) {
          rowCovered++;
        } else if (uncoveredRows.size() < MAX_LISTED) {
          uncoveredRows.add(
              table.code()
                  + "#"
                  + index
                  + table.rows().get(index).label().map(l -> " (" + l + ")").orElse(""));
        }
      }
    }

    List<String> uncoveredBins = new ArrayList<>();
    int binTotal = 0;
    int binCovered = 0;
    for (Ir.ScorecardDef scorecard : policy.scorecards()) {
      for (Ir.Characteristic characteristic : scorecard.characteristics()) {
        for (int index = 0; index < characteristic.bins().size(); index++) {
          binTotal++;
          if (probe
              .bins()
              .contains(new CoverageProbe.Bin(scorecard.code(), characteristic.name(), index))) {
            binCovered++;
          } else if (uncoveredBins.size() < MAX_LISTED) {
            uncoveredBins.add(scorecard.code() + "." + characteristic.name() + "#" + index);
          }
        }
      }
    }

    return new CoverageReport(
        statusTotal,
        statusCovered,
        rowTotal,
        rowCovered,
        binTotal,
        binCovered,
        uncoveredStatuses,
        uncoveredRows,
        uncoveredBins);
  }

  /**
   * Every status a rule can reach, excluding ERROR.
   *
   * <p>Derived from the rule's own declarations rather than assumed: a rule with no guard cannot be
   * NOT_APPLICABLE, so counting that status against it would make 100% unreachable and the whole
   * measurement meaningless.
   */
  private static Set<RuntimeStatus> reachable(Ir.RuleDef rule) {
    Set<RuntimeStatus> statuses = new LinkedHashSet<>();
    statuses.add(RuntimeStatus.PASS);
    statuses.add(
        switch (rule.onFail().status()) {
          case PASS -> RuntimeStatus.PASS;
          case DEVIATION -> RuntimeStatus.DEVIATION;
          case REFER -> RuntimeStatus.REFER;
          case FAIL -> RuntimeStatus.FAIL;
        });
    if (rule.when().isPresent()) {
      statuses.add(RuntimeStatus.NOT_APPLICABLE);
    }
    switch (rule.onMissing().policy()) {
      // §4.6 maps a REFER-on-missing to DATA_MISSING, not REFER, and the distinction matters to an
      // author: one means "the policy chose to refer", the other "we could not tell".
      case REFER -> statuses.add(RuntimeStatus.DATA_MISSING);
      case FAIL -> statuses.add(RuntimeStatus.FAIL);
      case NOT_APPLICABLE -> statuses.add(RuntimeStatus.NOT_APPLICABLE);
      // USE_DEFAULT re-runs the check with the Boolean defaulted, so it produces PASS or the
      // onFail status — both already counted. It adds no distinguishable branch.
      case USE_DEFAULT -> {}
    }
    return statuses;
  }

  /** The percentage of reachable rule statuses a test suite reached, 100 when there are none. */
  public BigDecimal ruleStatusPercent() {
    return percent(ruleStatusesCovered, ruleStatusesTotal);
  }

  /** The percentage of decision-table rows that fired. */
  public BigDecimal rowPercent() {
    return percent(rowsCovered, rowsTotal);
  }

  /** The percentage of scorecard bins that scored. */
  public BigDecimal binPercent() {
    return percent(binsCovered, binsTotal);
  }

  /**
   * The figure the submit gate compares against the tenant's threshold.
   *
   * <p>One pooled ratio over all three kinds, not an average of three percentages: averaging would
   * let a policy with two decision-table rows and two hundred rule statuses hide a completely
   * untested table behind one third of the score.
   *
   * @return the overall percentage
   */
  public BigDecimal overallPercent() {
    return percent(
        ruleStatusesCovered + rowsCovered + binsCovered, ruleStatusesTotal + rowsTotal + binsTotal);
  }

  private static BigDecimal percent(int covered, int total) {
    // A policy with nothing to cover is fully covered. The alternative — 0% — would make a
    // table-free policy unsubmittable for failing a measurement that does not apply to it.
    if (total == 0) {
      return new BigDecimal("100.0");
    }
    return BigDecimal.valueOf(covered)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_EVEN);
  }
}
