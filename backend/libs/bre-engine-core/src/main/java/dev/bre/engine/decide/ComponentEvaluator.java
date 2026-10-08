package dev.bre.engine.decide;

import dev.bre.engine.bel.ast.Expr;
import dev.bre.engine.bel.parse.Parser;
import dev.bre.engine.bel.parse.UnaryTest;
import dev.bre.engine.bel.parse.UnaryTestParser;
import dev.bre.engine.eval.EvalErrorCode;
import dev.bre.engine.eval.EvalException;
import dev.bre.engine.eval.EvaluationContext;
import dev.bre.engine.eval.Evaluator;
import dev.bre.engine.eval.UnaryTests;
import dev.bre.engine.eval.Value;
import dev.bre.engine.eval.Value.BoolV;
import dev.bre.engine.eval.Value.IntV;
import dev.bre.engine.ir.Ir;
import dev.bre.engine.ir.Ir.DecisionTableDef;
import dev.bre.engine.ir.Ir.OnMissing;
import dev.bre.engine.ir.Ir.OutcomeStatus;
import dev.bre.engine.ir.Ir.RuleDef;
import dev.bre.engine.ir.Ir.RuntimeStatus;
import dev.bre.engine.ir.Ir.ScorecardDef;
import dev.bre.engine.ir.Ir.TableRow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Evaluates one component — a rule, a decision table or a scorecard — for one entity (LLD §4.6).
 *
 * <p>All three share one shape: an optional {@code when} guard, then the component's own logic,
 * then {@code onMissing} if a needed fact was absent. The guard rule is the one that matters most
 * and is easy to get backwards: a {@code when} that is <b>FALSE</b> makes the component
 * NOT_APPLICABLE, but a {@code when} that is <b>unknown</b> goes to {@code onMissing} and must
 * never become NOT_APPLICABLE (S1-01). "We do not know whether this rule applies" is not the same
 * as "it does not apply", and treating it as such is a fail-open.
 */
public final class ComponentEvaluator {

  private ComponentEvaluator() {}

  // ---------------------------------------------------------------- rules

  /**
   * Evaluates a rule (§4.6).
   *
   * @param rule the rule
   * @param context the evaluation context for this entity
   * @return the result
   */
  public static ComponentResult evaluateRule(RuleDef rule, EvaluationContext context) {
    try {
      Optional<ComponentResult> guard =
          applyGuard(rule.code(), rule.when(), rule.onMissing(), context);
      if (guard.isPresent()) {
        return guard.get();
      }

      Value check = evaluate(rule.check(), context);
      if (check.isMissing()) {
        return applyOnMissing(rule.code(), rule.onMissing(), rule.check());
      }

      if (((BoolV) check).value()) {
        return ComponentResult.of(
            rule.code(),
            RuntimeStatus.PASS,
            null,
            null,
            null,
            null,
            evaluateOutputs(rule.outputs(), context));
      }
      return fromOutcome(rule.code(), rule.onFail(), rule.outputs(), context);

    } catch (EvalException runtimeError) {
      return ComponentResult.error(rule.code(), runtimeError.code().name());
    }
  }

  // ------------------------------------------------------- decision tables

  /**
   * Evaluates a decision table (§4.6, §3.3, vectors S5 and T).
   *
   * @param table the table
   * @param context the evaluation context for this entity
   * @return the result
   */
  public static ComponentResult evaluateTable(DecisionTableDef table, EvaluationContext context) {
    try {
      Optional<ComponentResult> guard =
          applyGuard(table.code(), table.when(), table.onMissing(), context);
      if (guard.isPresent()) {
        return guard.get();
      }

      // Inputs first, so "was any input missing?" is known before matching. That question decides
      // what a no-match means: onMissing if something was absent, ERROR if everything was present.
      List<Value> inputs = new ArrayList<>(table.inputs().size());
      boolean anyInputMissing = false;
      for (Ir.TableInput input : table.inputs()) {
        Value value = evaluate(input.expr(), context);
        anyInputMissing |= value.isMissing();
        inputs.add(value);
      }

      List<TableRow> matched = matchingRows(table, inputs, context);

      if (matched.isEmpty()) {
        // S5-01 and S5-04. A gap with all inputs present is a policy defect CHK-002 should have
        // caught at compile time, so it is an ERROR rather than a quiet pass.
        if (anyInputMissing) {
          // The derived reason names the first input that was actually missing, which is what
          // S5-01 asserts: group.villageRiskCategory -> DATA_MISSING_GROUP_VILLAGE_RISK_CATEGORY.
          String firstMissing = null;
          for (int column = 0; column < inputs.size(); column++) {
            if (inputs.get(column).isMissing()) {
              firstMissing = table.inputs().get(column).expr();
              break;
            }
          }
          return applyOnMissing(table.code(), table.onMissing(), firstMissing);
        }
        // EVAL_TABLE_NO_MATCH is scoped to non-COLLECT tables (§4.6). A COLLECT table that
        // matches nothing has collected nothing, which is a legitimate answer — T-03 pins it at
        // 0 for SUM — not a gap in the policy.
        if (table.hitPolicy().kind() == Ir.HitPolicy.Kind.COLLECT) {
          return collect(table, List.of(), context);
        }
        return ComponentResult.error(table.code(), EvalErrorCode.EVAL_TABLE_NO_MATCH.name());
      }

      return resolveHitPolicy(table, matched, context);

    } catch (EvalException runtimeError) {
      return ComponentResult.error(table.code(), runtimeError.code().name());
    }
  }

  private static List<TableRow> matchingRows(
      DecisionTableDef table, List<Value> inputs, EvaluationContext context) {
    List<TableRow> matched = new ArrayList<>();
    for (TableRow row : table.rows()) {
      if (rowMatches(row, inputs, context)) {
        matched.add(row);
      }
    }
    return matched;
  }

  /** A row matches when EVERY cell matches: rows are AND across columns (§3.3). */
  private static boolean rowMatches(TableRow row, List<Value> inputs, EvaluationContext context) {
    if (row.when().size() != inputs.size()) {
      // The compiler enforces equal arity (§2.2's closing constraints); reaching here is a defect.
      throw new EvalException(EvalErrorCode.EVAL_INTERNAL, "row arity", "DecisionTable");
    }
    for (int column = 0; column < inputs.size(); column++) {
      UnaryTest test = UnaryTestParser.parse(row.when().get(column));
      if (!UnaryTests.matches(test, inputs.get(column), context)) {
        return false;
      }
    }
    return true;
  }

  private static ComponentResult resolveHitPolicy(
      DecisionTableDef table, List<TableRow> matched, EvaluationContext context) {
    return switch (table.hitPolicy().kind()) {
      case FIRST -> fromRow(table, matched.get(0), context);

      case UNIQUE -> {
        if (matched.size() > 1) {
          // T-01. More than one row under UNIQUE is an author error CHK-001 should have caught;
          // picking one would make the policy mean something nobody wrote.
          yield ComponentResult.error(
              table.code(), EvalErrorCode.EVAL_TABLE_UNIQUE_VIOLATION.name());
        }
        yield fromRow(table, matched.get(0), context);
      }

      case PRIORITY -> {
        // T-02: the most severe matching row wins.
        TableRow worst =
            matched.stream()
                .max(Comparator.comparingInt(row -> severity(rowStatus(row))))
                .orElseThrow();
        yield fromRow(table, worst, context);
      }

      case COLLECT -> collect(table, matched, context);
    };
  }

  /** T-03: every matching row contributes to one aggregated output. */
  private static ComponentResult collect(
      DecisionTableDef table, List<TableRow> matched, EvaluationContext context) {
    String outputName = table.hitPolicy().collectOutput().orElseThrow();
    List<Value> contributions = new ArrayList<>();
    for (TableRow row : matched) {
      String expr = row.then().outputs().get(outputName);
      if (expr != null) {
        contributions.add(evaluate(expr, context));
      }
    }

    Value aggregated =
        switch (table.hitPolicy().op().orElseThrow()) {
          case SUM -> sum(contributions);
          case COUNT -> new IntV(contributions.size());
          case MIN -> extremum(contributions, false);
          case MAX -> extremum(contributions, true);
          case LIST -> new Value.ListV(contributions);
        };

    // A COLLECT table's rows carry no status, so a match is a PASS (§2.2's output-only rule).
    return ComponentResult.of(
        table.code(), RuntimeStatus.PASS, null, null, null, null, Map.of(outputName, aggregated));
  }

  private static Value sum(List<Value> values) {
    Value total = new IntV(0);
    for (Value value : values) {
      if (value.isMissing()) {
        return Value.Missing.INSTANCE;
      }
      total = dev.bre.engine.eval.Decimals.add(total, value);
    }
    return total;
  }

  private static Value extremum(List<Value> values, boolean wantMax) {
    Value best = null;
    for (Value value : values) {
      if (value.isMissing()) {
        return Value.Missing.INSTANCE;
      }
      if (best == null) {
        best = value;
        continue;
      }
      int order = dev.bre.engine.eval.Comparisons.order(value, best);
      // The `order != 0` clause is load-bearing, not defensive: on a tie under MIN,
      // `(order > 0) == wantMax` is `false == false`, which is true, and an equal value would
      // replace the incumbent for no reason. Parenthesised because the precedence that makes
      // this correct is not the precedence most readers assume.
      if (((order > 0) == wantMax) && (order != 0)) {
        best = value;
      }
    }
    return best == null ? Value.Missing.INSTANCE : best;
  }

  private static ComponentResult fromRow(
      DecisionTableDef table, TableRow row, EvaluationContext context) {
    Ir.RowResult then = row.then();
    Map<String, Value> outputs = evaluateOutputs(then.outputs(), context);

    // An output-only table — no row assigns a status — is a PASS on a match (§2.2).
    RuntimeStatus status =
        then.status().map(ComponentEvaluator::toRuntimeStatus).orElse(RuntimeStatus.PASS);

    return ComponentResult.of(
        table.code(),
        status,
        then.reasonCode().orElse(null),
        then.deviationType().orElse(null),
        then.measure().map(expr -> evaluate(expr, context)).orElse(null),
        then.worseWhen().orElse(null),
        outputs);
  }

  private static Optional<OutcomeStatus> rowStatus(TableRow row) {
    return row.then().status();
  }

  // ------------------------------------------------------------ scorecards

  /**
   * Evaluates a scorecard (§4.6, S11).
   *
   * <p>Adverse reasons are the part with real regulatory weight: a declined applicant is entitled
   * to know why, so the reasons reported are the characteristics with the largest
   * <em>shortfall</em> against their own maximum — not simply the lowest scores. A characteristic
   * that scored its maximum has a shortfall of zero and is never an adverse reason, however few
   * points it was worth (S11-01).
   *
   * @param scorecard the scorecard
   * @param context the evaluation context for this entity
   * @return the result, carrying the score as the {@code score} output
   */
  public static ComponentResult evaluateScorecard(
      ScorecardDef scorecard, EvaluationContext context) {
    try {
      long raw = scorecard.baseScore();
      List<Shortfall> shortfalls = new ArrayList<>();

      for (Ir.Characteristic characteristic : scorecard.characteristics()) {
        Value value = evaluate(characteristic.expr(), context);
        if (value.isMissing()) {
          return applyOnMissing(scorecard.code(), scorecard.onMissing(), characteristic.expr());
        }

        Optional<Ir.Bin> hit = Optional.empty();
        for (Ir.Bin bin : characteristic.bins()) {
          if (UnaryTests.matches(UnaryTestParser.parse(bin.when()), value, context)) {
            hit = Optional.of(bin);
            break;
          }
        }
        if (hit.isEmpty()) {
          // S11-02: a present value with no bin is an ERROR, not a zero.
          return ComponentResult.error(
              scorecard.code(), EvalErrorCode.EVAL_SCORECARD_NO_BIN.name());
        }

        int points = hit.get().points();
        raw += points;

        int best = characteristic.bins().stream().mapToInt(Ir.Bin::points).max().orElse(points);
        String reason = hit.get().reasonCode().or(characteristic::reasonCode).orElse(null);
        if (best - points > 0 && reason != null) {
          shortfalls.add(new Shortfall(reason, best - points));
        }
      }

      List<String> adverse =
          shortfalls.stream()
              // Largest shortfall first; ties go to declaration order, which a stable sort keeps.
              .sorted(Comparator.comparingInt(Shortfall::amount).reversed())
              .limit(scorecard.adverseReasons())
              .map(Shortfall::reasonCode)
              .toList();

      Value score = scale(raw, scorecard.scaling());
      // §4.10 and S11-01: the adverse reasons are a named output, `reasons`, so a policy can
      // reference scorecard.CODE.reasons the same way it references the score.
      Map<String, Value> outputs =
          Map.of(
              "score",
              score,
              "reasons",
              new Value.ListV(adverse.stream().map(r -> (Value) new Value.TextV(r)).toList()));
      return new ComponentResult(
          scorecard.code(),
          RuntimeStatus.PASS,
          RuntimeStatus.PASS,
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          outputs,
          Optional.empty(),
          adverse);

    } catch (EvalException runtimeError) {
      return ComponentResult.error(scorecard.code(), runtimeError.code().name());
    }
  }

  private record Shortfall(String reasonCode, int amount) {}

  private static Value scale(long raw, Ir.Scaling scaling) {
    Value scaled =
        dev.bre.engine.eval.Decimals.add(
            dev.bre.engine.eval.Decimals.multiply(
                new IntV(raw), new Value.DecimalV(scaling.multiplier())),
            new Value.DecimalV(scaling.offset()));
    return dev.bre.engine.eval.Decimals.round(
        scaled, scaling.scale(), java.math.RoundingMode.valueOf(scaling.roundingMode()));
  }

  // --------------------------------------------------------------- shared

  /**
   * Applies a component's {@code when} guard.
   *
   * @return a result when the guard settles the component, empty when evaluation should continue
   */
  private static Optional<ComponentResult> applyGuard(
      String code, Optional<String> when, OnMissing onMissing, EvaluationContext context) {
    if (when.isEmpty()) {
      return Optional.empty();
    }
    Value guard = evaluate(when.get(), context);
    if (guard.isMissing()) {
      // S1-01: an unknown `when` goes to onMissing, NEVER to NOT_APPLICABLE.
      return Optional.of(applyOnMissing(code, onMissing, when.get()));
    }
    if (!((BoolV) guard).value()) {
      return Optional.of(ComponentResult.of(code, RuntimeStatus.NOT_APPLICABLE)); // S1-02
    }
    return Optional.empty();
  }

  /** Applies {@code onMissing} (§4.6, M-01). */
  private static ComponentResult applyOnMissing(
      String code, OnMissing onMissing, String checkExpr) {
    return switch (onMissing.policy()) {
      case FAIL ->
          ComponentResult.of(code, RuntimeStatus.FAIL, onMissing.reasonCode().orElse(null));
      case NOT_APPLICABLE -> ComponentResult.of(code, RuntimeStatus.NOT_APPLICABLE);
      case REFER ->
          ComponentResult.of(
              code,
              RuntimeStatus.DATA_MISSING,
              onMissing.reasonCode().orElse(defaultDataMissingReason(checkExpr)));
      case USE_DEFAULT -> {
        // M-01c: re-run the decision point with the unknown Boolean replaced by the default. The
        // literal defaults the BOOLEAN, not the input value — so a check that reads TRUE passes.
        boolean defaulted = onMissing.value().orElse(false);
        yield defaulted
            ? ComponentResult.of(code, RuntimeStatus.PASS)
            : ComponentResult.of(code, RuntimeStatus.FAIL, onMissing.reasonCode().orElse(null));
      }
    };
  }

  /**
   * The derived reason code for a missing fact, {@code DATA_MISSING_} plus the first missing symbol
   * in SNAKE_CASE (§4.6).
   *
   * <p>ponytail: derived from the expression's first reference rather than from which symbol
   * actually came back missing, which needs the evaluator to report it. That is right for a
   * single-reference check and can name the wrong symbol in a multi-reference one. See {@code
   * docs/OPEN-QUESTIONS.md} Q2 — whether the fact root belongs in the code is unresolved in the LLD
   * itself, so this is not the place to settle it.
   */
  private static String defaultDataMissingReason(String checkExpr) {
    if (checkExpr == null) {
      return null;
    }
    try {
      String symbol = firstReference(Parser.parseExpression(checkExpr));
      return symbol == null ? null : "DATA_MISSING_" + toSnakeCase(symbol);
    } catch (RuntimeException unparseable) {
      return null;
    }
  }

  private static String firstReference(Expr expr) {
    return switch (expr) {
      case Expr.Ref ref -> ref.name();
      case Expr.Field field -> {
        String root = firstReference(field.target());
        yield root == null ? field.name() : root + "." + field.name();
      }
      case Expr.Not not -> firstReference(not.operand());
      case Expr.Negate negate -> firstReference(negate.operand());
      case Expr.Arithmetic arithmetic -> firstReference(arithmetic.left());
      case Expr.Comparison comparison -> firstReference(comparison.left());
      case Expr.Logical logical -> firstReference(logical.left());
      case Expr.In in -> firstReference(in.value());
      case Expr.Between between -> firstReference(between.value());
      case Expr.Call call ->
          call.arguments().isEmpty() ? null : firstReference(call.arguments().get(0));
      case Expr.Filter filter -> firstReference(filter.target());
      default -> null;
    };
  }

  private static String toSnakeCase(String symbol) {
    StringBuilder snake = new StringBuilder();
    for (int i = 0; i < symbol.length(); i++) {
      char c = symbol.charAt(i);
      if (c == '.') {
        snake.append('_');
      } else if (Character.isUpperCase(c)
          && !snake.isEmpty()
          && snake.charAt(snake.length() - 1) != '_') {
        snake.append('_').append(c);
      } else {
        snake.append(Character.toUpperCase(c));
      }
    }
    return snake.toString().toUpperCase(Locale.ROOT);
  }

  private static ComponentResult fromOutcome(
      String code, Ir.Outcome outcome, Map<String, String> outputs, EvaluationContext context) {
    return ComponentResult.of(
        code,
        toRuntimeStatus(outcome.status()),
        outcome.reasonCode().orElse(null),
        outcome.deviationType().orElse(null),
        outcome.measure().map(expr -> evaluate(expr, context)).orElse(null),
        outcome.worseWhen().orElse(null),
        evaluateOutputs(outputs, context));
  }

  private static Map<String, Value> evaluateOutputs(
      Map<String, String> outputs, EvaluationContext context) {
    if (outputs.isEmpty()) {
      return Map.of();
    }
    Map<String, Value> evaluated = new LinkedHashMap<>(outputs.size());
    outputs.forEach((name, expr) -> evaluated.put(name, evaluate(expr, context)));
    return evaluated;
  }

  private static RuntimeStatus toRuntimeStatus(OutcomeStatus status) {
    return switch (status) {
      case PASS -> RuntimeStatus.PASS;
      case DEVIATION -> RuntimeStatus.DEVIATION;
      case REFER -> RuntimeStatus.REFER;
      case FAIL -> RuntimeStatus.FAIL;
    };
  }

  /** Severity order for PRIORITY tables and WORST aggregation (§4.7). */
  static int severity(Optional<OutcomeStatus> status) {
    return status.map(ComponentEvaluator::severity).orElse(0);
  }

  static int severity(OutcomeStatus status) {
    return switch (status) {
      case PASS -> 1;
      case DEVIATION -> 2;
      case REFER -> 3;
      case FAIL -> 4;
    };
  }

  private static Value evaluate(String expression, EvaluationContext context) {
    return Evaluator.evaluate(Parser.parseExpression(expression), context);
  }
}
