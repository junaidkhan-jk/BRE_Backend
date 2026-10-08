package dev.bre.engine.eval;

import dev.bre.engine.bel.ast.Expr;
import dev.bre.engine.eval.Value.BoolV;
import dev.bre.engine.eval.Value.DateV;
import dev.bre.engine.eval.Value.IntV;
import dev.bre.engine.eval.Value.ListV;
import dev.bre.engine.eval.Value.Missing;
import dev.bre.engine.eval.Value.TextV;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The closed function registry (LLD §3.9).
 *
 * <p><b>Closed</b> is the security property: an author cannot define a function, and cannot reach
 * anything the engine does not offer here. That is what rules out the whole class of expression
 * sandbox escapes — Draft 1 used SpEL with a {@code StandardEvaluationContext}, which allows
 * arbitrary code execution (ADR-002).
 *
 * <p>The aggregate semantics are the part worth reading twice (§3.5, S2):
 *
 * <ul>
 *   <li><b>Aggregates are strict.</b> {@code sum([100, missing, 50])} is <b>missing</b>, not 150.
 *       Silently summing around a gap understates a total, which is the direction that approves a
 *       loan it should not.
 *   <li><b>{@code count} is not.</b> {@code count([100, missing, 50])} is <b>3</b> — it counts
 *       elements, and the elements are there whatever their values.
 *   <li><b>Empty lists have defined answers.</b> {@code sum([])} is 0, {@code all([], …)} is TRUE
 *       and {@code any([], …)} is FALSE; {@code max}, {@code min}, {@code avg} and {@code first} of
 *       an empty list are missing.
 * </ul>
 */
final class Functions {

  private Functions() {}

  /**
   * Calls a registry function.
   *
   * @param call the call node
   * @param eval evaluates a sub-expression in the current context
   * @param context the evaluation context, needed to bind {@code it} for predicate arguments
   * @return the result, possibly {@link Missing}
   */
  static Value call(Expr.Call call, Function<Expr, Value> eval, EvaluationContext context) {
    List<Expr> args = call.arguments();
    return switch (call.function()) {
      // --- missing-awareness. isMissing is never unknown (§3.5). ---
      case "isMissing" -> BoolV.of(eval.apply(arity(call, args, 1).get(0)).isMissing());
      case "coalesce" -> coalesce(args, eval);

      // --- numeric ---
      case "round" -> round(call, args, eval);
      case "abs" -> abs(eval.apply(arity(call, args, 1).get(0)));

      // --- aggregates over lists ---
      case "sum" -> sum(eval.apply(arity(call, args, 1).get(0)));
      case "count" -> count(eval.apply(arity(call, args, 1).get(0)));
      case "distinctCount" -> distinctCount(eval.apply(arity(call, args, 1).get(0)));
      case "max" -> extremum(eval.apply(arity(call, args, 1).get(0)), true);
      case "min" -> extremum(eval.apply(arity(call, args, 1).get(0)), false);
      case "avg" -> avg(eval.apply(arity(call, args, 1).get(0)));
      case "first" -> first(eval.apply(arity(call, args, 1).get(0)));

      // --- quantifiers, which bind `it` over each element ---
      case "any" -> quantify(call, args, eval, context, false);
      case "all" -> quantify(call, args, eval, context, true);

      // --- dates (§3.7). None of these reads a clock. ---
      case "date" -> date(call, args, eval);
      case "ageInYears" ->
          Dates.ageInYears(eval.apply(arity(call, args, 2).get(0)), eval.apply(args.get(1)));
      case "monthsBetween" ->
          Dates.monthsBetween(eval.apply(arity(call, args, 2).get(0)), eval.apply(args.get(1)));
      case "daysBetween" ->
          Dates.daysBetween(eval.apply(arity(call, args, 2).get(0)), eval.apply(args.get(1)));
      case "addMonths" ->
          Dates.addMonths(eval.apply(arity(call, args, 2).get(0)), eval.apply(args.get(1)));

      default ->
          // The registry is closed: an unknown name is a compile-time diagnostic (BEL-0xx) that
          // the type checker raises in M1-03. Reaching here means it got past the checker.
          throw new EvalException(EvalErrorCode.EVAL_INTERNAL, call.function(), "UnknownFunction");
    };
  }

  private static List<Expr> arity(Expr.Call call, List<Expr> args, int expected) {
    if (args.size() != expected) {
      throw new EvalException(
          EvalErrorCode.EVAL_INTERNAL,
          call.function() + " expects " + expected + " argument(s), got " + args.size(),
          "Arity");
    }
    return args;
  }

  private static Value coalesce(List<Expr> args, Function<Expr, Value> eval) {
    for (Expr arg : args) {
      Value value = eval.apply(arg);
      if (!value.isMissing()) {
        return value;
      }
    }
    return Missing.INSTANCE;
  }

  private static Value round(Expr.Call call, List<Expr> args, Function<Expr, Value> eval) {
    arity(call, args, 3);
    Value operand = eval.apply(args.get(0));
    Value scale = eval.apply(args.get(1));

    // The scale must be an Integer literal (§3.6), so it is read from the AST rather than
    // evaluated as a general expression. A negative scale is a unary minus over a literal, not a
    // literal — round(4444444.44, -3, DOWN) would otherwise be rejected (D-06a).
    Integer scaleLiteral = integerLiteral(args.get(1));
    if (scaleLiteral == null) {
      if (scale.isMissing()) {
        return Missing.INSTANCE;
      }
      throw new EvalException(EvalErrorCode.EVAL_INTERNAL, "round scale", "IntegerLiteral");
    }

    // The mode is a contextual keyword, valid only here (§3.1) — an identifier in the AST, not a
    // value, so it is read from the tree.
    if (!(args.get(2) instanceof Expr.Ref modeRef)) {
      throw new EvalException(EvalErrorCode.EVAL_INTERNAL, "round mode", "RoundingMode");
    }
    RoundingMode mode = roundingMode(modeRef.name());
    return Decimals.round(operand, scaleLiteral, mode);
  }

  /** Reads an Integer literal, with or without a leading unary minus. Null if it is neither. */
  private static Integer integerLiteral(Expr expr) {
    if (expr instanceof Expr.IntLit literal) {
      return (int) literal.value();
    }
    if (expr instanceof Expr.Negate negate && negate.operand() instanceof Expr.IntLit literal) {
      return (int) -literal.value();
    }
    return null;
  }

  private static RoundingMode roundingMode(String name) {
    return switch (name) {
      case "HALF_UP" -> RoundingMode.HALF_UP;
      case "HALF_EVEN" -> RoundingMode.HALF_EVEN;
      case "HALF_DOWN" -> RoundingMode.HALF_DOWN;
      case "UP" -> RoundingMode.UP;
      case "DOWN" -> RoundingMode.DOWN;
      case "CEILING" -> RoundingMode.CEILING;
      case "FLOOR" -> RoundingMode.FLOOR;
      default -> throw new EvalException(EvalErrorCode.EVAL_INTERNAL, name, "RoundingMode");
    };
  }

  private static Value abs(Value operand) {
    if (operand.isMissing()) {
      return Missing.INSTANCE;
    }
    return Decimals.compare(operand, new IntV(0)) < 0 ? Decimals.negate(operand) : operand;
  }

  private static Value sum(Value value) {
    if (value.isMissing()) {
      return Missing.INSTANCE;
    }
    List<Value> elements = elementsOf(value);
    if (elements.isEmpty()) {
      return new IntV(0); // sum([]) = 0 (S2-01)
    }
    Value total = new IntV(0);
    for (Value element : elements) {
      if (element.isMissing()) {
        return Missing.INSTANCE; // strict (S2-03, S2-04)
      }
      total = Decimals.add(total, element);
    }
    return total;
  }

  private static Value count(Value value) {
    if (value.isMissing()) {
      return Missing.INSTANCE;
    }
    // Counts ELEMENTS, including missing ones: count([100, missing, 50]) = 3 (S2-03).
    return new IntV(elementsOf(value).size());
  }

  private static Value distinctCount(Value value) {
    if (value.isMissing()) {
      return Missing.INSTANCE;
    }
    Set<String> seen = new LinkedHashSet<>();
    for (Value element : elementsOf(value)) {
      if (element.isMissing()) {
        return Missing.INSTANCE;
      }
      seen.add(canonical(element));
    }
    return new IntV(seen.size());
  }

  /** A scale-insensitive key, so {@code 1.0} and {@code 1.00} are one distinct value (§3.6). */
  private static String canonical(Value value) {
    return switch (value) {
      case IntV i -> "n:" + i.value();
      case Value.DecimalV d -> "n:" + d.toCanonicalString();
      case TextV t -> "t:" + t.value();
      case BoolV b -> "b:" + b.value();
      case DateV d -> "d:" + d.value();
      default -> value.typeName() + ":" + value;
    };
  }

  private static Value extremum(Value value, boolean wantMax) {
    if (value.isMissing()) {
      return Missing.INSTANCE;
    }
    List<Value> elements = elementsOf(value);
    if (elements.isEmpty()) {
      return Missing.INSTANCE; // max([]) and min([]) are missing (S2-02)
    }
    Value best = null;
    for (Value element : elements) {
      if (element.isMissing()) {
        return Missing.INSTANCE;
      }
      if (best == null || (Decimals.compare(element, best) > 0) == wantMax) {
        if (best == null || Decimals.compare(element, best) != 0) {
          best = element;
        }
      }
    }
    return best;
  }

  private static Value avg(Value value) {
    if (value.isMissing()) {
      return Missing.INSTANCE;
    }
    List<Value> elements = elementsOf(value);
    if (elements.isEmpty()) {
      return Missing.INSTANCE; // avg([]) is missing (S2-02)
    }
    Value total = sum(value);
    if (total.isMissing()) {
      return Missing.INSTANCE;
    }
    return Decimals.divide(total, new IntV(elements.size()), null);
  }

  private static Value first(Value value) {
    if (value.isMissing()) {
      return Missing.INSTANCE;
    }
    List<Value> elements = elementsOf(value);
    return elements.isEmpty() ? Missing.INSTANCE : elements.get(0);
  }

  private static Value quantify(
      Expr.Call call,
      List<Expr> args,
      Function<Expr, Value> eval,
      EvaluationContext context,
      boolean requireAll) {
    arity(call, args, 2);
    Value target = eval.apply(args.get(0));
    if (target.isMissing()) {
      return Missing.INSTANCE;
    }
    List<Value> elements = elementsOf(target);
    if (elements.isEmpty()) {
      // all([], …) is TRUE and any([], …) is FALSE (S2-01) — the standard vacuous answers.
      return BoolV.of(requireAll);
    }

    boolean sawMissing = false;
    for (Value element : elements) {
      Value verdict = Evaluator.evaluate(args.get(1), context.withIt(element));
      if (verdict.isMissing()) {
        sawMissing = true;
        continue;
      }
      boolean holds = ((BoolV) verdict).value();
      if (requireAll && !holds) {
        return BoolV.FALSE; // one counterexample settles `all`
      }
      if (!requireAll && holds) {
        return BoolV.TRUE; // one witness settles `any`
      }
    }
    // No decisive element. An unknown among them makes the answer unknown.
    return sawMissing ? Missing.INSTANCE : BoolV.of(requireAll);
  }

  private static Value date(Expr.Call call, List<Expr> args, Function<Expr, Value> eval) {
    arity(call, args, 1);
    // Date literals exist only through date("YYYY-MM-DD") with a string LITERAL argument, folded
    // at compile time (§3.7). An invalid value is BEL-012.
    if (!(args.get(0) instanceof Expr.StringLit literal)) {
      Value evaluated = eval.apply(args.get(0));
      if (evaluated.isMissing()) {
        return Missing.INSTANCE;
      }
      throw new EvalException(EvalErrorCode.EVAL_INTERNAL, "date()", "StringLiteral");
    }
    try {
      return new DateV(LocalDate.parse(literal.value()));
    } catch (DateTimeParseException invalid) {
      throw new EvalException(EvalErrorCode.EVAL_INTERNAL, "date()", "Date");
    }
  }

  private static List<Value> elementsOf(Value value) {
    if (value instanceof ListV list) {
      return list.elements();
    }
    // A scalar where a list was expected is a type-checker gap; treat it as a one-element list so
    // the failure surfaces as a wrong answer in a vector rather than a crash in production.
    List<Value> single = new ArrayList<>(1);
    single.add(value);
    return single;
  }
}
