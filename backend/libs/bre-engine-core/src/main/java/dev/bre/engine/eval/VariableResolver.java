package dev.bre.engine.eval;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Resolves a policy's declared variables (LLD §3.8, §4.2, §4.3).
 *
 * <p>Variables are named intermediate expressions a policy declares once and references many times,
 * so {@code memberUnsecuredOutstanding} can be written as a filtered bureau sum in one place and
 * used by every rule that needs it.
 *
 * <p><b>Resolved lazily and memoised, not evaluated up front.</b> §4.2 builds a dependency DAG and
 * a topological order at compile time; this does the same work on demand, which has two properties
 * worth having: a variable no component reaches is never evaluated (so it costs no fuel), and the
 * dependency order falls out of the recursion rather than needing to be computed and kept in step
 * with it.
 *
 * <p>Memoisation is per entity, which is required rather than an optimisation: a MEMBER-scope
 * variable means something different for each member, and the cache is discarded with the entity's
 * context. Within one entity a variable is evaluated at most once, so a reference appearing in a
 * rule's {@code check}, its {@code measure} and its {@code outputs} costs one evaluation, not
 * three.
 *
 * <p>A cycle is an engine-level error here. §4.2's cycle detection is a <em>compile-time</em>
 * diagnostic, so a cyclic policy should never reach evaluation; the guard exists because silently
 * recursing would be a stack overflow in production rather than a decision.
 */
public final class VariableResolver {

  private final Map<String, String> expressions;
  private final Map<String, Value> resolved = new HashMap<>();
  private final Set<String> inProgress = new LinkedHashSet<>();

  /**
   * Creates a resolver over a policy's variable declarations.
   *
   * @param expressions variable name to BEL expression
   */
  public VariableResolver(Map<String, String> expressions) {
    this.expressions = Map.copyOf(expressions);
  }

  /** A resolver for a policy that declares no variables. */
  public static VariableResolver none() {
    return new VariableResolver(Map.of());
  }

  /**
   * Returns whether a name is a declared variable.
   *
   * @param name the identifier
   * @return true when the policy declares a variable of that name
   */
  public boolean declares(String name) {
    return expressions.containsKey(name);
  }

  /**
   * Resolves a variable, evaluating it at most once per entity.
   *
   * @param name the variable's name
   * @param context the context to evaluate in
   * @param evaluate evaluates a BEL expression in a context
   * @return the variable's value, possibly {@link Value.Missing}
   * @throws EvalException {@link EvalErrorCode#EVAL_INTERNAL} on a dependency cycle, which the
   *     compiler should have rejected (§4.2)
   */
  public Value resolve(
      String name,
      EvaluationContext context,
      BiFunction<String, EvaluationContext, Value> evaluate) {
    Value cached = resolved.get(name);
    if (cached != null) {
      return cached;
    }
    if (!inProgress.add(name)) {
      throw new EvalException(
          EvalErrorCode.EVAL_INTERNAL,
          String.join(" -> ", inProgress) + " -> " + name,
          "VariableCycle");
    }
    try {
      Value value = evaluate.apply(expressions.get(name), context);
      resolved.put(name, value);
      return value;
    } finally {
      inProgress.remove(name);
    }
  }

  /** Returns a fresh resolver over the same declarations, with an empty cache. */
  public VariableResolver forNewEntity() {
    return new VariableResolver(expressions);
  }
}
