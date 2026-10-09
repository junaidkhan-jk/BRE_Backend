package dev.bre.engine.decide;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bre.engine.eval.EvaluationContext;
import dev.bre.engine.eval.Value;
import dev.bre.engine.eval.Value.RecordV;
import dev.bre.engine.ir.Ir;
import dev.bre.engine.ir.Ir.AggregateSpec;
import dev.bre.engine.ir.Ir.FlowDef;
import dev.bre.engine.ir.Ir.MissingPolicy;
import dev.bre.engine.ir.Ir.OnMissing;
import dev.bre.engine.ir.Ir.Outcome;
import dev.bre.engine.ir.Ir.PolicyIr;
import dev.bre.engine.ir.Ir.RuleDef;
import dev.bre.engine.ir.Ir.Scope;
import dev.bre.engine.ir.Ir.StageDef;
import dev.bre.engine.ir.Ir.StepDef;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two things the flow has to get right about <em>which</em> data a step sees (§4.5, §4.10).
 *
 * <p>Both were missing and neither failed loudly. A component's named outputs were computed and
 * then dropped before the next step ran, so {@code CODE.output} was Missing; and HOUSEHOLD scope
 * fell through to a single application-scope entity, so {@code household.annualIncome} was never
 * bound. In each case the policy referred for missing data — indistinguishable, from outside, from
 * an integrator sending an incomplete document.
 */
class FlowScopeAndOutputsTest {

  private static final LocalDate AS_OF = LocalDate.parse("2026-10-01");

  private static RuleDef rule(String code, Scope scope, String check, Map<String, String> outputs) {
    return new RuleDef(
        code,
        code,
        scope,
        Optional.empty(),
        check,
        Outcome.fail("R_" + code),
        OnMissing.of(MissingPolicy.REFER, "DATA_MISSING"),
        outputs,
        Optional.empty());
  }

  private static PolicyIr policy(List<RuleDef> rules, List<StepDef> steps) {
    return new PolicyIr(
        1,
        List.of(),
        rules,
        List.of(),
        List.of(),
        new FlowDef(List.of(new StageDef("ONLY", false, steps))),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        PolicyIr.DEFAULT_CUSTOMER_REASON_LIMIT);
  }

  private static StepDef step(String ref) {
    return StepDef.of(ref, AggregateSpec.of(AggregateSpec.Kind.WORST));
  }

  private static FlowEvaluator.DecisionResult decide(PolicyIr policy, Map<String, Value> facts) {
    return FlowEvaluator.evaluate(
        policy, EvaluationContext.of(new RecordV(facts), Map.of(), Map.of(), AS_OF));
  }

  @Test
  @DisplayName("a later step reads an earlier component's named output")
  void outputsAreVisibleToLaterSteps() {
    PolicyIr p =
        policy(
            List.of(
                rule("FIRST", Scope.APPLICATION, "true", Map.of("limit", "100")),
                rule("SECOND", Scope.APPLICATION, "FIRST.limit = 100", Map.of())),
            List.of(step("FIRST"), step("SECOND")));

    assertThat(decide(p, Map.of()).step("SECOND").orElseThrow().status())
        .isEqualTo(Ir.RuntimeStatus.PASS);
  }

  @Test
  @DisplayName("a FORWARD reference reads as missing, so a mis-ordered flow fails closed")
  void forwardReferencesAreMissing() {
    // SECOND runs first and references FIRST, which has not run. Reading a value from a step that
    // has not happened would make the result depend on something other than the flow order.
    PolicyIr p =
        policy(
            List.of(
                rule("FIRST", Scope.APPLICATION, "true", Map.of("limit", "100")),
                rule("SECOND", Scope.APPLICATION, "FIRST.limit = 100", Map.of())),
            List.of(step("SECOND"), step("FIRST")));

    assertThat(decide(p, Map.of()).step("SECOND").orElseThrow().status())
        .as("§3.9 strict propagation: a missing input refers, it does not pass")
        .isEqualTo(Ir.RuntimeStatus.DATA_MISSING);
  }

  @Test
  @DisplayName("each member sees ITS OWN output, never another member's")
  void outputsAreKeyedByEntity() {
    // The defect this guards against is the worst kind: binding the first member's score for every
    // member decides one applicant's application on another applicant's credit data, and every
    // result still looks entirely plausible.
    PolicyIr p =
        policy(
            List.of(
                rule("DOUBLE", Scope.MEMBER, "true", Map.of("twice", "member.n * 2")),
                rule("CHECK", Scope.MEMBER, "DOUBLE.twice = member.n * 2", Map.of())),
            List.of(step("DOUBLE"), step("CHECK")));

    Map<String, Value> facts =
        Map.of(
            "group",
            new RecordV(
                Map.of(
                    "memberPartyRefs",
                    new Value.ListV(List.of(new Value.TextV("P-1"), new Value.TextV("P-2"))))),
            "parties",
            new Value.ListV(List.of(member("P-1", 10), member("P-2", 25))));

    FlowEvaluator.StepResult check = decide(p, facts).step("CHECK").orElseThrow();
    assertThat(check.entities()).hasSize(2);
    assertThat(check.status())
        .as("both members must see their own doubled value")
        .isEqualTo(Ir.RuntimeStatus.PASS);
  }

  @Test
  @DisplayName("HOUSEHOLD scope runs once per household, in declaration order")
  void householdScopeIteratesHouseholds() {
    PolicyIr p =
        policy(
            List.of(
                rule("INCOME_CAP", Scope.HOUSEHOLD, "household.annualIncome <= 300000", Map.of())),
            List.of(step("INCOME_CAP")));

    Map<String, Value> facts =
        Map.of(
            "households",
            new Value.ListV(
                List.of(
                    household("H-1", "180000"),
                    household("H-2", "420000"),
                    household("H-3", "200000"))));

    FlowEvaluator.StepResult result = decide(p, facts).step("INCOME_CAP").orElseThrow();
    assertThat(result.entities()).hasSize(3);
    assertThat(result.entities().stream().map(e -> e.entityRef().orElse("?")).toList())
        .containsExactly("H-1", "H-2", "H-3");
    assertThat(result.status())
        .as("WORST over three households, one of which is over the ceiling")
        .isEqualTo(Ir.RuntimeStatus.FAIL);
  }

  @Test
  @DisplayName("a directly bound household is one household already selected")
  void directlyBoundHouseholdIsOneEntity() {
    PolicyIr p =
        policy(
            List.of(
                rule("INCOME_CAP", Scope.HOUSEHOLD, "household.annualIncome <= 300000", Map.of())),
            List.of(step("INCOME_CAP")));

    FlowEvaluator.StepResult result =
        decide(p, Map.of("household", household("H-9", "180000"))).step("INCOME_CAP").orElseThrow();
    assertThat(result.entities()).hasSize(1);
    assertThat(result.status()).isEqualTo(Ir.RuntimeStatus.PASS);
  }

  private static Value member(String ref, long n) {
    return new RecordV(Map.of("partyRef", new Value.TextV(ref), "n", new Value.IntV(n)));
  }

  private static Value household(String ref, String income) {
    return new RecordV(
        Map.of("householdRef", new Value.TextV(ref), "annualIncome", Value.DecimalV.of(income)));
  }
}
