package dev.bre.engine.ir;

import dev.bre.engine.bel.ast.Expr;
import dev.bre.engine.bel.parse.Parser;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The policy's dependency graph and its topological order (LLD §4.2, pipeline stage 6).
 *
 * <p>Nodes are the policy's variables, the components referenced from other components' results,
 * and the synthetic node {@code settings.authorityAmount}. There is an edge {@code u -> v} when
 * {@code v}'s expressions reference {@code u}.
 *
 * <p><b>Why this exists when evaluation already works without it.</b> {@code VariableResolver}
 * resolves variables lazily and detects a cycle when it walks into one, which is correct but
 * happens <em>during a decision</em> — on the first application unlucky enough to touch the cyclic
 * variable, in production, as a runtime error on a real applicant. §4.2 puts cycle detection at
 * compile time, which is where it belongs: a cyclic policy is not a policy that sometimes fails, it
 * is a policy that cannot be approved. This class is what lets {@code BEL-015} be raised when the
 * policy is authored.
 *
 * <p>The order it produces is also the deterministic one. Lazy resolution evaluates in whatever
 * order the components happen to ask, which gives the same answers — variables are pure — but not
 * the same fuel accounting if a budget runs out part way. A declared order makes that reproducible,
 * which replay (D8) depends on.
 *
 * <p>Kahn's algorithm with a stable node order, so the output of two runs over the same policy is
 * identical rather than merely equivalent.
 */
public final class DependencyGraph {

  /** The synthetic node for {@code settings.authorityAmount} (§4.2). */
  public static final String AUTHORITY_AMOUNT_NODE = "settings.authorityAmount";

  private final Map<String, Set<String>> dependsOn;
  private final List<String> topologicalOrder;
  private final Optional<List<String>> cycle;

  private DependencyGraph(
      Map<String, Set<String>> dependsOn,
      List<String> topologicalOrder,
      Optional<List<String>> cycle) {
    this.dependsOn = dependsOn;
    this.topologicalOrder = topologicalOrder;
    this.cycle = cycle;
  }

  /**
   * Builds the graph for a policy.
   *
   * @param policy the policy IR
   * @return the graph, carrying either a topological order or the cycle that prevents one
   */
  public static DependencyGraph of(Ir.PolicyIr policy) {
    Set<String> variables = new LinkedHashSet<>();
    policy.variables().forEach(variable -> variables.add(variable.name()));

    Set<String> components = new LinkedHashSet<>();
    policy.rules().forEach(rule -> components.add(rule.code()));
    policy.decisionTables().forEach(table -> components.add(table.code()));
    policy.scorecards().forEach(scorecard -> components.add(scorecard.code()));

    // Every node, in declaration order: variables, then components, then the synthetic node.
    // A LinkedHashMap rather than a HashMap throughout, so the order below is the declared one.
    Map<String, Set<String>> edges = new LinkedHashMap<>();
    variables.forEach(name -> edges.put(name, new LinkedHashSet<>()));
    components.forEach(code -> edges.put(code, new LinkedHashSet<>()));

    for (Ir.VariableDef variable : policy.variables()) {
      edges.get(variable.name()).addAll(referencesIn(variable.expr(), variables, components));
    }
    for (Ir.RuleDef rule : policy.rules()) {
      Set<String> to = edges.get(rule.code());
      rule.when().ifPresent(expr -> to.addAll(referencesIn(expr, variables, components)));
      to.addAll(referencesIn(rule.check(), variables, components));
      rule.onFail().measure().ifPresent(e -> to.addAll(referencesIn(e, variables, components)));
      rule.outputs().values().forEach(e -> to.addAll(referencesIn(e, variables, components)));
    }
    for (Ir.DecisionTableDef table : policy.decisionTables()) {
      Set<String> to = edges.get(table.code());
      table.when().ifPresent(expr -> to.addAll(referencesIn(expr, variables, components)));
      table.inputs().forEach(in -> to.addAll(referencesIn(in.expr(), variables, components)));
      for (Ir.TableRow row : table.rows()) {
        row.then().measure().ifPresent(e -> to.addAll(referencesIn(e, variables, components)));
        row.then()
            .outputs()
            .values()
            .forEach(e -> to.addAll(referencesIn(e, variables, components)));
        // Row cells are unary tests, not expressions, and are parsed by a different parser. They
        // can reference a variable, but doing so is BEL-027 territory and the binder's business;
        // the graph does not need them because a cell cannot introduce an edge a column does not.
      }
    }
    for (Ir.ScorecardDef scorecard : policy.scorecards()) {
      Set<String> to = edges.get(scorecard.code());
      scorecard
          .characteristics()
          .forEach(c -> to.addAll(referencesIn(c.expr(), variables, components)));
    }

    policy
        .authorityAmount()
        .ifPresent(
            expr -> {
              edges.put(
                  AUTHORITY_AMOUNT_NODE,
                  new LinkedHashSet<>(referencesIn(expr, variables, components)));
            });

    Map<String, Set<String>> immutable = new LinkedHashMap<>();
    edges.forEach((node, to) -> immutable.put(node, Set.copyOf(to)));

    Optional<List<String>> cycle = findCycle(immutable);
    List<String> order = cycle.isPresent() ? List.of() : kahn(immutable);
    return new DependencyGraph(Map.copyOf(immutable), order, cycle);
  }

  /**
   * The identifiers an expression references that are nodes of this graph.
   *
   * <p>A reference to a fact root, a parameter or a reference list is not an edge: those are inputs
   * to the whole decision, not nodes that have to be ordered. A name that is neither a node nor a
   * namespace is the binder's problem (BEL-002), not the graph's — building the graph must not fail
   * on a policy that has a different, more specific error to report.
   */
  private static Set<String> referencesIn(
      String expression, Set<String> variables, Set<String> components) {
    Expr parsed;
    try {
      parsed = Parser.parseExpression(expression);
    } catch (RuntimeException syntaxError) {
      // A syntax error is BEL-001 and is reported by the parse stage, which runs before this one.
      // Returning no edges keeps the graph usable for the rest of the policy's diagnostics.
      return Set.of();
    }
    Set<String> found = new LinkedHashSet<>();
    collect(parsed, variables, components, found);
    return found;
  }

  /**
   * Walks an expression and collects the graph nodes it references.
   *
   * <p>An exhaustive switch over the sealed {@code Expr} hierarchy rather than a generic child
   * walk, so that adding an expression form is a compile error here — with {@code -Werror} a new
   * variant cannot be quietly left out of the dependency graph, which would produce a policy whose
   * declared order is wrong in a way nothing detects.
   */
  private static void collect(
      Expr expr, Set<String> variables, Set<String> components, Set<String> found) {
    switch (expr) {
      case Expr.Ref ref -> {
        if (variables.contains(ref.name()) || components.contains(ref.name())) {
          found.add(ref.name());
        }
      }
      case Expr.Field field ->
          // A result reference is `COMPONENT.output`, so the component is the ROOT of the access
          // chain. Walking to the root matters: `MFI_APP_SCORE_V1.score` depends on the scorecard,
          // not on something called `score`.
          collect(field.target(), variables, components, found);
      case Expr.Filter filter -> {
        collect(filter.target(), variables, components, found);
        collect(filter.predicate(), variables, components, found);
      }
      case Expr.Call call ->
          call.arguments().forEach(a -> collect(a, variables, components, found));
      case Expr.ListLit list ->
          list.elements().forEach(e -> collect(e, variables, components, found));
      case Expr.Negate negate -> collect(negate.operand(), variables, components, found);
      case Expr.Not not -> collect(not.operand(), variables, components, found);
      case Expr.Arithmetic arithmetic -> {
        collect(arithmetic.left(), variables, components, found);
        collect(arithmetic.right(), variables, components, found);
      }
      case Expr.Comparison comparison -> {
        collect(comparison.left(), variables, components, found);
        collect(comparison.right(), variables, components, found);
      }
      case Expr.Logical logical -> {
        collect(logical.left(), variables, components, found);
        collect(logical.right(), variables, components, found);
      }
      case Expr.In in -> {
        collect(in.value(), variables, components, found);
        collect(in.target(), variables, components, found);
      }
      case Expr.Between between -> {
        collect(between.value(), variables, components, found);
        collect(between.low(), variables, components, found);
        collect(between.high(), variables, components, found);
      }
      case Expr.If conditional -> {
        collect(conditional.condition(), variables, components, found);
        collect(conditional.thenBranch(), variables, components, found);
        collect(conditional.elseBranch(), variables, components, found);
      }
      case Expr.Range range -> {
        collect(range.low(), variables, components, found);
        collect(range.high(), variables, components, found);
      }
      case Expr.IntLit ignored -> {
        // A literal references nothing.
      }
      case Expr.DecimalLit ignored -> {
        // A literal references nothing.
      }
      case Expr.StringLit ignored -> {
        // A literal references nothing.
      }
      case Expr.BoolLit ignored -> {
        // A literal references nothing.
      }
    }
  }

  /** Depth-first search for a cycle, returning the path that forms it. */
  private static Optional<List<String>> findCycle(Map<String, Set<String>> edges) {
    Set<String> done = new LinkedHashSet<>();
    Set<String> onPath = new LinkedHashSet<>();
    Deque<String> path = new ArrayDeque<>();

    for (String node : edges.keySet()) {
      Optional<List<String>> found = visit(node, edges, done, onPath, path);
      if (found.isPresent()) {
        return found;
      }
    }
    return Optional.empty();
  }

  private static Optional<List<String>> visit(
      String node,
      Map<String, Set<String>> edges,
      Set<String> done,
      Set<String> onPath,
      Deque<String> path) {

    if (done.contains(node)) {
      return Optional.empty();
    }
    if (onPath.contains(node)) {
      // The cycle is the tail of the current path from this node onwards, closed back to it.
      List<String> found = new ArrayList<>();
      boolean started = false;
      for (String step : path) {
        if (step.equals(node)) {
          started = true;
        }
        if (started) {
          found.add(step);
        }
      }
      found.add(node);
      return Optional.of(List.copyOf(found));
    }

    onPath.add(node);
    path.addLast(node);
    for (String next : edges.getOrDefault(node, Set.of())) {
      Optional<List<String>> found = visit(next, edges, done, onPath, path);
      if (found.isPresent()) {
        return found;
      }
    }
    path.removeLast();
    onPath.remove(node);
    done.add(node);
    return Optional.empty();
  }

  /** Kahn's algorithm over nodes in declaration order, so the result is deterministic. */
  private static List<String> kahn(Map<String, Set<String>> edges) {
    Map<String, Integer> remaining = new LinkedHashMap<>();
    edges.forEach((node, to) -> remaining.put(node, 0));
    edges.forEach(
        (node, to) -> to.forEach(dep -> remaining.computeIfPresent(node, (k, v) -> v + 1)));

    // A dependency the graph does not hold as a node (a fact root, say) is not counted above
    // because `to` only ever contains nodes — referencesIn filters to them.
    Deque<String> ready = new ArrayDeque<>();
    remaining.forEach(
        (node, count) -> {
          if (count == 0) {
            ready.addLast(node);
          }
        });

    List<String> order = new ArrayList<>(edges.size());
    while (!ready.isEmpty()) {
      String node = ready.removeFirst();
      order.add(node);
      // Anything that depended on this node loses one outstanding dependency.
      edges.forEach(
          (other, to) -> {
            if (to.contains(node)) {
              int left = remaining.merge(other, -1, Integer::sum);
              if (left == 0) {
                ready.addLast(other);
              }
            }
          });
    }
    return List.copyOf(order);
  }

  /**
   * The evaluation order: every node appears after everything it depends on.
   *
   * @return the order, or an empty list when the graph has a cycle
   */
  public List<String> topologicalOrder() {
    return topologicalOrder;
  }

  /**
   * The cycle, when the graph has one.
   *
   * @return the cycle as a path that returns to its first node, for {@code BEL-015}'s "{cycle
   *     path}"; empty when the graph is acyclic
   */
  public Optional<List<String>> cycle() {
    return cycle;
  }

  /**
   * Whether the graph is acyclic and therefore evaluable.
   *
   * @return true when there is no cycle
   */
  public boolean isAcyclic() {
    return cycle.isEmpty();
  }

  /**
   * What a node directly depends on.
   *
   * @param node the node
   * @return its direct dependencies, empty for an unknown node
   */
  public Set<String> dependenciesOf(String node) {
    return dependsOn.getOrDefault(node, Set.of());
  }

  /**
   * Every node in the graph, in declaration order.
   *
   * @return the nodes
   */
  public Set<String> nodes() {
    return dependsOn.keySet();
  }
}
