package dev.bre.engine.bel.check;

import dev.bre.engine.bel.ast.Expr;
import dev.bre.engine.bel.lex.Token;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The binder and type checker — stages 4 and 5 of the compile pipeline (LLD §4.1).
 *
 * <p><b>What this buys.</b> Without it, {@code application.purpose >= 650} compiles and then
 * produces a runtime {@code ERROR} on the first application that reaches the rule — which, being
 * fail-closed, refers a borrower for a defect in the policy. The same expression checked here is a
 * diagnostic in the author's editor before the version is ever submitted. Every error this class
 * reports is a decision it stops being wrong in production.
 *
 * <p><b>It collects rather than throws.</b> An author fixing a policy wants the whole list, so a
 * mistyped comparison does not hide the four other problems in the same expression. The checker
 * descends past an error wherever it can give the subtree a type anyway.
 *
 * <p><b>Three rules here are judgement, not mechanics, and each is written down where it is
 * enforced:</b>
 *
 * <ul>
 *   <li><b>{@code Integer} and {@code Decimal} interoperate</b> (§3.6). {@code tenureMonths > 12.5}
 *       is meaningful, and making it a type error would push authors into writing casts that the
 *       language does not have.
 *   <li><b>{@code Text} has no ordering</b> (BEL-010). Collation is locale-dependent, and a credit
 *       rule whose outcome depends on the server's locale is not reproducible — which D8 forbids
 *       outright, so this is a determinism rule wearing a type system's clothes.
 *   <li><b>A {@code protected} field needs an approved override</b> (BEL-017), and an {@code
 *       aadhaar} field can never be read (BEL-026). These are the checks that stop a lending
 *       decision turning on gender, religion or caste by accident. The override makes using one a
 *       recorded, approved act rather than a line of code nobody reviewed.
 * </ul>
 */
public final class TypeChecker {

  /**
   * The closed function registry's static signatures (§3.9).
   *
   * <p>Closed on purpose (00 §3.5 Safety: "Functions come from a closed registry only"), and this
   * is the list the evaluator actually implements — kept in step by a test that compares the two,
   * because a function the checker knows and the evaluator does not is a policy that compiles and
   * then fails at runtime.
   */
  private static final Map<String, Signature> FUNCTIONS = signatures();

  /** Splits a dotted fact path. See {@link #asElementPath}. */
  private static final java.util.regex.Pattern DOT = java.util.regex.Pattern.compile("\\.");

  private final SymbolTable symbols;
  private final boolean hasGovernanceOverride;
  private final List<Diagnostic> diagnostics = new ArrayList<>();
  private BelType itType;

  private TypeChecker(SymbolTable symbols, boolean hasGovernanceOverride) {
    this.symbols = symbols;
    this.hasGovernanceOverride = hasGovernanceOverride;
  }

  /**
   * The result of checking one expression.
   *
   * @param type the expression's type, empty when a diagnostic prevented inferring one
   * @param diagnostics every problem found, in source order
   */
  public record Result(Optional<BelType> type, List<Diagnostic> diagnostics) {

    /** Creates a result, copying the list. */
    public Result {
      diagnostics = List.copyOf(diagnostics);
    }

    /** Whether the expression is free of diagnostics. */
    public boolean isClean() {
      return diagnostics.isEmpty();
    }
  }

  /**
   * Checks an expression.
   *
   * @param expr the parsed expression
   * @param symbols what the names mean
   * @return the inferred type and every diagnostic
   */
  public static Result check(Expr expr, SymbolTable symbols) {
    return check(expr, symbols, false);
  }

  /**
   * Checks an expression belonging to a component that carries a governance override.
   *
   * @param expr the parsed expression
   * @param symbols what the names mean
   * @param hasApprovedOverride whether the component carries an APPROVED {@code
   *     governance.overrideRef}, which permits reading a {@code protected} field (BEL-017). It
   *     never permits reading an {@code aadhaar} field (BEL-026).
   * @return the inferred type and every diagnostic
   */
  public static Result check(Expr expr, SymbolTable symbols, boolean hasApprovedOverride) {
    TypeChecker checker = new TypeChecker(symbols, hasApprovedOverride);
    Optional<BelType> type = checker.typeOf(expr);
    return new Result(type, checker.diagnostics);
  }

  /**
   * Checks an expression that must be Boolean — a rule's {@code check} or {@code when}.
   *
   * @param expr the parsed expression
   * @param symbols what the names mean
   * @param role what the expression is, for the message
   * @param hasApprovedOverride whether a governance override applies
   * @return every diagnostic, including a BEL-003 when the expression is not Boolean
   */
  public static List<Diagnostic> checkBoolean(
      Expr expr, SymbolTable symbols, String role, boolean hasApprovedOverride) {
    TypeChecker checker = new TypeChecker(symbols, hasApprovedOverride);
    Optional<BelType> type = checker.typeOf(expr);
    type.ifPresent(
        actual -> {
          if (actual != BelType.BOOLEAN) {
            checker.report(
                Diagnostic.TYPE_MISMATCH,
                tokenOf(expr),
                "a " + role + " must be Boolean, got " + actual);
          }
        });
    return List.copyOf(checker.diagnostics);
  }

  // --- the walk ---------------------------------------------------------------------------------

  private Optional<BelType> typeOf(Expr expr) {
    return switch (expr) {
      case Expr.IntLit ignored -> Optional.of(BelType.INTEGER);
      case Expr.DecimalLit ignored -> Optional.of(BelType.DECIMAL);
      case Expr.StringLit ignored -> Optional.of(BelType.TEXT);
      case Expr.BoolLit ignored -> Optional.of(BelType.BOOLEAN);
      case Expr.Ref ref -> typeOfRef(ref);
      case Expr.Field field -> typeOfField(field);
      case Expr.Filter filter -> typeOfFilter(filter);
      case Expr.Call call -> typeOfCall(call);
      case Expr.ListLit list -> typeOfListLiteral(list);
      case Expr.Negate negate -> typeOfNegate(negate);
      case Expr.Not not -> typeOfNot(not);
      case Expr.Arithmetic arithmetic -> typeOfArithmetic(arithmetic);
      case Expr.Comparison comparison -> typeOfComparison(comparison);
      case Expr.Logical logical -> typeOfLogical(logical);
      case Expr.In in -> typeOfIn(in);
      case Expr.Between between -> typeOfBetween(between);
      case Expr.If conditional -> typeOfIf(conditional);
      case Expr.Range range -> typeOfRange(range);
    };
  }

  private Optional<BelType> typeOfRef(Expr.Ref ref) {
    String name = ref.name();

    if ("it".equals(name)) {
      if (itType == null) {
        report(Diagnostic.IT_OUTSIDE_FILTER, ref.token(), "'it' used outside a filter");
        return Optional.empty();
      }
      return Optional.of(itType);
    }

    Optional<BelType> variable = symbols.variableType(name);
    if (variable.isPresent()) {
      return variable;
    }
    // A bare identifier that is not a variable is the root of a fact path or a namespace; the
    // Field case resolves it with its children. Reaching here means it stood alone.
    Optional<BelType> fact = symbols.factPathType(name);
    if (fact.isPresent()) {
      return guardedFact(name, fact.get(), ref.token());
    }
    if ("param".equals(name) || "list".equals(name)) {
      report(
          Diagnostic.UNKNOWN_IDENTIFIER,
          ref.token(),
          "'" + name + "' is a namespace and needs a name after it");
      return Optional.empty();
    }
    report(Diagnostic.UNKNOWN_IDENTIFIER, ref.token(), "unknown identifier '" + name + "'");
    return Optional.empty();
  }

  private Optional<BelType> typeOfField(Expr.Field field) {
    // A path is resolved whole rather than a field at a time: `param.X` is one name, and a fact
    // path's type lives on the leaf, not on each segment.
    Optional<String> path = pathOf(field);
    if (path.isPresent()) {
      return typeOfPath(path.get(), field.token());
    }

    // Not a plain path — a projection over a filter or a call, e.g. `xs[p].f`.
    Optional<BelType> target = typeOf(field.target());
    if (target.isEmpty()) {
      return Optional.empty();
    }
    if (target.get().isList()) {
      // A projection over a list of records: the element type is the field's, and the result is a
      // list of it. Without a per-field view of a record the element type is not knowable here, so
      // the projection is left untyped rather than guessed — a guess would mask a real error.
      return Optional.empty();
    }
    if (target.get() != BelType.RECORD) {
      report(
          Diagnostic.TYPE_MISMATCH,
          field.token(),
          "'." + field.name() + "' needs a record or a list of records, got " + target.get());
    }
    return Optional.empty();
  }

  private Optional<BelType> typeOfPath(String path, Token token) {
    if (path.startsWith("param.")) {
      String name = path.substring("param.".length());
      Optional<BelType> type = symbols.parameterType(name);
      if (type.isEmpty()) {
        report(Diagnostic.UNKNOWN_PARAMETER, token, "unknown parameter 'param." + name + "'");
      }
      return type;
    }
    if (path.startsWith("list.")) {
      String name = path.substring("list.".length());
      Optional<BelType> type = symbols.referenceListType(name);
      if (type.isEmpty()) {
        report(
            Diagnostic.UNKNOWN_REFERENCE_LIST, token, "unknown reference list 'list." + name + "'");
      }
      return type;
    }

    // A result reference, COMPONENT.output, before a fact path: a component code is upper-case by
    // the schema's `code` pattern, and no fact root is.
    int dot = path.indexOf('.');
    if (dot > 0) {
      String head = path.substring(0, dot);
      if (head.equals(head.toUpperCase(java.util.Locale.ROOT)) && head.length() > 1) {
        Optional<BelType> output = symbols.componentOutputType(head, path.substring(dot + 1));
        if (output.isPresent()) {
          return output;
        }
      }
    }

    Optional<BelType> fact = symbols.factPathType(path);
    if (fact.isEmpty()) {
      // Also try the list-element spelling: an author writes `parties.income.monthlyNet` for a
      // projection over a list, and the schema declares `parties[].income.monthlyNet`.
      Optional<String> elementPath = asElementPath(path);
      if (elementPath.isPresent()) {
        Optional<BelType> projected = symbols.factPathType(elementPath.get());
        if (projected.isPresent()) {
          Optional<BelType> guarded = guardedFact(elementPath.get(), projected.get(), token);
          // A projection over a list yields a list of the field's type.
          return guarded.flatMap(BelType::listOf);
        }
      }
      report(Diagnostic.UNKNOWN_FACT_PATH, token, "unknown fact path '" + path + "'");
      return Optional.empty();
    }
    return guardedFact(path, fact.get(), token);
  }

  /**
   * Applies the §9.1 read restrictions to a fact path.
   *
   * <p>Order matters: {@code aadhaar} is checked first and has no override, because a field that is
   * both would otherwise be readable with an override it must never have.
   */
  private Optional<BelType> guardedFact(String path, BelType type, Token token) {
    if (symbols.isAadhaar(path)) {
      report(
          Diagnostic.AADHAAR_FIELD,
          token,
          "field '" + path + "' is tagged aadhaar and may not be read by rule logic");
      return Optional.empty();
    }
    if (symbols.isProtected(path) && !hasGovernanceOverride) {
      report(
          Diagnostic.PROTECTED_FIELD,
          token,
          "field '"
              + path
              + "' is protected and needs an approved governance.overrideRef to be read");
      return Optional.empty();
    }
    return Optional.of(type);
  }

  private Optional<BelType> typeOfFilter(Expr.Filter filter) {
    Optional<BelType> target = typeOf(filter.target());
    if (target.isEmpty()) {
      return Optional.empty();
    }
    if (!target.get().isList()) {
      report(
          Diagnostic.FILTER_ON_NON_LIST,
          filter.token(),
          "a filter needs a list, got " + target.get());
      return Optional.empty();
    }

    // `it` is bound to the element type for the predicate's extent only.
    BelType outerIt = itType;
    itType = target.get().elementType().orElse(BelType.RECORD);
    try {
      Optional<BelType> predicate = typeOf(filter.predicate());
      predicate.ifPresent(
          actual -> {
            if (actual != BelType.BOOLEAN) {
              report(
                  Diagnostic.FILTER_PREDICATE_NOT_BOOLEAN,
                  filter.token(),
                  "a filter predicate must be Boolean, got " + actual);
            }
          });
    } finally {
      itType = outerIt;
    }
    return target;
  }

  private Optional<BelType> typeOfCall(Expr.Call call) {
    Signature signature = FUNCTIONS.get(call.function());
    if (signature == null) {
      report(
          Diagnostic.UNKNOWN_FUNCTION,
          call.token(),
          "unknown function '"
              + call.function()
              + "'; BEL has a closed function registry and does not accept user-defined functions");
      call.arguments().forEach(this::typeOf);
      return Optional.empty();
    }

    List<Optional<BelType>> argumentTypes = new ArrayList<>(call.arguments().size());
    for (Expr argument : call.arguments()) {
      argumentTypes.add(typeOf(argument));
    }

    if (!signature.acceptsArity(call.arguments().size())) {
      report(
          Diagnostic.WRONG_ARITY,
          call.token(),
          "function '"
              + call.function()
              + "' expects "
              + signature.arityDescription()
              + " arguments, got "
              + call.arguments().size());
      return signature.resultOf(argumentTypes);
    }
    signature.checkArguments(call, argumentTypes, this);
    return signature.resultOf(argumentTypes);
  }

  /**
   * A list literal's type is the list of its common element type (BEL-024).
   *
   * <p>A mixed literal is an error rather than a list of some union: {@code status in ["ACTIVE",
   * 1]} is a mistake, and typing it loosely would let it reach runtime, where the comparison would
   * silently fail to match instead of saying so.
   */
  private Optional<BelType> typeOfListLiteral(Expr.ListLit list) {
    List<Optional<BelType>> elements = new ArrayList<>(list.elements().size());
    for (Expr element : list.elements()) {
      elements.add(typeOf(element));
    }
    if (elements.isEmpty()) {
      // An empty literal has no element type to infer. `x in []` is always FALSE and legal, so
      // this is not an error — it simply types as nothing the caller can check against.
      return Optional.empty();
    }
    Optional<BelType> common = commonType(elements);
    if (common.isEmpty()) {
      if (elements.stream().allMatch(Optional::isPresent)) {
        report(
            Diagnostic.LIST_ELEMENT_MISMATCH,
            list.token(),
            "a list literal needs elements of one type, got "
                + elements.stream().map(e -> e.orElseThrow().wire()).distinct().toList());
      }
      return Optional.empty();
    }
    return common.flatMap(BelType::listOf);
  }

  private Optional<BelType> typeOfNegate(Expr.Negate negate) {
    Optional<BelType> operand = typeOf(negate.operand());
    operand.ifPresent(
        type -> {
          if (!type.isNumeric()) {
            report(Diagnostic.TYPE_MISMATCH, negate.token(), "'-' expects a number, got " + type);
          }
        });
    return operand.filter(BelType::isNumeric);
  }

  private Optional<BelType> typeOfNot(Expr.Not not) {
    Optional<BelType> operand = typeOf(not.operand());
    operand.ifPresent(
        type -> {
          if (type != BelType.BOOLEAN) {
            report(Diagnostic.TYPE_MISMATCH, not.token(), "'not' expects Boolean, got " + type);
          }
        });
    return Optional.of(BelType.BOOLEAN);
  }

  private Optional<BelType> typeOfArithmetic(Expr.Arithmetic arithmetic) {
    Optional<BelType> left = typeOf(arithmetic.left());
    Optional<BelType> right = typeOf(arithmetic.right());
    if (left.isEmpty() || right.isEmpty()) {
      return Optional.empty();
    }
    Optional<BelType> result = BelType.arithmeticResult(left.get(), right.get());
    if (result.isEmpty()) {
      report(
          Diagnostic.TYPE_MISMATCH,
          arithmetic.token(),
          "'"
              + symbolOf(arithmetic.op())
              + "' expects numbers, got "
              + left.get()
              + " and "
              + right.get());
      return Optional.empty();
    }
    // §3.6: division always yields a Decimal, even over two Integers, because 1/3 is not 0.
    if (arithmetic.op() == Expr.ArithmeticOp.DIVIDE) {
      return Optional.of(BelType.DECIMAL);
    }
    return result;
  }

  private Optional<BelType> typeOfComparison(Expr.Comparison comparison) {
    Optional<BelType> left = typeOf(comparison.left());
    Optional<BelType> right = typeOf(comparison.right());
    if (left.isEmpty() || right.isEmpty()) {
      return Optional.of(BelType.BOOLEAN);
    }

    BelType a = left.get();
    BelType b = right.get();
    boolean ordering = comparison.op() != Expr.CompareOp.EQ && comparison.op() != Expr.CompareOp.NE;

    // A Date and a DateTime are never comparable, in either direction: §3.7 makes the author
    // convert with toDate(), because comparing an instant with a calendar date silently depends on
    // a time zone nobody chose.
    if ((a == BelType.DATE && b == BelType.DATE_TIME)
        || (a == BelType.DATE_TIME && b == BelType.DATE)) {
      report(
          Diagnostic.DATE_AND_DATETIME,
          comparison.token(),
          "Date and DateTime are not comparable; convert one of them first");
      return Optional.of(BelType.BOOLEAN);
    }

    if (ordering && !a.isOrdered()) {
      report(
          Diagnostic.ORDERING_NOT_ALLOWED,
          comparison.token(),
          "ordering comparison on " + a + " is not allowed");
      return Optional.of(BelType.BOOLEAN);
    }
    if (!a.isComparableWith(b)) {
      report(
          Diagnostic.TYPE_MISMATCH,
          comparison.token(),
          "'" + symbolOf(comparison.op()) + "' cannot compare " + a + " with " + b);
      return Optional.of(BelType.BOOLEAN);
    }

    // BEL-011: a text literal compared with an enum-typed field must be one of its values.
    checkEnumLiteral(comparison.left(), comparison.right());
    checkEnumLiteral(comparison.right(), comparison.left());
    return Optional.of(BelType.BOOLEAN);
  }

  private void checkEnumLiteral(Expr fieldSide, Expr literalSide) {
    if (!(literalSide instanceof Expr.StringLit literal)) {
      return;
    }
    Optional<String> path =
        fieldSide instanceof Expr.Field field ? pathOf(field) : Optional.empty();
    if (path.isEmpty()) {
      return;
    }
    Set<String> permitted = symbols.enumValues(path.get());
    if (permitted.isEmpty() || permitted.contains(literal.value())) {
      return;
    }
    report(
        Diagnostic.NOT_AN_ENUM_VALUE,
        literal.token(),
        "'"
            + literal.value()
            + "' is not a permitted value of '"
            + path.get()
            + "'; expected one of "
            + String.join(", ", new java.util.TreeSet<>(permitted)));
  }

  /** BEL-011 where the enum field is the list side of an {@code in}. */
  private void checkEnumLiteralAgainstList(Expr listSide, Expr literalSide) {
    if (!(literalSide instanceof Expr.StringLit literal)
        || !(listSide instanceof Expr.Field field)) {
      return;
    }
    Optional<String> path = pathOf(field);
    if (path.isEmpty()) {
      return;
    }
    // The author wrote the container path; the enum values are declared on the element.
    Set<String> permitted = symbols.enumValues(path.get());
    if (permitted.isEmpty()) {
      permitted = asElementPath(path.get()).map(symbols::enumValues).orElse(Set.of());
    }
    if (permitted.isEmpty() || permitted.contains(literal.value())) {
      return;
    }
    report(
        Diagnostic.NOT_AN_ENUM_VALUE,
        literal.token(),
        "'"
            + literal.value()
            + "' is not a permitted value of '"
            + path.get()
            + "'; expected one of "
            + String.join(", ", new java.util.TreeSet<>(permitted)));
  }

  private Optional<BelType> typeOfLogical(Expr.Logical logical) {
    for (Expr side : List.of(logical.left(), logical.right())) {
      typeOf(side)
          .ifPresent(
              type -> {
                if (type != BelType.BOOLEAN) {
                  report(
                      Diagnostic.TYPE_MISMATCH,
                      logical.token(),
                      "'"
                          + (logical.op() == Expr.LogicalOp.AND ? "and" : "or")
                          + "' expects Boolean, got "
                          + type);
                }
              });
    }
    return Optional.of(BelType.BOOLEAN);
  }

  private Optional<BelType> typeOfIn(Expr.In in) {
    Optional<BelType> value = typeOf(in.value());
    Optional<BelType> target = typeOf(in.target());
    if (value.isEmpty() || target.isEmpty()) {
      return Optional.of(BelType.BOOLEAN);
    }
    if (!target.get().isList()) {
      report(
          Diagnostic.TYPE_MISMATCH,
          in.token(),
          "'in' needs a list on its right, got " + target.get());
      return Optional.of(BelType.BOOLEAN);
    }
    BelType element = target.get().elementType().orElse(BelType.RECORD);
    if (!value.get().isComparableWith(element)) {
      report(
          Diagnostic.LIST_ELEMENT_MISMATCH,
          in.token(),
          "list element type " + element + " does not match " + value.get());
      return Optional.of(BelType.BOOLEAN);
    }
    // `"MANAGER" in parties.role` is how a projected enum is tested, so BEL-011 applies here too.
    checkEnumLiteralAgainstList(in.target(), in.value());
    return Optional.of(BelType.BOOLEAN);
  }

  private Optional<BelType> typeOfBetween(Expr.Between between) {
    Optional<BelType> value = typeOf(between.value());
    Optional<BelType> low = typeOf(between.low());
    Optional<BelType> high = typeOf(between.high());

    value.ifPresent(
        type -> {
          if (!type.isOrdered()) {
            report(
                Diagnostic.ORDERING_NOT_ALLOWED,
                between.token(),
                "a range on " + type + " is not allowed");
          }
        });
    for (Optional<BelType> bound : List.of(low, high)) {
      if (value.isPresent() && bound.isPresent() && !value.get().isComparableWith(bound.get())) {
        report(
            Diagnostic.TYPE_MISMATCH,
            between.token(),
            "a range bound of " + bound.get() + " cannot be compared with " + value.get());
      }
    }
    return Optional.of(BelType.BOOLEAN);
  }

  private Optional<BelType> typeOfIf(Expr.If conditional) {
    typeOf(conditional.condition())
        .ifPresent(
            type -> {
              if (type != BelType.BOOLEAN) {
                report(
                    Diagnostic.TYPE_MISMATCH,
                    conditional.token(),
                    "an 'if' condition must be Boolean, got " + type);
              }
            });
    Optional<BelType> thenType = typeOf(conditional.thenBranch());
    Optional<BelType> elseType = typeOf(conditional.elseBranch());
    if (thenType.isEmpty() || elseType.isEmpty()) {
      return thenType.or(() -> elseType);
    }
    if (!thenType.get().isComparableWith(elseType.get())) {
      report(
          Diagnostic.TYPE_MISMATCH,
          conditional.token(),
          "the branches of an 'if' must agree, got " + thenType.get() + " and " + elseType.get());
      return Optional.empty();
    }
    // A mixed Integer/Decimal if widens to Decimal, matching arithmetic.
    return thenType.get() == elseType.get() ? thenType : Optional.of(BelType.DECIMAL);
  }

  private Optional<BelType> typeOfRange(Expr.Range range) {
    typeOf(range.low());
    typeOf(range.high());
    // A range is only ever an `in` target or a unary-test cell; it has no standalone value type.
    return Optional.empty();
  }

  // --- helpers ----------------------------------------------------------------------------------

  /** The dotted path of a plain field-access chain, or empty when the chain has a computed root. */
  private static Optional<String> pathOf(Expr.Field field) {
    StringBuilder path = new StringBuilder(field.name());
    Expr target = field.target();
    while (true) {
      if (target instanceof Expr.Field parent) {
        path.insert(0, parent.name() + ".");
        target = parent.target();
      } else if (target instanceof Expr.Ref ref) {
        path.insert(0, ref.name() + ".");
        return Optional.of(path.toString());
      } else {
        return Optional.empty();
      }
    }
  }

  /**
   * Rewrites an author's projection path into the schema's element path.
   *
   * <p>An author projects over a list by naming it — {@code parties.income.monthlyNet} — and the
   * schema declares the element, {@code parties[].income.monthlyNet}. Every segment that names a
   * list gets a {@code []}, not just the first: {@code parties.bureauReports.tradelines} is {@code
   * parties[].bureauReports[].tradelines}, and three-level projections like that are ordinary in
   * bureau data rather than exotic.
   *
   * <p>Each prefix is asked of the symbol table rather than guessed, so the rewrite follows the
   * schema's actual shape instead of assuming every segment is a list.
   */
  private Optional<String> asElementPath(String path) {
    // A compiled pattern with an explicit limit: String.split drops trailing empty strings, and a
    // path ending in a dot is malformed input the checker should see rather than silently trim.
    String[] segments = DOT.split(path, -1);
    if (segments.length < 2) {
      return Optional.empty();
    }
    StringBuilder rewritten = new StringBuilder(path.length() + 8);
    boolean insertedAny = false;
    for (int i = 0; i < segments.length; i++) {
      if (i > 0) {
        rewritten.append('.');
      }
      rewritten.append(segments[i]);
      if (i == segments.length - 1) {
        break;
      }
      // Is the path so far a list? If so, the author is projecting through it.
      Optional<BelType> prefix = symbols.factPathType(rewritten.toString());
      if (prefix.isPresent() && prefix.get().isList()) {
        rewritten.append("[]");
        insertedAny = true;
      }
    }
    return insertedAny ? Optional.of(rewritten.toString()) : Optional.empty();
  }

  private void report(String code, Token token, String message) {
    int line = token == null ? 1 : token.line();
    int column = token == null ? 1 : token.column();
    diagnostics.add(new Diagnostic(code, line, column, message));
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

  private static String symbolOf(Expr.ArithmeticOp op) {
    return switch (op) {
      case ADD -> "+";
      case SUBTRACT -> "-";
      case MULTIPLY -> "*";
      case DIVIDE -> "/";
    };
  }

  private static String symbolOf(Expr.CompareOp op) {
    return switch (op) {
      case EQ -> "=";
      case NE -> "!=";
      case LT -> "<";
      case LE -> "<=";
      case GT -> ">";
      case GE -> ">=";
    };
  }

  /**
   * Returns the function names the checker knows.
   *
   * @return the closed registry's names
   */
  public static Set<String> knownFunctions() {
    return FUNCTIONS.keySet();
  }

  // --- signatures -------------------------------------------------------------------------------

  /** One function's static signature. */
  private interface Signature {

    /** Whether this many arguments is acceptable. */
    boolean acceptsArity(int count);

    /** The arity, for a message. */
    String arityDescription();

    /** The result type, given the arguments' types. */
    Optional<BelType> resultOf(List<Optional<BelType>> arguments);

    /**
     * Reports anything wrong with the arguments beyond their count.
     *
     * <p>A no-op by default: most functions are fully described by their arity and result type, and
     * only the ones with a real constraint — an aggregate needing a list, {@code round} needing a
     * number — override it. The parameters are unused here precisely because this is the do-nothing
     * case.
     */
    @SuppressWarnings("UnusedVariable")
    default void checkArguments(
        Expr.Call call, List<Optional<BelType>> arguments, TypeChecker checker) {}
  }

  private static Map<String, Signature> signatures() {
    Map<String, Signature> out = new LinkedHashMap<>();

    // isMissing(any) -> Boolean. Total by design: asking whether a value is absent must itself
    // never be absent, which is what makes it the escape hatch from three-valued logic.
    out.put("isMissing", fixed(1, args -> Optional.of(BelType.BOOLEAN)));

    // coalesce(a, b, ...) -> the common type of its arguments.
    out.put(
        "coalesce",
        new Signature() {
          @Override
          public boolean acceptsArity(int count) {
            return count >= 1;
          }

          @Override
          public String arityDescription() {
            return "at least 1";
          }

          @Override
          public Optional<BelType> resultOf(List<Optional<BelType>> arguments) {
            return commonType(arguments);
          }

          @Override
          public void checkArguments(
              Expr.Call call, List<Optional<BelType>> arguments, TypeChecker checker) {
            if (commonType(arguments).isEmpty()
                && arguments.stream().allMatch(Optional::isPresent)) {
              checker.report(
                  Diagnostic.TYPE_MISMATCH,
                  call.token(),
                  "coalesce needs arguments of one type, got "
                      + arguments.stream().map(a -> a.orElseThrow().wire()).toList());
            }
          }
        });

    out.put("round", numeric(args -> Optional.of(BelType.DECIMAL), 2, 3));
    out.put("abs", numeric(args -> first(args), 1, 1));

    // Aggregates over a list. sum/avg yield Decimal; min/max/first yield the element type.
    out.put("sum", aggregate(args -> Optional.of(BelType.DECIMAL)));
    out.put("avg", aggregate(args -> Optional.of(BelType.DECIMAL)));
    out.put("min", aggregate(TypeChecker::elementOfFirst));
    out.put("max", aggregate(TypeChecker::elementOfFirst));
    out.put("first", aggregate(TypeChecker::elementOfFirst));
    out.put("count", aggregate(args -> Optional.of(BelType.INTEGER)));
    out.put("distinctCount", aggregate(args -> Optional.of(BelType.INTEGER)));

    // any(list, predicate) / all(list, predicate) -> Boolean.
    out.put("any", fixed(2, args -> Optional.of(BelType.BOOLEAN)));
    out.put("all", fixed(2, args -> Optional.of(BelType.BOOLEAN)));

    // Dates. §3.7: there is no clock, so every one of these takes its reference date explicitly.
    out.put("date", fixed(1, args -> Optional.of(BelType.DATE)));
    out.put("ageInYears", fixed(2, args -> Optional.of(BelType.INTEGER)));
    out.put("monthsBetween", fixed(2, args -> Optional.of(BelType.INTEGER)));
    out.put("daysBetween", fixed(2, args -> Optional.of(BelType.INTEGER)));
    out.put("addMonths", fixed(2, args -> Optional.of(BelType.DATE)));

    return Map.copyOf(out);
  }

  private static Optional<BelType> first(List<Optional<BelType>> arguments) {
    return arguments.isEmpty() ? Optional.empty() : arguments.get(0);
  }

  private static Optional<BelType> elementOfFirst(List<Optional<BelType>> arguments) {
    return first(arguments).flatMap(BelType::elementType);
  }

  private static Optional<BelType> commonType(List<Optional<BelType>> arguments) {
    BelType common = null;
    for (Optional<BelType> argument : arguments) {
      if (argument.isEmpty()) {
        continue;
      }
      if (common == null) {
        common = argument.get();
      } else if (common != argument.get()) {
        if (!common.isComparableWith(argument.get())) {
          return Optional.empty();
        }
        common = BelType.DECIMAL; // Integer and Decimal widen.
      }
    }
    return Optional.ofNullable(common);
  }

  private static Signature fixed(
      int arity, java.util.function.Function<List<Optional<BelType>>, Optional<BelType>> result) {
    return new Signature() {
      @Override
      public boolean acceptsArity(int count) {
        return count == arity;
      }

      @Override
      public String arityDescription() {
        return String.valueOf(arity);
      }

      @Override
      public Optional<BelType> resultOf(List<Optional<BelType>> arguments) {
        return result.apply(arguments);
      }
    };
  }

  private static Signature numeric(
      java.util.function.Function<List<Optional<BelType>>, Optional<BelType>> result,
      int minArity,
      int maxArity) {
    return new Signature() {
      @Override
      public boolean acceptsArity(int count) {
        return count >= minArity && count <= maxArity;
      }

      @Override
      public String arityDescription() {
        return minArity == maxArity ? String.valueOf(minArity) : minArity + " to " + maxArity;
      }

      @Override
      public Optional<BelType> resultOf(List<Optional<BelType>> arguments) {
        return result.apply(arguments);
      }

      @Override
      public void checkArguments(
          Expr.Call call, List<Optional<BelType>> arguments, TypeChecker checker) {
        if (!arguments.isEmpty()
            && arguments.get(0).isPresent()
            && !arguments.get(0).get().isNumeric()) {
          checker.report(
              Diagnostic.TYPE_MISMATCH,
              call.token(),
              "'" + call.function() + "' expects a number, got " + arguments.get(0).get());
        }
      }
    };
  }

  private static Signature aggregate(
      java.util.function.Function<List<Optional<BelType>>, Optional<BelType>> result) {
    return new Signature() {
      @Override
      public boolean acceptsArity(int count) {
        return count == 1;
      }

      @Override
      public String arityDescription() {
        return "1";
      }

      @Override
      public Optional<BelType> resultOf(List<Optional<BelType>> arguments) {
        return result.apply(arguments);
      }

      @Override
      public void checkArguments(
          Expr.Call call, List<Optional<BelType>> arguments, TypeChecker checker) {
        Optional<BelType> argument = first(arguments);
        if (argument.isPresent() && !argument.get().isList()) {
          checker.report(
              Diagnostic.TYPE_MISMATCH,
              call.token(),
              "'" + call.function() + "' expects a list, got " + argument.get());
        }
      }
    };
  }
}
