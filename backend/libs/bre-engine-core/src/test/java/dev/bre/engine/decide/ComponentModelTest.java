package dev.bre.engine.decide;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bre.engine.bel.parse.UnaryTestParser;
import dev.bre.engine.eval.EvaluationContext;
import dev.bre.engine.eval.UnaryTests;
import dev.bre.engine.eval.Value;
import dev.bre.engine.eval.Value.DecimalV;
import dev.bre.engine.eval.Value.IntV;
import dev.bre.engine.eval.Value.ListV;
import dev.bre.engine.eval.Value.Missing;
import dev.bre.engine.eval.Value.RecordV;
import dev.bre.engine.eval.Value.TextV;
import dev.bre.engine.ir.Ir;
import dev.bre.engine.ir.Ir.AggregateSpec;
import dev.bre.engine.ir.Ir.DecisionTableDef;
import dev.bre.engine.ir.Ir.HitPolicy;
import dev.bre.engine.ir.Ir.MissingPolicy;
import dev.bre.engine.ir.Ir.OnMissing;
import dev.bre.engine.ir.Ir.OutcomeStatus;
import dev.bre.engine.ir.Ir.RuntimeStatus;
import dev.bre.engine.ir.Ir.Scope;
import dev.bre.engine.ir.Ir.TableRow;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The component model: unary tests, decision tables with each hit policy, scorecards, aggregation
 * and flow (LLD §3.3, §4.6–§4.8).
 *
 * <p>These assert the same semantics the policy conformance vectors do, but directly against the IR
 * rather than through the vector loader — so a failure points at the behaviour rather than at the
 * plumbing, and the semantics are pinned before the vectors are wired up.
 */
class ComponentModelTest {

  private static EvaluationContext context(Map<String, Value> facts) {
    return EvaluationContext.of(
        new RecordV(facts), Map.of(), Map.of(), LocalDate.parse("2026-10-01"));
  }

  private static EvaluationContext empty() {
    return context(Map.of());
  }

  @Nested
  @DisplayName("§3.3 unary tests — the missing-input column")
  class UnaryTestSemantics {

    private boolean matches(String cell, Value input) {
      return UnaryTests.matches(UnaryTestParser.parse(cell), input, empty());
    }

    @Test
    @DisplayName("'-' matches any present value but NEVER a missing one (S5-01)")
    void dashDoesNotMatchMissing() {
      assertThat(matches("-", new IntV(5))).isTrue();
      assertThat(matches("-", new TextV("anything"))).isTrue();
      assertThat(matches("-", Missing.INSTANCE))
          .as("a dash means 'any value'; a missing input has no value, so the row must not match")
          .isFalse();
    }

    @Test
    @DisplayName("'missing' matches only a missing input (S5-02)")
    void missingMatchesOnlyMissing() {
      assertThat(matches("missing", Missing.INSTANCE)).isTrue();
      assertThat(matches("missing", new IntV(5))).isFalse();
    }

    @Test
    @DisplayName("'-, missing' matches everything (S5-03)")
    void dashAndMissingMatchesEverything() {
      assertThat(matches("-, missing", Missing.INSTANCE)).isTrue();
      assertThat(matches("-, missing", new TextV("Z"))).isTrue();
    }

    @Test
    @DisplayName("a comparison cell, and a bare value meaning equality")
    void comparisonsAndBareValues() {
      assertThat(matches(">= 18", new IntV(18))).isTrue();
      assertThat(matches(">= 18", new IntV(17))).isFalse();
      assertThat(matches("\"JLG\"", new TextV("JLG"))).isTrue();
      assertThat(matches("\"JLG\"", new TextV("SHG"))).isFalse();
      assertThat(matches("= 1.0", new DecimalV(new java.math.BigDecimal("1.00"))))
          .as("scale is ignored, exactly as in an expression")
          .isTrue();
    }

    @Test
    @DisplayName("ranges take their inclusivity from the brackets")
    void ranges() {
      assertThat(matches("[1..10]", new IntV(10))).isTrue();
      assertThat(matches("[1..10)", new IntV(10))).isFalse();
      assertThat(matches("[1..10]", new IntV(0))).isFalse();
    }

    @Test
    @DisplayName("membership, and its negation")
    void membership() {
      assertThat(matches("in [\"A\", \"B\"]", new TextV("A"))).isTrue();
      assertThat(matches("in [\"A\", \"B\"]", new TextV("C"))).isFalse();
      assertThat(matches("not in [\"A\", \"B\"]", new TextV("C"))).isTrue();
    }
  }

  @Nested
  @DisplayName("§4.6 decision tables and hit policies")
  class Tables {

    private DecisionTableDef table(HitPolicy policy, List<TableRow> rows, String inputExpr) {
      return new DecisionTableDef(
          "TBL",
          "a table",
          Scope.APPLICATION,
          Optional.empty(),
          policy,
          List.of(new Ir.TableInput("value", inputExpr)),
          List.of(),
          rows,
          OnMissing.DEFAULT,
          Optional.empty());
    }

    private static TableRow row(String cell, OutcomeStatus status, String reason) {
      return new TableRow(List.of(cell), Ir.RowResult.of(status, reason), Optional.empty());
    }

    @Test
    @DisplayName("S5-01: no row matches a missing input, so onMissing applies")
    void noMatchOnMissingInputGoesToOnMissing() {
      DecisionTableDef table =
          table(
              HitPolicy.of(HitPolicy.Kind.FIRST),
              List.of(
                  row("in [\"A\", \"B\"]", OutcomeStatus.PASS, null),
                  row("-", OutcomeStatus.REFER, "POL_FALLBACK")),
              "application.vrm");

      ComponentResult result = ComponentEvaluator.evaluateTable(table, empty());
      assertThat(result.status())
          .as("the dash row must not absorb a missing input; that is the fail-closed choice")
          .isEqualTo(RuntimeStatus.DATA_MISSING);
    }

    @Test
    @DisplayName("S5-02: an explicit 'missing' row matches, with its own reason")
    void explicitMissingRowMatches() {
      DecisionTableDef table =
          table(
              HitPolicy.of(HitPolicy.Kind.FIRST),
              List.of(
                  row("in [\"A\", \"B\"]", OutcomeStatus.PASS, null),
                  row("missing", OutcomeStatus.REFER, "DATA_VRM_MISSING"),
                  row("-", OutcomeStatus.REFER, "POL_FALLBACK")),
              "application.vrm");

      ComponentResult result = ComponentEvaluator.evaluateTable(table, empty());
      assertThat(result.status()).isEqualTo(RuntimeStatus.REFER);
      assertThat(result.reasonCode()).contains("DATA_VRM_MISSING");
    }

    @Test
    @DisplayName("S5-04: a gap with every input PRESENT is EVAL_TABLE_NO_MATCH")
    void gapWithPresentInputIsAnError() {
      DecisionTableDef table =
          table(
              HitPolicy.of(HitPolicy.Kind.FIRST),
              List.of(
                  row("in [\"A\", \"B\"]", OutcomeStatus.PASS, null),
                  row("= \"C\"", OutcomeStatus.DEVIATION, "POL_VRM_C")),
              "application.vrm");

      ComponentResult result =
          ComponentEvaluator.evaluateTable(
              table, context(Map.of("application", new RecordV(Map.of("vrm", new TextV("F"))))));
      assertThat(result.status()).isEqualTo(RuntimeStatus.ERROR);
      assertThat(result.error()).contains("EVAL_TABLE_NO_MATCH");
    }

    @Test
    @DisplayName("T-01: two rows under UNIQUE is EVAL_TABLE_UNIQUE_VIOLATION")
    void uniqueViolation() {
      DecisionTableDef table =
          table(
              HitPolicy.of(HitPolicy.Kind.UNIQUE),
              List.of(row("-", OutcomeStatus.PASS, null), row("= \"A\"", OutcomeStatus.PASS, null)),
              "application.vrm");

      ComponentResult result =
          ComponentEvaluator.evaluateTable(
              table, context(Map.of("application", new RecordV(Map.of("vrm", new TextV("A"))))));
      assertThat(result.error()).contains("EVAL_TABLE_UNIQUE_VIOLATION");
    }

    @Test
    @DisplayName("T-02: PRIORITY takes the most severe matching row")
    void priorityTakesTheWorstRow() {
      DecisionTableDef table =
          table(
              HitPolicy.of(HitPolicy.Kind.PRIORITY),
              List.of(
                  row("-", OutcomeStatus.PASS, null),
                  row("in [\"A\", \"B\"]", OutcomeStatus.DEVIATION, "POL_DEV"),
                  row("= \"A\"", OutcomeStatus.REFER, "POL_REFER")),
              "application.vrm");

      ComponentResult result =
          ComponentEvaluator.evaluateTable(
              table, context(Map.of("application", new RecordV(Map.of("vrm", new TextV("A"))))));
      assertThat(result.status()).isEqualTo(RuntimeStatus.REFER);
    }

    @Test
    @DisplayName("T-03: COLLECT(SUM) adds every matching row's contribution, and 0 when none match")
    void collectSum() {
      TableRow first =
          new TableRow(
              List.of(">= 1"),
              new Ir.RowResult(
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Map.of("points", "10")),
              Optional.empty());
      TableRow second =
          new TableRow(
              List.of(">= 2"),
              new Ir.RowResult(
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Map.of("points", "20")),
              Optional.empty());

      DecisionTableDef table =
          table(
              HitPolicy.collect(HitPolicy.CollectOp.SUM, "points"),
              List.of(first, second),
              "application.cycleNumber");

      ComponentResult both =
          ComponentEvaluator.evaluateTable(
              table,
              context(Map.of("application", new RecordV(Map.of("cycleNumber", new IntV(3))))));
      assertThat(both.status()).isEqualTo(RuntimeStatus.PASS);
      assertThat(both.outputs().get("points")).isEqualTo(new IntV(30));

      ComponentResult none =
          ComponentEvaluator.evaluateTable(
              table,
              context(Map.of("application", new RecordV(Map.of("cycleNumber", new IntV(0))))));
      assertThat(none.status())
          .as("a COLLECT table with no matching row is not a gap; it collects nothing")
          .isEqualTo(RuntimeStatus.PASS);
      assertThat(none.outputs().get("points")).isEqualTo(new IntV(0));
    }
  }

  @Nested
  @DisplayName("§4.6 rules and onMissing")
  class Rules {

    private Ir.RuleDef rule(String check, OnMissing onMissing, String when) {
      return new Ir.RuleDef(
          "R",
          "a rule",
          Scope.APPLICATION,
          Optional.ofNullable(when),
          check,
          Ir.Outcome.fail("POL_FAILED"),
          onMissing,
          Map.of(),
          Optional.empty());
    }

    @Test
    @DisplayName("S1-01: an unknown 'when' goes to onMissing, never to NOT_APPLICABLE")
    void unknownGuardIsNotApplicableNever() {
      ComponentResult result =
          ComponentEvaluator.evaluateRule(rule("true", OnMissing.DEFAULT, "x > 0"), empty());
      assertThat(result.status())
          .as("'we do not know whether this applies' is not 'it does not apply'")
          .isEqualTo(RuntimeStatus.DATA_MISSING);
    }

    @Test
    @DisplayName("S1-02: a FALSE 'when' is NOT_APPLICABLE")
    void falseGuardIsNotApplicable() {
      ComponentResult result =
          ComponentEvaluator.evaluateRule(
              rule("true", OnMissing.DEFAULT, "x > 0"), context(Map.of("x", new IntV(-1))));
      assertThat(result.status()).isEqualTo(RuntimeStatus.NOT_APPLICABLE);
    }

    @Test
    @DisplayName("M-01: the three onMissing policies")
    void onMissingPolicies() {
      assertThat(
              ComponentEvaluator.evaluateRule(
                      rule("x", OnMissing.of(MissingPolicy.FAIL, "KYC_MISSING"), null), empty())
                  .status())
          .isEqualTo(RuntimeStatus.FAIL);
      assertThat(
              ComponentEvaluator.evaluateRule(
                      rule("x", OnMissing.of(MissingPolicy.NOT_APPLICABLE, null), null), empty())
                  .status())
          .isEqualTo(RuntimeStatus.NOT_APPLICABLE);
      assertThat(
              ComponentEvaluator.evaluateRule(
                      rule(
                          "x",
                          new OnMissing(
                              MissingPolicy.USE_DEFAULT, Optional.of(true), Optional.empty()),
                          null),
                      empty())
                  .status())
          .as("USE_DEFAULT(true) replaces the unknown Boolean, so the check reads TRUE")
          .isEqualTo(RuntimeStatus.PASS);
    }

    @Test
    @DisplayName("a FAIL carries the author's reason code")
    void failCarriesReason() {
      ComponentResult result =
          ComponentEvaluator.evaluateRule(
              rule("x > 10", OnMissing.DEFAULT, null), context(Map.of("x", new IntV(5))));
      assertThat(result.status()).isEqualTo(RuntimeStatus.FAIL);
      assertThat(result.reasonCode()).contains("POL_FAILED");
    }
  }

  @Nested
  @DisplayName("§4.6 scorecards")
  class Scorecards {

    @Test
    @DisplayName("S11-01: adverse reasons are the largest shortfalls, and a maxed bin is excluded")
    void adverseReasonsAreShortfalls() {
      Ir.ScorecardDef scorecard =
          new Ir.ScorecardDef(
              "SC",
              "a scorecard",
              Scope.APPLICATION,
              0,
              List.of(
                  // A: max 70, got 45 -> shortfall 25
                  characteristic("a", 70, 45, "RA"),
                  // B: max 40, got 15 -> shortfall 25 (tie with A; declaration order wins)
                  characteristic("b", 40, 15, "RB"),
                  // C: max 30, got 30 -> shortfall 0, so never an adverse reason
                  characteristic("c", 30, 30, "RC")),
              2,
              Ir.Scaling.IDENTITY,
              OnMissing.DEFAULT);

      ComponentResult result =
          ComponentEvaluator.evaluateScorecard(
              scorecard, context(Map.of("a", new IntV(1), "b", new IntV(1), "c", new IntV(1))));

      assertThat(result.status()).isEqualTo(RuntimeStatus.PASS);
      assertThat(result.outputs().get("score")).isEqualTo(DecimalV.of("90"));
      assertThat(result.adverseReasons())
          .as("C scored its maximum, so it is not a reason however few points it was worth")
          .containsExactly("RA", "RB");
    }

    /** A characteristic whose value is always 1, with a hit bin and a higher-scoring sibling. */
    private static Ir.Characteristic characteristic(
        String name, int maxPoints, int hitPoints, String reason) {
      return new Ir.Characteristic(
          name,
          name,
          Optional.of(reason),
          List.of(
              new Ir.Bin("= 1", hitPoints, Optional.of(reason)),
              new Ir.Bin("= 999", maxPoints, Optional.empty())));
    }

    @Test
    @DisplayName("S11-02: a present value with no matching bin is EVAL_SCORECARD_NO_BIN")
    void noBinIsAnError() {
      Ir.ScorecardDef scorecard =
          new Ir.ScorecardDef(
              "SC",
              "a scorecard",
              Scope.APPLICATION,
              0,
              List.of(
                  new Ir.Characteristic(
                      "a",
                      "a",
                      Optional.empty(),
                      List.of(new Ir.Bin("= 1", 10, Optional.empty())))),
              0,
              Ir.Scaling.IDENTITY,
              OnMissing.DEFAULT);

      ComponentResult result =
          ComponentEvaluator.evaluateScorecard(scorecard, context(Map.of("a", new IntV(7))));
      assertThat(result.error())
          .as("an unbinned value is a policy gap, not a zero")
          .contains("EVAL_SCORECARD_NO_BIN");
    }
  }

  @Nested
  @DisplayName("§4.7 aggregation")
  class Aggregating {

    private RuntimeStatus aggregate(AggregateSpec spec, RuntimeStatus... statuses) {
      List<ComponentResult> results =
          java.util.Arrays.stream(statuses).map(status -> ComponentResult.of("C", status)).toList();
      return Aggregation.aggregate(spec, results, empty());
    }

    @Test
    @DisplayName("S6-01: WORST, including DATA_MISSING over DEVIATION and FAIL over ERROR")
    void worst() {
      assertThat(
              aggregate(
                  AggregateSpec.of(AggregateSpec.Kind.WORST),
                  RuntimeStatus.PASS,
                  RuntimeStatus.DATA_MISSING,
                  RuntimeStatus.DEVIATION))
          .isEqualTo(RuntimeStatus.DATA_MISSING);
      assertThat(
              aggregate(
                  AggregateSpec.of(AggregateSpec.Kind.WORST),
                  RuntimeStatus.ERROR,
                  RuntimeStatus.FAIL))
          .isEqualTo(RuntimeStatus.FAIL);
      assertThat(
              aggregate(
                  AggregateSpec.of(AggregateSpec.Kind.WORST),
                  RuntimeStatus.NOT_APPLICABLE,
                  RuntimeStatus.NOT_APPLICABLE))
          .isEqualTo(RuntimeStatus.NOT_APPLICABLE);
    }

    @Test
    @DisplayName("A-01: ALL_MUST_PASS drops NOT_APPLICABLE, fails on a deviation, keeps a gap")
    void allMustPass() {
      AggregateSpec spec = AggregateSpec.of(AggregateSpec.Kind.ALL_MUST_PASS);
      assertThat(
              aggregate(spec, RuntimeStatus.PASS, RuntimeStatus.NOT_APPLICABLE, RuntimeStatus.PASS))
          .isEqualTo(RuntimeStatus.PASS);
      assertThat(aggregate(spec, RuntimeStatus.PASS, RuntimeStatus.DEVIATION))
          .as("the rule is strict, so nobody may approve around it")
          .isEqualTo(RuntimeStatus.FAIL);
      assertThat(aggregate(spec, RuntimeStatus.PASS, RuntimeStatus.DATA_MISSING))
          .as("a gap must stay a REFER, not become a DECLINE")
          .isEqualTo(RuntimeStatus.DATA_MISSING);
    }

    @Test
    @DisplayName("S7: THRESHOLD on PERCENT, with NOT_APPLICABLE out of the denominator")
    void threshold() {
      AggregateSpec spec =
          new AggregateSpec(
              AggregateSpec.Kind.THRESHOLD,
              Optional.of(AggregateSpec.Metric.PERCENT),
              Optional.of(">="),
              Optional.of("20"),
              List.of(RuntimeStatus.DEVIATION),
              Optional.of(OutcomeStatus.REFER));

      // S7-01: 2 of 10 deviate -> 20% -> REFER.
      assertThat(
              aggregate(
                  spec,
                  RuntimeStatus.DEVIATION,
                  RuntimeStatus.DEVIATION,
                  RuntimeStatus.PASS,
                  RuntimeStatus.PASS,
                  RuntimeStatus.PASS,
                  RuntimeStatus.PASS,
                  RuntimeStatus.PASS,
                  RuntimeStatus.PASS,
                  RuntimeStatus.PASS,
                  RuntimeStatus.PASS))
          .isEqualTo(RuntimeStatus.REFER);

      // S7-02: 1 of 10 -> 10% -> below the threshold, so WORST.
      assertThat(
              aggregate(
                  spec,
                  RuntimeStatus.DEVIATION,
                  RuntimeStatus.PASS,
                  RuntimeStatus.PASS,
                  RuntimeStatus.PASS,
                  RuntimeStatus.PASS,
                  RuntimeStatus.PASS,
                  RuntimeStatus.PASS,
                  RuntimeStatus.PASS,
                  RuntimeStatus.PASS,
                  RuntimeStatus.PASS))
          .isEqualTo(RuntimeStatus.DEVIATION);

      // S7-04: 2 deviations, 2 not applicable, 6 pass -> 2/8 = 25% -> REFER.
      assertThat(
              aggregate(
                  spec,
                  RuntimeStatus.DEVIATION,
                  RuntimeStatus.DEVIATION,
                  RuntimeStatus.NOT_APPLICABLE,
                  RuntimeStatus.NOT_APPLICABLE,
                  RuntimeStatus.PASS,
                  RuntimeStatus.PASS,
                  RuntimeStatus.PASS,
                  RuntimeStatus.PASS,
                  RuntimeStatus.PASS,
                  RuntimeStatus.PASS))
          .isEqualTo(RuntimeStatus.REFER);

      // S7-05: a FAIL present -> never weaker than WORST, so FAIL.
      assertThat(
              aggregate(
                  spec,
                  RuntimeStatus.FAIL,
                  RuntimeStatus.DEVIATION,
                  RuntimeStatus.DEVIATION,
                  RuntimeStatus.DEVIATION,
                  RuntimeStatus.PASS,
                  RuntimeStatus.PASS,
                  RuntimeStatus.PASS,
                  RuntimeStatus.PASS,
                  RuntimeStatus.PASS,
                  RuntimeStatus.PASS))
          .isEqualTo(RuntimeStatus.FAIL);
    }
  }

  @Nested
  @DisplayName("§4.8 flow, knockout and precedence")
  class Flow {

    private Ir.PolicyIr policy(boolean knockout, Ir.RuleDef... rules) {
      List<Ir.StepDef> steps =
          java.util.Arrays.stream(rules)
              .map(rule -> Ir.StepDef.of(rule.code(), AggregateSpec.of(AggregateSpec.Kind.NONE)))
              .toList();
      return new Ir.PolicyIr(
          1,
          List.of(),
          List.of(rules),
          List.of(),
          List.of(),
          new Ir.FlowDef(List.of(new Ir.StageDef("S1", knockout, steps))),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Ir.PolicyIr.DEFAULT_CUSTOMER_REASON_LIMIT);
    }

    private static Ir.RuleDef rule(String code, String check) {
      return new Ir.RuleDef(
          code,
          code,
          Scope.APPLICATION,
          Optional.empty(),
          check,
          Ir.Outcome.fail("POL_" + code),
          OnMissing.DEFAULT,
          Map.of(),
          Optional.empty());
    }

    @Test
    @DisplayName("P-01: a FAIL declines even alongside an ERROR")
    void failBeatsError() {
      FlowEvaluator.DecisionResult result =
          FlowEvaluator.evaluate(
              policy(false, rule("A", "x > 0"), rule("B", "1 / zero > 0")),
              context(Map.of("x", new IntV(-1), "zero", new IntV(0))));
      assertThat(result.decision()).isEqualTo(Outcomes.Decision.DECLINE);
    }

    @Test
    @DisplayName("P-03: a gap refers rather than approving")
    void gapRefers() {
      FlowEvaluator.DecisionResult result =
          FlowEvaluator.evaluate(
              policy(false, rule("A", "x > 0"), rule("B", "absent > 0")),
              context(Map.of("x", new IntV(1))));
      assertThat(result.decision()).isEqualTo(Outcomes.Decision.REFER);
      assertThat(result.stpEligible()).isFalse();
    }

    @Test
    @DisplayName("all passing approves, and is straight-through eligible")
    void allPassApproves() {
      FlowEvaluator.DecisionResult result =
          FlowEvaluator.evaluate(
              policy(false, rule("A", "x > 0")), context(Map.of("x", new IntV(1))));
      assertThat(result.decision()).isEqualTo(Outcomes.Decision.APPROVE);
      assertThat(result.stpEligible()).isTrue();
    }

    @Test
    @DisplayName("S12-01: a knockout FAIL skips later stages with SYS_SKIPPED_KNOCKOUT")
    void knockoutSkipsLaterStages() {
      Ir.RuleDef failing = rule("KO", "x > 0");
      Ir.RuleDef later = rule("LATER", "true");
      Ir.PolicyIr policy =
          new Ir.PolicyIr(
              1,
              List.of(),
              List.of(failing, later),
              List.of(),
              List.of(),
              new Ir.FlowDef(
                  List.of(
                      new Ir.StageDef(
                          "KNOCKOUT",
                          true,
                          List.of(Ir.StepDef.of("KO", AggregateSpec.of(AggregateSpec.Kind.NONE)))),
                      new Ir.StageDef(
                          "REST",
                          false,
                          List.of(
                              Ir.StepDef.of("LATER", AggregateSpec.of(AggregateSpec.Kind.NONE)))))),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Ir.PolicyIr.DEFAULT_CUSTOMER_REASON_LIMIT);

      FlowEvaluator.DecisionResult result =
          FlowEvaluator.evaluate(policy, context(Map.of("x", new IntV(-1))));

      assertThat(result.decision()).isEqualTo(Outcomes.Decision.DECLINE);
      FlowEvaluator.StepResult skipped = result.step("LATER").orElseThrow();
      assertThat(skipped.status()).isEqualTo(RuntimeStatus.NOT_APPLICABLE);
      assertThat(skipped.entities().get(0).reasonCode()).contains(FlowEvaluator.SKIPPED_KNOCKOUT);
    }

    @Test
    @DisplayName("§4.5: a MEMBER-scope step runs once per member, in memberPartyRefs order")
    void memberScopeIteratesEachMember() {
      Ir.RuleDef memberRule =
          new Ir.RuleDef(
              "MEM",
              "per member",
              Scope.MEMBER,
              Optional.empty(),
              "member.age >= 18",
              Ir.Outcome.fail("POL_UNDERAGE"),
              OnMissing.DEFAULT,
              Map.of(),
              Optional.empty());

      Ir.PolicyIr policy =
          new Ir.PolicyIr(
              1,
              List.of(),
              List.of(memberRule),
              List.of(),
              List.of(),
              new Ir.FlowDef(
                  List.of(
                      new Ir.StageDef(
                          "S1",
                          false,
                          List.of(
                              Ir.StepDef.of("MEM", AggregateSpec.of(AggregateSpec.Kind.WORST)))))),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Ir.PolicyIr.DEFAULT_CUSTOMER_REASON_LIMIT);

      Value parties =
          new ListV(
              List.of(
                  new RecordV(
                      Map.of(
                          "partyRef",
                          new TextV("P-2"),
                          "role",
                          new TextV("MEMBER"),
                          "age",
                          new IntV(17))),
                  new RecordV(
                      Map.of(
                          "partyRef",
                          new TextV("P-1"),
                          "role",
                          new TextV("MEMBER"),
                          "age",
                          new IntV(30)))));
      Value group =
          new RecordV(
              Map.of("memberPartyRefs", new ListV(List.of(new TextV("P-1"), new TextV("P-2")))));

      FlowEvaluator.DecisionResult result =
          FlowEvaluator.evaluate(policy, context(Map.of("parties", parties, "group", group)));

      FlowEvaluator.StepResult step = result.step("MEM").orElseThrow();
      assertThat(step.entities()).hasSize(2);
      assertThat(step.entities().get(0).status())
          .as("P-1 comes first because memberPartyRefs says so, not because parties[] does")
          .isEqualTo(RuntimeStatus.PASS);
      assertThat(step.entities().get(1).status()).isEqualTo(RuntimeStatus.FAIL);
      assertThat(step.status()).isEqualTo(RuntimeStatus.FAIL);
      assertThat(result.decision()).isEqualTo(Outcomes.Decision.DECLINE);
    }
  }
}
