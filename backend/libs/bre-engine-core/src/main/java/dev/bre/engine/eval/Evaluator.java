package dev.bre.engine.eval;

import dev.bre.engine.bel.ast.Expr;
import dev.bre.engine.bel.lex.Token;
import dev.bre.engine.eval.Value.BoolV;
import dev.bre.engine.eval.Value.DecimalV;
import dev.bre.engine.eval.Value.IntV;
import dev.bre.engine.eval.Value.ListV;
import dev.bre.engine.eval.Value.Missing;
import dev.bre.engine.eval.Value.RecordV;
import dev.bre.engine.eval.Value.TextV;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates a parsed BEL expression (LLD §3.5–3.9, M1-05/M1-06).
 *
 * <p>Three properties here are the ones that matter, and all three are pinned by conformance
 * vectors rather than by this comment:
 *
 * <ol>
 *   <li><b>Three-valued logic with short-circuiting (§3.5).</b> {@code false and x} is FALSE
 *       without evaluating {@code x}, and {@code true or x} is TRUE without evaluating it — so a
 *       guard like {@code income > 0 and emi / income <= cap} is safe, because the division never
 *       runs when the guard is false. An unevaluated operand cannot error and cannot burn fuel.
 *   <li><b>Strict missing propagation.</b> Arithmetic, comparison, {@code between} and {@code in}
 *       all return missing if any operand is missing — with one exception §3.5 calls out: {@code
 *       in} is TRUE when the value equals a <em>present</em> element, even if other elements are
 *       missing, because that answer cannot change once a match is found.
 *   <li><b>Fail-closed conditionals.</b> {@code if c then a else b} with unknown {@code c} is
 *       <b>missing</b>, never the else branch. Falling through to {@code else} is how a missing
 *       fact silently becomes an approval, which is the defect D9 exists to close.
 * </ol>
 *
 * <p>This is a tree-walking evaluator. The specification's design lowers a policy to a closure tree
 * at compile time (§4.4) for the latency budget; that is M1-04/M1-05's optimisation, and the
 * semantics it must preserve are exactly the ones here.
 */
public final class Evaluator {

  /** Rounding-mode constants, valid only as the third argument of {@code round} (§3.1). */
  private static final List<String> ROUNDING_MODES =
      List.of("HALF_UP", "HALF_EVEN", "HALF_DOWN", "UP", "DOWN", "CEILING", "FLOOR");

  private final EvaluationContext context;

  private Evaluator(EvaluationContext context) {
    this.context = context;
  }

  /**
   * Evaluates an expression against a context.
   *
   * @param expr the parsed expression
   * @param context the facts, parameters, lists and {@code asOf}
   * @return the resulting value, possibly {@link Missing}
   * @throws EvalException on a runtime error such as division by zero (§3.11)
   */
  public static Value evaluate(Expr expr, EvaluationContext context) {
    return new Evaluator(context).eval(expr);
  }

  private Value eval(Expr expr) {
    // §3.10: every AST node costs one unit. Charged here rather than per node type so that no
    // expression form can be added later that quietly costs nothing.
    context.fuel().charge();
    if (context.recorder().isRecording()) {
      return evalRecording(expr);
    }
    return evalNode(expr);
  }

  /**
   * Evaluates one node while recording the explanation (§4.12).
   *
   * <p>Separate from the plain path so that a decision pays nothing for tracing. The recorder on
   * the decision path is {@code TraceRecorder.NONE}, whose {@code isRecording()} is a constant
   * false the JIT folds away — an explanation is regenerated on demand by re-running with a real
   * recorder attached, never produced speculatively.
   */
  private Value evalRecording(Expr expr) {
    Token token = tokenOf(expr);
    int start = token == null ? 0 : token.start();
    int end = token == null ? 0 : token.end();
    context.recorder().enter(describe(expr), start, end);
    Value value;
    try {
      value = evalNode(expr);
    } catch (RuntimeException failed) {
      // The frame has to be closed or every later node nests under a dead one.
      context.recorder().exit(Missing.INSTANCE, null);
      throw failed;
    }
    context.recorder().exit(value, factPathOf(expr));
    return value;
  }

  /** The fact path a node read, when it read one — which is what decides masking (§9.4). */
  private static String factPathOf(Expr expr) {
    return expr instanceof Expr.Field || expr instanceof Expr.Ref ? describe(expr) : null;
  }

  private static Token tokenOf(Expr expr) {
    return switch (expr) {
      case Expr.IntLit e -> e.token();
      case Expr.DecimalLit e -> e.token();
      case Expr.StringLit e -> e.token();
      case Expr.BoolLit e -> e.token();
      case Expr.Ref e -> e.token();
      case Expr.Field e -> e.token();
      case Expr.Filter e -> e.token();
      case Expr.Call e -> e.token();
      case Expr.ListLit e -> e.token();
      case Expr.Negate e -> e.token();
      case Expr.Not e -> e.token();
      case Expr.Arithmetic e -> e.token();
      case Expr.Comparison e -> e.token();
      case Expr.Logical e -> e.token();
      case Expr.In e -> e.token();
      case Expr.Between e -> e.token();
      case Expr.If e -> e.token();
      case Expr.Range e -> e.token();
    };
  }

  private Value evalNode(Expr expr) {
    return switch (expr) {
      case Expr.IntLit e -> new IntV(e.value());
      case Expr.DecimalLit e -> new DecimalV(e.value());
      case Expr.StringLit e -> new TextV(e.value());
      case Expr.BoolLit e -> BoolV.of(e.value());
      case Expr.Ref e -> reference(e);
      case Expr.Field e -> field(e);
      case Expr.Filter e -> filter(e);
      case Expr.ListLit e -> listLiteral(e);
      case Expr.Negate e -> Decimals.negate(eval(e.operand()));
      case Expr.Not e -> not(eval(e.operand()));
      case Expr.Arithmetic e -> arithmetic(e);
      case Expr.Comparison e -> comparison(e);
      case Expr.Logical e -> logical(e);
      case Expr.In e -> in(e);
      case Expr.Between e -> between(e);
      case Expr.If e -> conditional(e);
      case Expr.Call e -> Functions.call(e, this::eval, context);
      case Expr.Range e ->
          // A range is only meaningful as an `in` target or a unary test; `in` handles it
          // directly, so reaching here means the type checker let something through.
          throw new EvalException(EvalErrorCode.EVAL_INTERNAL, null, "Range");
    };
  }

  private Value reference(Expr.Ref ref) {
    return switch (ref.name()) {
      case "asOf" -> context.asOfValue();
      case "it" -> context.it();
      // `param`, `list`, `rule`, `table` and `scorecard` are namespace roots: they are only ever
      // meaningful as the target of a field access, which `field()` resolves.
      case "param", "list", "rule", "table", "scorecard" -> Missing.INSTANCE;
      default -> {
        if (ROUNDING_MODES.contains(ref.name())) {
          // A rounding-mode constant reaching here means it was used outside round()'s third
          // argument, which the type checker rejects (M1-03).
          throw new EvalException(EvalErrorCode.EVAL_INTERNAL, ref.name(), "RoundingMode");
        }
        // A declared variable shadows a fact root of the same name: the policy said what that
        // name means, and the binder resolves it that way (§3.8).
        if (context.variables().declares(ref.name())) {
          yield context
              .variables()
              .resolve(
                  ref.name(),
                  context,
                  (expression, where) ->
                      evaluate(dev.bre.engine.bel.parse.Compiled.expression(expression), where));
        }
        // Otherwise a fact root, and an absent one reads as missing rather than failing.
        yield context.facts().field(ref.name());
      }
    };
  }

  private Value field(Expr.Field access) {
    // Namespaced references are postfix chains rooted at a reserved word (§3.2).
    if (access.target() instanceof Expr.Ref root) {
      switch (root.name()) {
        case "param":
          return context.parameter(access.name());
        case "list":
          return context.list(access.name());
        default:
          break;
      }
    }

    Value target = eval(access.target());
    return switch (target) {
      case Missing ignored -> Missing.INSTANCE;
      case RecordV record -> record.field(access.name());
      // Projection over a list: `l.f` is List<type of f>, flattened one level if f is itself a
      // list (§3.4). Flattening is what makes count(member.bureauReports.tradelines) = 3 over
      // two reports holding 1 and 2 tradelines (S4-01).
      case ListV list -> project(list, access.name());
      default -> Missing.INSTANCE;
    };
  }

  /**
   * Projection over a list, {@code l.f} (§3.4): the field of every element, flattened one level
   * when the field is itself a list.
   *
   * <p>The two cases differ on what a missing field means, and the difference is load-bearing:
   *
   * <ul>
   *   <li><b>A missing list-valued field makes the whole projection missing</b> (S4-02). Absence is
   *       not emptiness: a bureau report with no {@code tradelines} field is a report we did not
   *       receive, not a borrower with zero tradelines, and counting it as zero is the direction
   *       that approves.
   *   <li><b>A missing scalar field stays a missing element</b> (S2-04), where the strictness of
   *       {@code sum} is what then propagates it.
   * </ul>
   *
   * <p>ponytail: which case applies is decided at runtime, by whether any element's field is
   * actually a list. The type checker will know this statically from the fact schema (M1-03), at
   * which point this should read the declared type instead of sniffing the data — a list-valued
   * field that happens to be absent on *every* element currently takes the scalar path.
   */
  private Value project(ListV list, String fieldName) {
    // §3.10 caps a list "whether it arrives as a fact or is produced at runtime". The service
    // rejects an oversized facts document up front with `facts-too-large`, but the engine checks
    // too: it is also reachable from a reference list and from an earlier projection, and an
    // engine that trusted its caller to have checked would be one configuration change from
    // unbounded work.
    context.listCap().check(list.elements().size());
    context.fuel().charge(list.elements().size()); // §3.10: a projection costs 1 + n.

    boolean flattening = false;
    for (Value element : list.elements()) {
      if (element instanceof RecordV record && record.field(fieldName) instanceof ListV) {
        flattening = true;
        break;
      }
    }

    List<Value> projected = new ArrayList<>();
    for (Value element : list.elements()) {
      Value fieldValue =
          element instanceof RecordV record ? record.field(fieldName) : Missing.INSTANCE;
      if (fieldValue instanceof ListV nested) {
        projected.addAll(nested.elements());
      } else if (flattening && fieldValue.isMissing()) {
        return Missing.INSTANCE; // S4-02
      } else {
        projected.add(fieldValue);
      }
      // Checked inside the loop, not only at the end. A projection that flattens nested lists
      // multiplies, so `group.members.bureauReports.tradelines` over bounded inputs can produce an
      // unbounded result — and the point of the cap is to stop the work, not to measure it after.
      context.listCap().check(projected.size());
    }
    return new ListV(projected);
  }

  private Value filter(Expr.Filter expr) {
    Value target = eval(expr.target());
    if (target.isMissing()) {
      return Missing.INSTANCE;
    }
    if (!(target instanceof ListV list)) {
      throw new EvalException(EvalErrorCode.EVAL_INTERNAL, null, target.typeName());
    }

    context.listCap().check(list.elements().size());
    // §3.10: a filter costs 1 + n before the predicate's own cost, which the recursive
    // evaluation of each element charges as it goes.
    context.fuel().charge(list.elements().size());

    List<Value> kept = new ArrayList<>();
    for (Value element : list.elements()) {
      Value verdict = Evaluator.evaluate(expr.predicate(), context.withIt(element));
      if (verdict.isMissing()) {
        // FAIL-CLOSED FILTER (S3-01). An unknown predicate for ANY element makes the whole
        // filtered list missing — it does not quietly drop that element. Dropping it would
        // understate a total such as sum(tradelines[status = "ACTIVE"].balance), which is
        // exactly the direction that approves a loan it should not.
        return Missing.INSTANCE;
      }
      if (verdict instanceof BoolV bool && bool.value()) {
        kept.add(element);
      }
    }
    // A filter can only shrink its input, so one check on the result is enough here.
    return context.listCap().of(kept);
  }

  private Value listLiteral(Expr.ListLit literal) {
    List<Value> elements = new ArrayList<>(literal.elements().size());
    for (Expr element : literal.elements()) {
      elements.add(eval(element));
    }
    return context.listCap().of(elements);
  }

  private Value arithmetic(Expr.Arithmetic expr) {
    Value left = eval(expr.left());
    Value right = eval(expr.right());
    return switch (expr.op()) {
      case ADD -> Decimals.add(left, right);
      case SUBTRACT -> Decimals.subtract(left, right);
      case MULTIPLY -> Decimals.multiply(left, right);
      case DIVIDE -> Decimals.divide(left, right, describe(expr.right()));
    };
  }

  /** A fact PATH for an error message, never a value (LLD §1.2 rule 3). */
  private static String describe(Expr expr) {
    return switch (expr) {
      case Expr.Ref ref -> ref.name();
      case Expr.Field field -> describe(field.target()) + "." + field.name();
      case Expr.Call call -> call.function() + "(...)";
      case Expr.Arithmetic arithmetic ->
          describe(arithmetic.left()) + " " + arithmetic.op() + " " + describe(arithmetic.right());
      case Expr.Comparison comparison ->
          describe(comparison.left()) + " " + comparison.op() + " " + describe(comparison.right());
      case Expr.Logical logical ->
          describe(logical.left()) + " " + logical.op() + " " + describe(logical.right());
      case Expr.IntLit lit -> String.valueOf(lit.value());
      case Expr.DecimalLit lit -> String.valueOf(lit.value());
      case Expr.StringLit lit -> "\"" + lit.value() + "\"";
      case Expr.BoolLit lit -> String.valueOf(lit.value());
      case Expr.Filter filter -> describe(filter.target()) + "[...]";
      default -> null;
    };
  }

  private Value comparison(Expr.Comparison expr) {
    return Comparisons.compare(expr.op(), eval(expr.left()), eval(expr.right()));
  }

  private Value logical(Expr.Logical expr) {
    // Short-circuit BEFORE evaluating the right operand (§3.5). The truth tables stay exact
    // because a skipped operand cannot change the result.
    Value left = eval(expr.left());
    if (expr.op() == Expr.LogicalOp.AND) {
      if (left instanceof BoolV bool && !bool.value()) {
        return BoolV.FALSE; // F and anything = F, including F and unknown
      }
      Value right = eval(expr.right());
      return andOf(left, right);
    }
    if (left instanceof BoolV bool && bool.value()) {
      return BoolV.TRUE; // T or anything = T
    }
    Value right = eval(expr.right());
    return orOf(left, right);
  }

  private static Value andOf(Value left, Value right) {
    // left is TRUE or unknown here; FALSE short-circuited.
    if (right instanceof BoolV rb && !rb.value()) {
      return BoolV.FALSE; // unknown and F = F
    }
    if (left.isMissing() || right.isMissing()) {
      return Missing.INSTANCE;
    }
    return BoolV.of(((BoolV) left).value() && ((BoolV) right).value());
  }

  private static Value orOf(Value left, Value right) {
    // left is FALSE or unknown here; TRUE short-circuited.
    if (right instanceof BoolV rb && rb.value()) {
      return BoolV.TRUE; // unknown or T = T
    }
    if (left.isMissing() || right.isMissing()) {
      return Missing.INSTANCE;
    }
    return BoolV.of(((BoolV) left).value() || ((BoolV) right).value());
  }

  private static Value not(Value operand) {
    if (operand.isMissing()) {
      return Missing.INSTANCE; // not U = U
    }
    return BoolV.of(!((BoolV) operand).value());
  }

  private Value in(Expr.In expr) {
    Value value = eval(expr.value());
    Value result = membership(value, expr.target());
    if (result.isMissing()) {
      return Missing.INSTANCE;
    }
    boolean member = ((BoolV) result).value();
    return BoolV.of(expr.negated() != member);
  }

  private Value membership(Value value, Expr target) {
    if (target instanceof Expr.Range range) {
      return inRange(value, range);
    }

    Value container = eval(target);
    if (value.isMissing() || container.isMissing()) {
      return Missing.INSTANCE;
    }
    if (!(container instanceof ListV list)) {
      throw new EvalException(EvalErrorCode.EVAL_INTERNAL, null, container.typeName());
    }

    // §3.5's exception: if the value equals some PRESENT element, the answer is TRUE even when
    // other elements are missing — a found match cannot be unmade by an unknown (L-05a). Only
    // when nothing matches does a missing element make the answer unknown (L-05b).
    boolean sawMissingElement = false;
    for (Value element : list.elements()) {
      if (element.isMissing()) {
        sawMissingElement = true;
        continue;
      }
      if (Comparisons.areEqual(value, element)) {
        return BoolV.TRUE;
      }
    }
    return sawMissingElement ? Missing.INSTANCE : BoolV.FALSE;
  }

  private Value inRange(Value value, Expr.Range range) {
    Value low = eval(range.low());
    Value high = eval(range.high());
    if (value.isMissing() || low.isMissing() || high.isMissing()) {
      return Missing.INSTANCE;
    }
    int vsLow = Comparisons.order(value, low);
    int vsHigh = Comparisons.order(value, high);
    boolean aboveLow = range.lowInclusive() ? vsLow >= 0 : vsLow > 0;
    boolean belowHigh = range.highInclusive() ? vsHigh <= 0 : vsHigh < 0;
    return BoolV.of(aboveLow && belowHigh);
  }

  private Value between(Expr.Between expr) {
    Value value = eval(expr.value());
    Value low = eval(expr.low());
    Value high = eval(expr.high());
    if (value.isMissing() || low.isMissing() || high.isMissing()) {
      return Missing.INSTANCE;
    }
    boolean within = Comparisons.order(value, low) >= 0 && Comparisons.order(value, high) <= 0;
    return BoolV.of(expr.negated() != within);
  }

  private Value conditional(Expr.If expr) {
    Value condition = eval(expr.condition());
    if (condition.isMissing()) {
      // THE FAIL-CLOSED CHOICE (§3.5, L-06). An unknown condition yields missing; it never falls
      // through to else. An else branch reached because a fact was absent is how missing data
      // turns into an approval.
      return Missing.INSTANCE;
    }
    return ((BoolV) condition).value() ? eval(expr.thenBranch()) : eval(expr.elseBranch());
  }
}
