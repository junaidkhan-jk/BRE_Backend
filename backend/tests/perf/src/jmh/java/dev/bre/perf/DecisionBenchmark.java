package dev.bre.perf;

import dev.bre.engine.bel.parse.Parser;
import dev.bre.engine.decide.FlowEvaluator;
import dev.bre.engine.eval.EvaluationContext;
import dev.bre.engine.eval.Evaluator;
import dev.bre.engine.eval.Value;
import dev.bre.engine.ir.Ir;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * The first measurement of this engine's speed (M1-16, and M1-05's prerequisite).
 *
 * <p>No performance figure for the engine has existed until now. That matters more than it sounds:
 * M1-05 asks for the evaluator to be rewritten from a tree-walk into a lowered closure tree, which
 * is a performance refactor of the single component that 90 conformance vectors pin. Doing that
 * without a baseline is optimising blind — there would be no way to say afterwards whether it
 * helped, and no way to notice if it hurt.
 *
 * <p>The targets this is measured against (00 §4): <b>NFR-PERF-01</b>, a 100-rule policy at p50 ≤
 * 20 ms and p99 ≤ 100 ms <em>including the durable decision write</em>; <b>NFR-PERF-02</b>, a
 * 1,000-rule large case at p99 ≤ 250 ms. Those are whole-request budgets and this measures only the
 * engine, so the engine's share needs to be a small fraction of them — the LLD's own M1-16 note
 * puts the engine at p99 ≤ 0.5 ms for 100 rules.
 *
 * <p>Sample mode rather than throughput, because the budgets are percentile budgets. An average
 * tells you nothing about a p99.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(
    value = 1,
    jvmArgs = {"-Xms1g", "-Xmx1g"})
@State(Scope.Benchmark)
public class DecisionBenchmark {

  /** Rule counts spanning both NFR budgets. */
  @Param({"10", "100", "1000"})
  public int ruleCount;

  private Ir.PolicyIr policy;
  private EvaluationContext context;

  @Setup
  public void setUp() {
    policy = policyOf(ruleCount);
    context = applicationContext();
  }

  /** One whole decision: the flow, every rule, aggregation and precedence. */
  @Benchmark
  public void decision(Blackhole blackhole) {
    blackhole.consume(FlowEvaluator.evaluate(policy, context));
  }

  /**
   * One expression, parsed ahead of time — the inner loop a lowered closure tree would change.
   *
   * <p>Isolated from the decision benchmark so that M1-05's effect is visible: lowering changes how
   * an expression evaluates, and a whole-decision number dilutes that with flow and aggregation
   * work the lowering does not touch.
   */
  @Benchmark
  public void expressionOnly(Blackhole blackhole) {
    blackhole.consume(Evaluator.evaluate(PREPARSED, context));
  }

  /** Parsing, measured separately because a lowered tree is built once and evaluated many times. */
  @Benchmark
  public void parseOnly(Blackhole blackhole) {
    blackhole.consume(Parser.parseExpression(EXPRESSION));
  }

  private static final String EXPRESSION =
      "application.requestedAmount / application.tenureMonths <= application.monthlyIncome * 0.5"
          + " and application.score >= 650";

  private static final dev.bre.engine.bel.ast.Expr PREPARSED = Parser.parseExpression(EXPRESSION);

  private static Ir.PolicyIr policyOf(int ruleCount) {
    List<Ir.RuleDef> rules = new ArrayList<>(ruleCount);
    List<Ir.StepDef> steps = new ArrayList<>(ruleCount);
    for (int i = 0; i < ruleCount; i++) {
      String code = "R" + i;
      rules.add(
          new Ir.RuleDef(
              code,
              code,
              Ir.Scope.APPLICATION,
              Optional.empty(),
              // Deliberately not a trivial comparison: arithmetic, a conjunction and three fact
              // reads, which is the shape of a real affordability rule.
              "application.requestedAmount / application.tenureMonths <= application.monthlyIncome"
                  + " * 0.5 and application.score >= "
                  + (600 + (i % 50)),
              Ir.Outcome.fail("POL_" + code),
              Ir.OnMissing.DEFAULT,
              Map.of(),
              Optional.empty()));
      steps.add(Ir.StepDef.of(code, Ir.AggregateSpec.of(Ir.AggregateSpec.Kind.WORST)));
    }
    return new Ir.PolicyIr(
        1,
        List.of(),
        rules,
        List.of(),
        List.of(),
        new Ir.FlowDef(List.of(new Ir.StageDef("ELIGIBILITY", false, steps))),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Ir.PolicyIr.DEFAULT_CUSTOMER_REASON_LIMIT);
  }

  private static EvaluationContext applicationContext() {
    Map<String, Value> application = new LinkedHashMap<>();
    application.put("requestedAmount", Value.DecimalV.of("212500.00"));
    application.put("tenureMonths", new Value.IntV(24));
    application.put("monthlyIncome", Value.DecimalV.of("45000.00"));
    application.put("score", new Value.IntV(712));
    return EvaluationContext.of(
        new Value.RecordV(Map.of("application", new Value.RecordV(application))),
        Map.of(),
        Map.of(),
        LocalDate.parse("2026-10-01"));
  }
}
