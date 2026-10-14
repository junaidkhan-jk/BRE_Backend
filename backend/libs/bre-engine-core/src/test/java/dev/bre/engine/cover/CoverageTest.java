package dev.bre.engine.cover;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bre.engine.decide.ComponentEvaluator;
import dev.bre.engine.eval.EvaluationContext;
import dev.bre.engine.eval.Value;
import dev.bre.engine.eval.Value.RecordV;
import dev.bre.engine.ir.Ir.Bin;
import dev.bre.engine.ir.Ir.Characteristic;
import dev.bre.engine.ir.Ir.DecisionTableDef;
import dev.bre.engine.ir.Ir.FlowDef;
import dev.bre.engine.ir.Ir.HitPolicy;
import dev.bre.engine.ir.Ir.MissingPolicy;
import dev.bre.engine.ir.Ir.OnMissing;
import dev.bre.engine.ir.Ir.Outcome;
import dev.bre.engine.ir.Ir.OutcomeStatus;
import dev.bre.engine.ir.Ir.PolicyIr;
import dev.bre.engine.ir.Ir.RowResult;
import dev.bre.engine.ir.Ir.RuleDef;
import dev.bre.engine.ir.Ir.RuntimeStatus;
import dev.bre.engine.ir.Ir.Scaling;
import dev.bre.engine.ir.Ir.Scope;
import dev.bre.engine.ir.Ir.ScorecardDef;
import dev.bre.engine.ir.Ir.StageDef;
import dev.bre.engine.ir.Ir.TableInput;
import dev.bre.engine.ir.Ir.TableOutput;
import dev.bre.engine.ir.Ir.TableRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What coverage measurement has to get right (FR-TEST-003, AC-TEST-003).
 *
 * <p>The measurement is only worth having if an author can trust it to say "this is untested" when
 * it is. Three ways a naive implementation lies, each pinned below:
 *
 * <ul>
 *   <li>Counting a row that <b>matched and lost</b> to the hit policy as covered. Under FIRST the
 *       losers' outputs are discarded, so nothing they say has ever affected an outcome.
 *   <li>Counting a status a rule <b>cannot reach</b> in the denominator. A rule with no guard can
 *       never be NOT_APPLICABLE; counting it would put 100% out of reach and make the figure
 *       meaningless.
 *   <li>Charging the <b>decision path</b> for the measurement. {@link CoverageProbe#NONE} has to
 *       stay inert, because a probe that accumulated in production would both leak memory and make
 *       a decision's cost depend on how many entities it saw.
 * </ul>
 */
class CoverageTest {

  private static final LocalDate AS_OF = LocalDate.parse("2026-10-01");

  @Test
  @DisplayName(
      "under FIRST, only the row that fired is covered — not the ones that matched and lost")
  void onlyTheFiredRowCounts() {
    // Two rows both match a tier-A applicant. FIRST takes row 0; row 1's outputs are discarded.
    DecisionTableDef table =
        table(
            HitPolicy.Kind.FIRST,
            List.of(
                row("\"A\"", OutcomeStatus.PASS, "0.10"),
                row("-", OutcomeStatus.PASS, "0.20"),
                row("\"Z\"", OutcomeStatus.FAIL, "0.90")));

    CoverageProbe probe = new CoverageProbe();
    ComponentEvaluator.evaluateTable(table, context(probe, "A"));

    assertThat(probe.rows())
        .as("row 1 matched (its cell is the any-value dash) and lost; row 2 never matched")
        .containsExactly(new CoverageProbe.Row("PRICING", 0));

    CoverageReport report =
        CoverageReport.measure(policy(List.of(), List.of(table), List.of()), probe);
    assertThat(report.rowsTotal()).isEqualTo(3);
    assertThat(report.rowsCovered()).isEqualTo(1);
    assertThat(report.uncoveredRows())
        .as("AC-TEST-003: an untested row is listed by name, with its label")
        .containsExactly("PRICING#1 (row-1)", "PRICING#2 (row-2)");
  }

  @Test
  @DisplayName("under COLLECT, every contributing row is covered")
  void collectCoversEveryContributor() {
    // COLLECT aggregates all matching rows, so all of them genuinely ran.
    DecisionTableDef table =
        new DecisionTableDef(
            "PRICING",
            "Pricing",
            Scope.APPLICATION,
            Optional.empty(),
            new HitPolicy(
                HitPolicy.Kind.COLLECT, Optional.of(HitPolicy.CollectOp.SUM), Optional.of("rate")),
            List.of(new TableInput("tier", "application.tier")),
            List.of(new TableOutput("rate", "DECIMAL")),
            List.of(row("\"A\"", null, "0.10"), row("-", null, "0.20"), row("\"Z\"", null, "0.90")),
            OnMissing.DEFAULT,
            Optional.empty());

    CoverageProbe probe = new CoverageProbe();
    ComponentEvaluator.evaluateTable(table, context(probe, "A"));

    assertThat(probe.rows())
        .containsExactlyInAnyOrder(
            new CoverageProbe.Row("PRICING", 0), new CoverageProbe.Row("PRICING", 1));
  }

  @Test
  @DisplayName("a scorecard records which bin of which characteristic scored")
  void scorecardBins() {
    ScorecardDef scorecard =
        new ScorecardDef(
            "BUREAU",
            "Bureau score",
            Scope.MEMBER,
            300,
            List.of(
                new Characteristic(
                    "tier",
                    "application.tier",
                    Optional.of("ADV_TIER"),
                    List.of(
                        new Bin("\"A\"", 100, Optional.empty()),
                        new Bin("\"B\"", 50, Optional.empty()),
                        new Bin("-", 0, Optional.empty())))),
            2,
            Scaling.IDENTITY,
            OnMissing.DEFAULT);

    CoverageProbe probe = new CoverageProbe();
    ComponentEvaluator.evaluateScorecard(scorecard, context(probe, "A"));

    assertThat(probe.bins()).containsExactly(new CoverageProbe.Bin("BUREAU", "tier", 0));

    CoverageReport report =
        CoverageReport.measure(policy(List.of(), List.of(), List.of(scorecard)), probe);
    assertThat(report.binsTotal()).isEqualTo(3);
    assertThat(report.binsCovered()).isEqualTo(1);
    assertThat(report.uncoveredBins()).containsExactly("BUREAU.tier#1", "BUREAU.tier#2");
    assertThat(report.binPercent()).isEqualByComparingTo(new BigDecimal("33.3"));
  }

  @Test
  @DisplayName("a rule's denominator is the statuses it can actually reach, and no others")
  void reachableStatusesOnly() {
    // No guard, onFail FAIL, onMissing REFER -> {PASS, FAIL, DATA_MISSING}. NOT_APPLICABLE is
    // unreachable without a `when`, so counting it would make 100% impossible.
    RuleDef plain =
        new RuleDef(
            "MIN_AGE",
            "Adult",
            Scope.MEMBER,
            Optional.empty(),
            "true",
            Outcome.fail("KYC_UNDER_MIN_AGE"),
            OnMissing.of(MissingPolicy.REFER, null),
            Map.of(),
            Optional.empty());

    CoverageProbe probe = new CoverageProbe();
    probe.component("MIN_AGE", RuntimeStatus.PASS);
    CoverageReport report =
        CoverageReport.measure(policy(List.of(plain), List.of(), List.of()), probe);

    assertThat(report.ruleStatusesTotal()).isEqualTo(3);
    assertThat(report.uncoveredRuleStatuses())
        .containsExactlyInAnyOrder("MIN_AGE:FAIL", "MIN_AGE:DATA_MISSING");
    assertThat(report.ruleStatusPercent()).isEqualByComparingTo(new BigDecimal("33.3"));
  }

  @Test
  @DisplayName("a guarded rule adds NOT_APPLICABLE; USE_DEFAULT adds no branch of its own")
  void guardsAndDefaults() {
    RuleDef guarded =
        new RuleDef(
            "GUARDED",
            "Guarded",
            Scope.MEMBER,
            Optional.of("member.role = \"MEMBER\""),
            "true",
            Outcome.fail("R"),
            // USE_DEFAULT re-runs the check with the Boolean defaulted, producing PASS or the
            // onFail status — both already counted. It is not a fourth branch.
            new OnMissing(MissingPolicy.USE_DEFAULT, Optional.of(false), Optional.empty()),
            Map.of(),
            Optional.empty());

    CoverageReport report =
        CoverageReport.measure(policy(List.of(guarded), List.of(), List.of()), new CoverageProbe());
    assertThat(report.ruleStatusesTotal()).isEqualTo(3);
    assertThat(report.uncoveredRuleStatuses())
        .containsExactlyInAnyOrder("GUARDED:PASS", "GUARDED:FAIL", "GUARDED:NOT_APPLICABLE");
  }

  @Test
  @DisplayName("the overall figure pools all three kinds rather than averaging them")
  void overallIsPooled() {
    // One rule with three statuses, one of them covered. One table with twenty rows, none covered.
    // Averaging the two percentages would report 16.7%; pooling reports 4.3%, which is the honest
    // answer for a suite that has never run a single row of a twenty-row grid.
    RuleDef rule =
        new RuleDef(
            "R",
            "R",
            Scope.APPLICATION,
            Optional.empty(),
            "true",
            Outcome.fail("X"),
            OnMissing.of(MissingPolicy.REFER, null),
            Map.of(),
            Optional.empty());
    List<TableRow> twenty =
        java.util.stream.IntStream.range(0, 20)
            .mapToObj(index -> row("\"T" + index + "\"", OutcomeStatus.PASS, "0.10"))
            .toList();

    CoverageProbe probe = new CoverageProbe();
    probe.component("R", RuntimeStatus.PASS);

    CoverageReport report =
        CoverageReport.measure(
            policy(List.of(rule), List.of(table(HitPolicy.Kind.FIRST, twenty)), List.of()), probe);

    assertThat(report.ruleStatusPercent()).isEqualByComparingTo(new BigDecimal("33.3"));
    assertThat(report.rowPercent()).isEqualByComparingTo(new BigDecimal("0.0"));
    assertThat(report.overallPercent()).isEqualByComparingTo(new BigDecimal("4.3"));
  }

  @Test
  @DisplayName("a policy with nothing measurable is fully covered, not zero")
  void nothingToCover() {
    CoverageReport report =
        CoverageReport.measure(policy(List.of(), List.of(), List.of()), new CoverageProbe());
    assertThat(report.overallPercent())
        .as("0% would make a rule-free policy permanently unsubmittable")
        .isEqualByComparingTo(new BigDecimal("100.0"));
  }

  @Test
  @DisplayName("NONE records nothing, so the decision path pays for no measurement")
  void noneIsInert() {
    DecisionTableDef table =
        table(HitPolicy.Kind.FIRST, List.of(row("-", OutcomeStatus.PASS, "0.10")));
    EvaluationContext decisionPath =
        EvaluationContext.of(
            new RecordV(Map.of("application", new RecordV(Map.of("tier", new Value.TextV("A"))))),
            Map.of(),
            Map.of(),
            AS_OF);

    assertThat(decisionPath.probe().isRecording()).isFalse();
    ComponentEvaluator.evaluateTable(table, decisionPath);
    assertThat(decisionPath.probe().rows()).isEmpty();
    assertThat(decisionPath.probe().outcomes()).isEmpty();
  }

  // ---------------------------------------------------------------- fixtures

  private static EvaluationContext context(CoverageProbe probe, String tier) {
    return EvaluationContext.of(
            new RecordV(
                Map.of(
                    "application", new RecordV(Map.of("tier", new Value.TextV(tier))),
                    "member", new RecordV(Map.of("tier", new Value.TextV(tier))))),
            Map.of(),
            Map.of(),
            AS_OF)
        .measuring(probe);
  }

  private static TableRow row(String when, OutcomeStatus status, String rate) {
    return new TableRow(
        List.of(when),
        new RowResult(
            Optional.ofNullable(status),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Map.of("rate", rate)),
        Optional.empty());
  }

  private static DecisionTableDef table(HitPolicy.Kind kind, List<TableRow> rows) {
    // Labels are assigned here so the uncovered-row listing has something human to show; the IR
    // allows them to be absent, in which case the index alone identifies the row.
    List<TableRow> labelled =
        java.util.stream.IntStream.range(0, rows.size())
            .mapToObj(
                index ->
                    new TableRow(
                        rows.get(index).when(),
                        rows.get(index).then(),
                        Optional.of("row-" + index)))
            .toList();
    return new DecisionTableDef(
        "PRICING",
        "Pricing",
        Scope.APPLICATION,
        Optional.empty(),
        new HitPolicy(kind, Optional.empty(), Optional.empty()),
        List.of(new TableInput("tier", "application.tier")),
        List.of(new TableOutput("rate", "DECIMAL")),
        labelled,
        OnMissing.DEFAULT,
        Optional.empty());
  }

  private static PolicyIr policy(
      List<RuleDef> rules, List<DecisionTableDef> tables, List<ScorecardDef> scorecards) {
    return new PolicyIr(
        1,
        List.of(),
        rules,
        tables,
        scorecards,
        new FlowDef(List.of(new StageDef("ONLY", false, List.of()))),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        PolicyIr.DEFAULT_CUSTOMER_REASON_LIMIT);
  }
}
