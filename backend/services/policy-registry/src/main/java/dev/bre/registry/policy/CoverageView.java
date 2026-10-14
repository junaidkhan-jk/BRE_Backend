package dev.bre.registry.policy;

import dev.bre.engine.cover.CoverageReport;
import java.math.BigDecimal;
import java.util.List;

/**
 * A coverage report as it is stored and served (FR-TEST-003).
 *
 * <p><b>Why this exists rather than serialising {@link CoverageReport} directly.</b> The engine
 * library is Maven-Central-only and carries no Jackson, by the dependency rule in LLD §1.2 — so its
 * percentages are methods, not record components, and a serialiser would silently drop every one of
 * them. Writing the shape here means the number the submit gate compares against the threshold, the
 * number stored in {@code compile_reports.coverage}, and the number the studio displays are
 * computed once, by the engine, and cannot drift apart.
 *
 * @param overallPercent the pooled figure the submit gate uses
 * @param ruleStatusPercent of the rule statuses the policy can reach
 * @param rowPercent of the decision-table rows
 * @param binPercent of the scorecard bins
 * @param ruleStatusesTotal how many (rule, status) pairs exist
 * @param ruleStatusesCovered how many a test case produced
 * @param rowsTotal how many rows exist
 * @param rowsCovered how many fired
 * @param binsTotal how many bins exist
 * @param binsCovered how many scored
 * @param uncoveredRuleStatuses the pairs no test reached
 * @param uncoveredRows the rows no test fired — AC-TEST-003 asks for these by name
 * @param uncoveredBins the bins no test scored
 */
public record CoverageView(
    BigDecimal overallPercent,
    BigDecimal ruleStatusPercent,
    BigDecimal rowPercent,
    BigDecimal binPercent,
    int ruleStatusesTotal,
    int ruleStatusesCovered,
    int rowsTotal,
    int rowsCovered,
    int binsTotal,
    int binsCovered,
    List<String> uncoveredRuleStatuses,
    List<String> uncoveredRows,
    List<String> uncoveredBins) {

  /** Creates a view, copying every list. */
  public CoverageView {
    uncoveredRuleStatuses = List.copyOf(uncoveredRuleStatuses);
    uncoveredRows = List.copyOf(uncoveredRows);
    uncoveredBins = List.copyOf(uncoveredBins);
  }

  /**
   * Converts an engine report.
   *
   * @param report the report
   * @return the view
   */
  public static CoverageView of(CoverageReport report) {
    return new CoverageView(
        report.overallPercent(),
        report.ruleStatusPercent(),
        report.rowPercent(),
        report.binPercent(),
        report.ruleStatusesTotal(),
        report.ruleStatusesCovered(),
        report.rowsTotal(),
        report.rowsCovered(),
        report.binsTotal(),
        report.binsCovered(),
        report.uncoveredRuleStatuses(),
        report.uncoveredRows(),
        report.uncoveredBins());
  }
}
