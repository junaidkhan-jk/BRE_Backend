package dev.bre.registry.bel;

import dev.bre.engine.bel.BelSyntaxException;
import dev.bre.engine.bel.ast.Expr;
import dev.bre.engine.bel.check.BelType;
import dev.bre.engine.bel.check.Diagnostic;
import dev.bre.engine.bel.check.TypeChecker;
import dev.bre.engine.bel.parse.Parser;
import dev.bre.facts.FactSchema;
import dev.bre.facts.FactSchemaSymbols;
import dev.bre.facts.LendingFacts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The BEL language service the editor needs (TRD §13.3, U4 — M5-02).
 *
 * <p><b>Every part of this already existed and none of it was reachable.</b> The lexer and parser
 * report a line, a column and an offset; the type checker returns a type and a list of diagnostics
 * with their own positions and catalogue codes; the fact schema knows every declared path, its
 * type, its enum values and whether it is protected. All of it ran at compile time, inside a save,
 * and surfaced as a list of strings after the author had finished typing. This exposes it <em>while
 * they type</em>, which is the only point at which a type error is cheap to fix.
 *
 * <p><b>Why a server-side service rather than a parser in the browser.</b> A second BEL parser in
 * TypeScript would be a second answer to "is this expression valid", and the two would diverge
 * first on the case nobody tested — so an author would be told their expression is fine and the
 * submit gate would refuse it, or worse, the reverse. One parser, one type checker, one answer.
 *
 * <p><b>Protected and Aadhaar fields are reported here too</b> (BEL-017, BEL-026). An author
 * reaching for a field they are not allowed to read should find out at the moment they type it, not
 * at submission — and certainly not from a reviewer.
 */
@Service
public class BelLanguageService {

  /** The schema this installation types expressions against. */
  private static final FactSchema SCHEMA = LendingFacts.schema();

  /** How many completions are returned. Beyond this an author should type more, not scroll. */
  public static final int MAX_COMPLETIONS = 200;

  /**
   * One diagnostic, positioned for an editor.
   *
   * @param code the catalogue code, e.g. {@code BEL-003}
   * @param severity {@code ERROR} — every BEL diagnostic is one; a warning would be a static check
   * @param line 1-based, as the lexer reports it
   * @param column 1-based
   * @param message what is wrong, naming the identifier or the types
   */
  public record Marker(String code, String severity, int line, int column, String message) {}

  /**
   * One completion offer.
   *
   * @param label what to insert
   * @param kind {@code FACT}, {@code PARAMETER}, {@code LIST}, {@code VARIABLE}, {@code FUNCTION}
   *     or {@code KEYWORD}
   * @param detail the type, or a signature for a function
   * @param documentation why an author would reach for it, where that is not obvious
   */
  public record Completion(String label, String kind, String detail, String documentation) {}

  /**
   * What checking an expression established.
   *
   * @param type the inferred type, absent when the expression did not parse
   * @param markers every diagnostic, positioned
   * @param ok whether the expression is usable as it stands
   */
  public record Checked(Optional<String> type, List<Marker> markers, boolean ok) {

    /** Creates a result, copying the list. */
    public Checked {
      markers = List.copyOf(markers);
    }
  }

  /**
   * Parses and type-checks one expression.
   *
   * @param expression the BEL source
   * @param parameters parameter names the policy has available, with their declared types
   * @param referenceLists reference list names
   * @param variables the policy's declared variables and their types
   * @return the result
   */
  public Checked check(
      String expression,
      Map<String, String> parameters,
      Map<String, String> referenceLists,
      Map<String, String> variables) {

    if (expression == null || expression.isBlank()) {
      // Not an error. An empty editor is where every expression starts, and reporting a syntax
      // error on it would mean the editor is red before the author has typed anything.
      return new Checked(Optional.empty(), List.of(), true);
    }

    Expr parsed;
    try {
      parsed = Parser.parseExpression(expression);
    } catch (BelSyntaxException syntax) {
      // The parser's own position, not a guess. An editor underlining the wrong token is worse
      // than one underlining nothing, because the author goes looking in the wrong place.
      return new Checked(
          Optional.empty(),
          List.of(
              new Marker("BEL-001", "ERROR", syntax.line(), syntax.column(), syntax.getMessage())),
          false);
    } catch (RuntimeException other) {
      return new Checked(
          Optional.empty(),
          List.of(new Marker("BEL-001", "ERROR", 1, 1, other.getMessage())),
          false);
    }

    FactSchemaSymbols symbols =
        new FactSchemaSymbols(
            SCHEMA, types(parameters), types(referenceLists), types(variables), Map.of());

    TypeChecker.Result result = TypeChecker.check(parsed, symbols);
    List<Marker> markers = new ArrayList<>();
    for (Diagnostic diagnostic : result.diagnostics()) {
      markers.add(
          new Marker(
              diagnostic.code(),
              "ERROR",
              diagnostic.line(),
              diagnostic.column(),
              diagnostic.message()));
    }
    return new Checked(
        result.type().map(BelType::toString), markers, result.diagnostics().isEmpty());
  }

  /**
   * Everything an author can reference, for completion.
   *
   * <p>Fact paths come from the published schema, so the list is exactly what the engine will
   * accept — a mistyped path reads as a missing fact at runtime, handled correctly and silently,
   * which is the one class of mistake an author cannot find by testing the happy path.
   *
   * @param parameters the policy's parameter names and types
   * @param referenceLists its reference list names
   * @param variables its declared variables
   * @return the offers
   */
  public List<Completion> completions(
      Map<String, String> parameters,
      Map<String, String> referenceLists,
      Map<String, String> variables) {

    List<Completion> out = new ArrayList<>();

    SCHEMA
        .fields()
        .forEach(
            (path, field) -> {
              if (out.size() >= MAX_COMPLETIONS) {
                return;
              }
              out.add(
                  new Completion(
                      path,
                      "FACT",
                      field.belType(),
                      field.isProtected()
                          // Offered, with the warning attached. Hiding it would leave an author
                          // guessing why a path they can see in the schema does not work.
                          ? "Protected: reading this in an expression is BEL-017."
                          : field.isMasked() ? "Masked in explanations (§9.4)." : ""));
            });

    parameters.forEach(
        (name, type) ->
            out.add(
                new Completion(
                    "param." + name, "PARAMETER", type, "Effective-dated: resolved at asOf.")));
    referenceLists.forEach(
        (name, type) -> out.add(new Completion("list." + name, "LIST", type, "")));
    variables.forEach(
        (name, type) -> out.add(new Completion(name, "VARIABLE", type, "Declared variable.")));

    for (String function : FUNCTIONS.keySet()) {
      out.add(new Completion(function, "FUNCTION", FUNCTIONS.get(function), ""));
    }
    for (String keyword : KEYWORDS) {
      out.add(new Completion(keyword, "KEYWORD", "", ""));
    }
    return out;
  }

  /**
   * The function signatures, for completion and hover.
   *
   * <p>Written out rather than reflected off the evaluator: a signature is documentation for an
   * author, and the evaluator's internal arity is not always the same shape as the thing a person
   * writes. Where they disagree the conformance vectors are the arbiter, and they cover every one
   * of these.
   */
  private static final Map<String, String> FUNCTIONS = functions();

  private static Map<String, String> functions() {
    Map<String, String> out = new LinkedHashMap<>();
    out.put("ageInYears", "ageInYears(date, asOf) -> Integer");
    out.put("monthsBetween", "monthsBetween(from, to) -> Integer");
    out.put("daysBetween", "daysBetween(from, to) -> Integer");
    out.put("addMonths", "addMonths(date, months) -> Date");
    out.put("addDays", "addDays(date, days) -> Date");
    out.put("sum", "sum(list) -> Decimal");
    out.put("count", "count(list) -> Integer");
    out.put("min", "min(list) -> Decimal");
    out.put("max", "max(list) -> Decimal");
    out.put("avg", "avg(list) -> Decimal");
    out.put("distinctCount", "distinctCount(list) -> Integer");
    out.put("any", "any(list) -> Boolean");
    out.put("all", "all(list) -> Boolean");
    out.put("first", "first(list) -> element");
    out.put("round", "round(decimal, scale, mode) -> Decimal");
    out.put("abs", "abs(number) -> number");
    out.put("coalesce", "coalesce(a, b) -> the first value that is not missing");
    return Map.copyOf(out);
  }

  /** The keywords §3.2's grammar defines. */
  private static final List<String> KEYWORDS =
      List.of(
          "and", "or", "not", "in", "if", "then", "else", "true", "false", "missing", "asOf", "it");

  /** The keyword list, for the editor's tokenizer. */
  public List<String> keywords() {
    return KEYWORDS;
  }

  /** The function names, for the editor's tokenizer. */
  public List<String> functionNames() {
    return List.copyOf(FUNCTIONS.keySet());
  }

  private static Map<String, BelType> types(Map<String, String> declared) {
    Map<String, BelType> out = new LinkedHashMap<>();
    declared.forEach(
        (name, type) -> {
          BelType parsed = belType(type);
          if (parsed != null) {
            out.put(name, parsed);
          }
        });
    return out;
  }

  /**
   * Maps a declared type name onto the checker's type.
   *
   * <p>An unknown name yields null and the symbol is left out, so an expression referencing it
   * reports BEL-002 "unknown identifier" rather than a type error about a type nobody declared.
   * Being told the name is unknown is more use than being told its type does not match.
   */
  private static BelType belType(String name) {
    return switch (name) {
      case "Decimal" -> BelType.DECIMAL;
      case "Integer" -> BelType.INTEGER;
      case "Boolean" -> BelType.BOOLEAN;
      case "Text" -> BelType.TEXT;
      case "Date" -> BelType.DATE;
      case "DateTime" -> BelType.DATE_TIME;
      default -> null;
    };
  }
}
