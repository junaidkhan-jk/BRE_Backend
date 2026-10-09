package dev.bre.engine.check;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bre.engine.ir.Ir;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The §4.13 static checks, with the gap analysis as the centrepiece. */
class StaticChecksTest {

  private static Ir.TableRow row(String cell, Ir.OutcomeStatus status) {
    return new Ir.TableRow(List.of(cell), Ir.RowResult.of(status, "POL_X"), Optional.empty());
  }

  private static Ir.TableRow row(List<String> cells, Ir.OutcomeStatus status) {
    return new Ir.TableRow(cells, Ir.RowResult.of(status, "POL_X"), Optional.empty());
  }

  private static Ir.DecisionTableDef table(
      Ir.HitPolicy hitPolicy, List<Ir.TableInput> inputs, List<Ir.TableRow> rows) {
    return new Ir.DecisionTableDef(
        "T1",
        "T1",
        Ir.Scope.APPLICATION,
        Optional.empty(),
        hitPolicy,
        inputs,
        List.of(),
        rows,
        Ir.OnMissing.DEFAULT,
        Optional.empty());
  }

  private static Ir.TableInput input(String name) {
    return new Ir.TableInput(name, "application." + name);
  }

  private static List<CheckFinding> check(Ir.DecisionTableDef table) {
    return TableAnalysis.analyse(table);
  }

  private static List<String> codes(List<CheckFinding> findings) {
    return findings.stream().map(CheckFinding::code).toList();
  }

  @Nested
  @DisplayName("CHK-002 gaps")
  class Gaps {

    /** The defect this check exists for: at runtime a gap is ERROR, which refers the applicant. */
    @Test
    void aNumericGapIsFoundWithAWitness() {
      Ir.DecisionTableDef gapped =
          table(
              Ir.HitPolicy.of(Ir.HitPolicy.Kind.FIRST),
              List.of(input("score")),
              List.of(row("< 600", Ir.OutcomeStatus.FAIL), row(">= 700", Ir.OutcomeStatus.PASS)));

      List<CheckFinding> findings = check(gapped);
      assertThat(codes(findings)).contains("CHK-002");
      CheckFinding gap =
          findings.stream().filter(f -> f.code().equals("CHK-002")).findFirst().orElseThrow();
      assertThat(gap.isError()).isTrue();
      assertThat(gap.witnesses())
          .as("600 to 700 is uncovered, and the witness has to say so concretely")
          .isNotEmpty();
      assertThat(gap.witnesses().toString()).contains("600");
    }

    @Test
    void aTotalTableHasNoGap() {
      Ir.DecisionTableDef total =
          table(
              Ir.HitPolicy.of(Ir.HitPolicy.Kind.FIRST),
              List.of(input("score")),
              List.of(row("< 600", Ir.OutcomeStatus.FAIL), row(">= 600", Ir.OutcomeStatus.PASS)));

      assertThat(codes(check(total))).doesNotContain("CHK-002");
    }

    /** The boundary case a credit table is most often wrong at. */
    @Test
    void theSingletonBoundaryIsNotLostBetweenLtAndGt() {
      Ir.DecisionTableDef missingTheBoundary =
          table(
              Ir.HitPolicy.of(Ir.HitPolicy.Kind.FIRST),
              List.of(input("score")),
              List.of(row("< 650", Ir.OutcomeStatus.FAIL), row("> 650", Ir.OutcomeStatus.PASS)));

      assertThat(codes(check(missingTheBoundary)))
          .as("exactly 650 matches neither row")
          .contains("CHK-002");
      assertThat(
              check(missingTheBoundary).stream()
                  .filter(f -> f.code().equals("CHK-002"))
                  .findFirst()
                  .orElseThrow()
                  .witnesses()
                  .toString())
          .contains("[650]");
    }

    @Test
    void aCategoricalGapIncludesTheUnlistedValues() {
      Ir.DecisionTableDef categorical =
          table(
              Ir.HitPolicy.of(Ir.HitPolicy.Kind.FIRST),
              List.of(input("villageRiskCategory")),
              List.of(
                  row("in [\"A\",\"B\"]", Ir.OutcomeStatus.PASS),
                  row("= \"C\"", Ir.OutcomeStatus.DEVIATION)));

      assertThat(codes(check(categorical)))
          .as("D, E and anything nobody wrote down are real inputs a borrower can arrive with")
          .contains("CHK-002");
      assertThat(check(categorical).toString()).contains("OTHER");
    }

    @Test
    void aDashRowClosesEveryPresentGap() {
      Ir.DecisionTableDef withCatchAll =
          table(
              Ir.HitPolicy.of(Ir.HitPolicy.Kind.FIRST),
              List.of(input("villageRiskCategory")),
              List.of(
                  row("in [\"A\",\"B\"]", Ir.OutcomeStatus.PASS),
                  row("-", Ir.OutcomeStatus.REFER)));

      assertThat(codes(check(withCatchAll))).doesNotContain("CHK-002");
    }

    /** A gap only over MISSING is not an error — it falls to onMissing (S5). */
    @Test
    void aMissingOnlyGapIsNotReported() {
      Ir.DecisionTableDef presentTotal =
          table(
              Ir.HitPolicy.of(Ir.HitPolicy.Kind.FIRST),
              List.of(input("score")),
              List.of(row("-", Ir.OutcomeStatus.PASS)));

      assertThat(codes(check(presentTotal)))
          .as("a dash covers every PRESENT value; the missing case is onMissing's business")
          .doesNotContain("CHK-002");
    }

    @Test
    void gapsAreFoundAcrossTwoColumns() {
      Ir.DecisionTableDef twoColumn =
          table(
              Ir.HitPolicy.of(Ir.HitPolicy.Kind.FIRST),
              List.of(input("score"), input("cycle")),
              List.of(
                  row(List.of(">= 650", ">= 2"), Ir.OutcomeStatus.PASS),
                  row(List.of("< 650", "-"), Ir.OutcomeStatus.FAIL)));

      assertThat(codes(check(twoColumn)))
          .as("score >= 650 with cycle < 2 matches nothing")
          .contains("CHK-002");
    }
  }

  @Nested
  @DisplayName("CHK-001 overlaps and CHK-003 unreachable rows")
  class OverlapsAndReachability {

    @Test
    void aUniqueTableWithOverlappingRowsIsAnError() {
      Ir.DecisionTableDef overlapping =
          table(
              Ir.HitPolicy.of(Ir.HitPolicy.Kind.UNIQUE),
              List.of(input("score")),
              List.of(row("<= 650", Ir.OutcomeStatus.FAIL), row(">= 650", Ir.OutcomeStatus.PASS)));

      assertThat(codes(check(overlapping))).contains("CHK-001");
      assertThat(
              check(overlapping).stream()
                  .filter(f -> f.code().equals("CHK-001"))
                  .findFirst()
                  .orElseThrow()
                  .witnesses()
                  .toString())
          .as("650 matches both")
          .contains("[650]");
    }

    @Test
    void overlapIsAllowedUnderPriorityAndCollect() {
      for (Ir.HitPolicy policy :
          List.of(
              Ir.HitPolicy.of(Ir.HitPolicy.Kind.PRIORITY),
              Ir.HitPolicy.collect(Ir.HitPolicy.CollectOp.SUM, "total"))) {
        Ir.DecisionTableDef overlapping =
            table(
                policy,
                List.of(input("score")),
                List.of(
                    row("<= 650", Ir.OutcomeStatus.FAIL), row(">= 650", Ir.OutcomeStatus.PASS)));
        assertThat(codes(check(overlapping))).doesNotContain("CHK-001");
      }
    }

    @Test
    void aRowEarlierRowsAlreadyCoverIsUnreachable() {
      Ir.DecisionTableDef shadowed =
          table(
              Ir.HitPolicy.of(Ir.HitPolicy.Kind.FIRST),
              List.of(input("score")),
              List.of(row("-", Ir.OutcomeStatus.PASS), row(">= 650", Ir.OutcomeStatus.FAIL)));

      assertThat(codes(check(shadowed))).contains("CHK-003");
    }

    @Test
    void aReachableRowIsNotReported() {
      Ir.DecisionTableDef fine =
          table(
              Ir.HitPolicy.of(Ir.HitPolicy.Kind.FIRST),
              List.of(input("score")),
              List.of(row("< 650", Ir.OutcomeStatus.FAIL), row(">= 650", Ir.OutcomeStatus.PASS)));

      assertThat(codes(check(fine))).doesNotContain("CHK-003");
    }
  }

  @Nested
  @DisplayName("CHK-014 opaque columns")
  class Opaque {

    @Test
    void aColumnReferencingAVariableIsReportedAndSkipped() {
      Ir.DecisionTableDef opaque =
          table(
              Ir.HitPolicy.of(Ir.HitPolicy.Kind.FIRST),
              List.of(input("score")),
              List.of(row(">= someVariable", Ir.OutcomeStatus.PASS)));

      List<CheckFinding> findings = check(opaque);
      assertThat(codes(findings)).contains("CHK-014");
      assertThat(codes(findings))
          .as("a gap cannot be claimed over a column whose bounds are unknown")
          .doesNotContain("CHK-002");
    }
  }

  @Nested
  @DisplayName("CHK-012 scorecard bins")
  class Scorecards {

    private Ir.ScorecardDef scorecard(List<Ir.Bin> bins) {
      return new Ir.ScorecardDef(
          "S1",
          "S1",
          Ir.Scope.APPLICATION,
          300,
          List.of(new Ir.Characteristic("cycle", "group.cycleNumber", Optional.empty(), bins)),
          3,
          Ir.Scaling.IDENTITY,
          Ir.OnMissing.DEFAULT);
    }

    @Test
    void overlappingBinsAreAnError() {
      List<CheckFinding> findings =
          TableAnalysis.analyse(
              scorecard(
                  List.of(
                      new Ir.Bin("<= 3", 20, Optional.empty()),
                      new Ir.Bin(">= 3", 45, Optional.empty()))));

      assertThat(codes(findings)).contains("CHK-012");
      assertThat(findings.toString()).contains("depend on bin order");
    }

    @Test
    void binsThatLeaveAGapAreAnError() {
      List<CheckFinding> findings =
          TableAnalysis.analyse(
              scorecard(
                  List.of(
                      new Ir.Bin("= 1", 20, Optional.empty()),
                      new Ir.Bin(">= 4", 70, Optional.empty()))));

      assertThat(codes(findings)).contains("CHK-012");
      assertThat(findings.toString()).contains("silently contribute nothing");
    }

    @Test
    void totalDisjointBinsPass() {
      List<CheckFinding> findings =
          TableAnalysis.analyse(
              scorecard(
                  List.of(
                      new Ir.Bin("< 2", 20, Optional.empty()),
                      new Ir.Bin(">= 2", 45, Optional.empty()))));

      assertThat(codes(findings)).doesNotContain("CHK-012");
    }
  }

  @Nested
  @DisplayName("catalogue checks over a policy")
  class Catalogue {

    private Ir.PolicyIr policy(
        List<Ir.RuleDef> rules, List<Ir.VariableDef> variables, List<Ir.StepDef> steps) {
      return new Ir.PolicyIr(
          1,
          variables,
          rules,
          List.of(),
          List.of(),
          new Ir.FlowDef(List.of(new Ir.StageDef("S", false, steps))),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Ir.PolicyIr.DEFAULT_CUSTOMER_REASON_LIMIT);
    }

    private Ir.RuleDef rule(String code, Ir.Outcome onFail) {
      return new Ir.RuleDef(
          code,
          code,
          Ir.Scope.APPLICATION,
          Optional.empty(),
          "application.a > 0",
          onFail,
          Ir.OnMissing.DEFAULT,
          Map.of(),
          Optional.empty());
    }

    @Test
    void aComponentNotInTheFlowIsReported() {
      List<CheckFinding> findings =
          StaticChecks.run(
              policy(
                  List.of(
                      rule("R1", Ir.Outcome.fail("POL_A")), rule("R2", Ir.Outcome.fail("POL_B"))),
                  List.of(),
                  List.of(Ir.StepDef.of("R1", Ir.AggregateSpec.of(Ir.AggregateSpec.Kind.WORST)))));

      assertThat(codes(findings)).contains("CHK-005");
      assertThat(findings.toString()).contains("Dead policy is worse than absent policy");
    }

    @Test
    void anUnusedVariableIsReported() {
      List<CheckFinding> findings =
          StaticChecks.run(
              policy(
                  List.of(rule("R1", Ir.Outcome.fail("POL_A"))),
                  List.of(
                      new Ir.VariableDef(
                          "spare", "Decimal", Ir.Scope.APPLICATION, "application.b")),
                  List.of(Ir.StepDef.of("R1", Ir.AggregateSpec.of(Ir.AggregateSpec.Kind.WORST)))));

      assertThat(codes(findings)).contains("CHK-004");
    }

    /** A deviation with no measure refers forever, however many people approve it (§4.9). */
    @Test
    void aDeviationWithoutAMeasureIsReported() {
      Ir.Outcome unmeasurable =
          new Ir.Outcome(
              Ir.OutcomeStatus.DEVIATION,
              Optional.of("AFF_X"),
              Optional.of("X"),
              Optional.empty(),
              Optional.empty());

      List<CheckFinding> findings =
          StaticChecks.run(
              policy(
                  List.of(rule("R1", unmeasurable)),
                  List.of(),
                  List.of(Ir.StepDef.of("R1", Ir.AggregateSpec.of(Ir.AggregateSpec.Kind.WORST)))));

      assertThat(codes(findings)).contains("CHK-015");
      assertThat(findings.toString()).contains("refer every time");
    }

    @Test
    void aPolicyMintingAReservedReasonCodeIsAnError() {
      for (String reserved : List.of("SYS_OVERRIDE", "DATA_MISSING_BUREAU", "DEV_NO_AUTHORITY")) {
        List<CheckFinding> findings =
            StaticChecks.run(
                policy(
                    List.of(rule("R1", Ir.Outcome.fail(reserved))),
                    List.of(),
                    List.of(
                        Ir.StepDef.of("R1", Ir.AggregateSpec.of(Ir.AggregateSpec.Kind.WORST)))));

        assertThat(codes(findings)).as("%s", reserved).contains("CHK-016");
        assertThat(
                findings.stream()
                    .filter(f -> f.code().equals("CHK-016"))
                    .findFirst()
                    .orElseThrow()
                    .isError())
            .isTrue();
      }
    }

    @Test
    void aVariableShadowingAFactRootIsReported() {
      List<CheckFinding> findings =
          StaticChecks.run(
              policy(
                  List.of(rule("R1", Ir.Outcome.fail("POL_A"))),
                  List.of(new Ir.VariableDef("application", "Decimal", Ir.Scope.APPLICATION, "1")),
                  List.of(Ir.StepDef.of("R1", Ir.AggregateSpec.of(Ir.AggregateSpec.Kind.WORST)))));

      assertThat(codes(findings)).contains("CHK-018");
    }

    @Test
    void aCleanPolicyHasNoErrors() {
      List<CheckFinding> findings =
          StaticChecks.run(
              policy(
                  List.of(rule("R1", Ir.Outcome.fail("POL_A"))),
                  List.of(),
                  List.of(Ir.StepDef.of("R1", Ir.AggregateSpec.of(Ir.AggregateSpec.Kind.WORST)))));

      assertThat(findings.stream().filter(CheckFinding::isError).toList()).isEmpty();
    }

    /** What is NOT checked has to be visible, or "no findings" reads as "clean". */
    @Test
    void theUnimplementedChecksAreNamedWithTheirDependency() {
      Map<String, String> pending = StaticChecks.notYetImplemented();

      assertThat(pending).containsKeys("CHK-006", "CHK-007", "CHK-011", "CHK-020", "CHK-021");
      assertThat(pending.values()).allSatisfy(reason -> assertThat(reason).isNotBlank());
    }
  }
}
