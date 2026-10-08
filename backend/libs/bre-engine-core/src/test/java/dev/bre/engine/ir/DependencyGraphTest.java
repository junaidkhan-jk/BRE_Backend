package dev.bre.engine.ir;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The dependency graph and topological order (LLD §4.2). */
class DependencyGraphTest {

  private static Ir.VariableDef variable(String name, String expr) {
    return new Ir.VariableDef(name, "Decimal", Ir.Scope.APPLICATION, expr);
  }

  private static Ir.RuleDef rule(String code, String check) {
    return new Ir.RuleDef(
        code,
        code,
        Ir.Scope.APPLICATION,
        Optional.empty(),
        check,
        Ir.Outcome.fail("POL_" + code),
        Ir.OnMissing.DEFAULT,
        Map.of(),
        Optional.empty());
  }

  private static Ir.PolicyIr policy(List<Ir.VariableDef> variables, List<Ir.RuleDef> rules) {
    return policy(variables, rules, Optional.empty());
  }

  private static Ir.PolicyIr policy(
      List<Ir.VariableDef> variables, List<Ir.RuleDef> rules, Optional<String> authorityAmount) {
    return new Ir.PolicyIr(
        1,
        variables,
        rules,
        List.of(),
        List.of(),
        new Ir.FlowDef(List.of()),
        Optional.empty(),
        authorityAmount,
        Optional.empty(),
        Ir.PolicyIr.DEFAULT_CUSTOMER_REASON_LIMIT);
  }

  @Test
  void anEdgeRunsFromADependencyToWhatUsesIt() {
    DependencyGraph graph =
        DependencyGraph.of(
            policy(
                List.of(
                    variable("income", "application.monthlyIncome"),
                    variable("emi", "application.existingEmi"),
                    variable("foir", "emi / income")),
                List.of()));

    assertThat(graph.dependenciesOf("foir")).containsExactlyInAnyOrder("emi", "income");
    assertThat(graph.dependenciesOf("income"))
        .as("a fact root is an input to the decision, not a node that has to be ordered")
        .isEmpty();
  }

  @Test
  void everythingComesAfterWhatItDependsOn() {
    DependencyGraph graph =
        DependencyGraph.of(
            policy(
                List.of(
                    variable("foir", "emi / income"),
                    variable("income", "application.monthlyIncome"),
                    variable("emi", "application.existingEmi")),
                List.of(rule("AFF_FOIR", "foir <= 0.45"))));

    List<String> order = graph.topologicalOrder();
    assertThat(graph.isAcyclic()).isTrue();
    assertThat(order).hasSize(4);
    assertThat(order.indexOf("income")).isLessThan(order.indexOf("foir"));
    assertThat(order.indexOf("emi")).isLessThan(order.indexOf("foir"));
    assertThat(order.indexOf("foir"))
        .as("the rule reads the variable, so it is ordered after it")
        .isLessThan(order.indexOf("AFF_FOIR"));
  }

  /**
   * The whole reason this class exists: a cyclic policy is detected without evaluating anything.
   *
   * <p>The lazy resolver also catches this, but only when a decision walks into it — on a real
   * applicant, in production. Here it is a property of the policy, available at authoring time,
   * which is what {@code BEL-015} is for.
   */
  @Test
  void aCycleIsFoundWithoutEvaluatingTheExpressions() {
    DependencyGraph graph =
        DependencyGraph.of(
            policy(
                List.of(variable("a", "b + 1"), variable("b", "c + 1"), variable("c", "a + 1")),
                List.of()));

    assertThat(graph.isAcyclic()).isFalse();
    assertThat(graph.topologicalOrder())
        .as("there is no order to report when there is a cycle")
        .isEmpty();
    assertThat(graph.cycle()).isPresent();
    assertThat(graph.cycle().orElseThrow())
        .as("BEL-015's {cycle path} closes back on its first node")
        .containsExactly("a", "b", "c", "a");
  }

  @Test
  void aVariableThatReferencesItselfIsACycle() {
    DependencyGraph graph = DependencyGraph.of(policy(List.of(variable("a", "a + 1")), List.of()));

    assertThat(graph.isAcyclic()).isFalse();
    assertThat(graph.cycle().orElseThrow()).containsExactly("a", "a");
  }

  /** A result reference is {@code COMPONENT.output}, so the component is the root of the chain. */
  @Test
  void aResultReferenceDependsOnTheComponentNotOnTheOutputName() {
    Ir.ScorecardDef scorecard =
        new Ir.ScorecardDef(
            "MFI_APP_SCORE_V1",
            "Application scorecard",
            Ir.Scope.APPLICATION,
            300,
            List.of(
                new Ir.Characteristic(
                    "cycle",
                    "group.cycleNumber",
                    Optional.empty(),
                    List.of(new Ir.Bin("= 1", 20, Optional.empty())))),
            3,
            Ir.Scaling.IDENTITY,
            Ir.OnMissing.DEFAULT);

    Ir.PolicyIr withScorecard =
        new Ir.PolicyIr(
            1,
            List.of(),
            List.of(rule("CR_CUTOFF", "MFI_APP_SCORE_V1.score >= 650")),
            List.of(),
            List.of(scorecard),
            new Ir.FlowDef(List.of()),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Ir.PolicyIr.DEFAULT_CUSTOMER_REASON_LIMIT);

    DependencyGraph graph = DependencyGraph.of(withScorecard);

    assertThat(graph.dependenciesOf("CR_CUTOFF")).containsExactly("MFI_APP_SCORE_V1");
    assertThat(graph.topologicalOrder().indexOf("MFI_APP_SCORE_V1"))
        .isLessThan(graph.topologicalOrder().indexOf("CR_CUTOFF"));
  }

  @Test
  void theAuthorityAmountIsANodeOfItsOwn() {
    DependencyGraph graph =
        DependencyGraph.of(
            policy(
                List.of(variable("proposed", "application.requestedAmount")),
                List.of(),
                Optional.of("proposed")));

    assertThat(graph.nodes()).contains(DependencyGraph.AUTHORITY_AMOUNT_NODE);
    assertThat(graph.dependenciesOf(DependencyGraph.AUTHORITY_AMOUNT_NODE))
        .containsExactly("proposed");
    assertThat(graph.topologicalOrder().indexOf("proposed"))
        .isLessThan(graph.topologicalOrder().indexOf(DependencyGraph.AUTHORITY_AMOUNT_NODE));
  }

  /** Two runs over the same policy must give the same order, not merely an equivalent one. */
  @Test
  void theOrderIsDeterministic() {
    Ir.PolicyIr subject =
        policy(
            List.of(
                variable("d", "a + b + c"),
                variable("a", "application.a"),
                variable("b", "application.b"),
                variable("c", "application.c")),
            List.of(rule("R1", "d > 0"), rule("R2", "d < 100")));

    List<String> first = DependencyGraph.of(subject).topologicalOrder();
    for (int run = 0; run < 25; run++) {
      assertThat(DependencyGraph.of(subject).topologicalOrder()).isEqualTo(first);
    }
  }

  /**
   * A syntax error belongs to the parse stage (BEL-001), which runs before this one. Building the
   * graph must not throw, or a policy with one bad expression would report no other diagnostic.
   */
  @Test
  void anUnparseableExpressionYieldsNoEdgesRatherThanAnException() {
    DependencyGraph graph =
        DependencyGraph.of(
            policy(
                List.of(variable("broken", "application. ) +"), variable("fine", "application.a")),
                List.of()));

    assertThat(graph.dependenciesOf("broken")).isEmpty();
    assertThat(graph.isAcyclic()).isTrue();
    assertThat(graph.topologicalOrder()).contains("broken", "fine");
  }

  @Test
  void aPolicyWithNoVariablesOrComponentsHasAnEmptyGraph() {
    DependencyGraph graph = DependencyGraph.of(policy(List.of(), List.of()));

    assertThat(graph.nodes()).isEmpty();
    assertThat(graph.topologicalOrder()).isEmpty();
    assertThat(graph.isAcyclic()).isTrue();
  }
}
