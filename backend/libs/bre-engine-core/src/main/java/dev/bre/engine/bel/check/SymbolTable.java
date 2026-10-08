package dev.bre.engine.bel.check;

import java.util.Optional;
import java.util.Set;

/**
 * What the type checker is allowed to ask about the names an expression mentions.
 *
 * <p>An interface rather than a dependency on the fact model, and the direction matters: {@code
 * bre-fact-model} depends on {@code bre-engine-core} (LLD §1.2), so the engine cannot depend back
 * on it. The engine declares the questions; the compiler supplies an implementation backed by the
 * resolved fact schema, parameter set and reference lists.
 *
 * <p>It also keeps the checker testable without a schema document, and keeps the engine's purity
 * rule intact: this interface has no I/O in it, so nothing reachable from the engine can perform
 * any.
 *
 * <p><b>Every method answers "I don't know" rather than guessing.</b> An empty {@link Optional} is
 * what produces BEL-019, BEL-022 or BEL-023 — a diagnostic naming the unknown name — instead of a
 * type the checker invented, which would turn a typo into a runtime error on a real application.
 */
public interface SymbolTable {

  /** A symbol table that knows nothing: every lookup fails. Useful only in tests. */
  SymbolTable EMPTY =
      new SymbolTable() {
        @Override
        public Optional<BelType> factPathType(String path) {
          return Optional.empty();
        }

        @Override
        public Optional<BelType> parameterType(String name) {
          return Optional.empty();
        }

        @Override
        public Optional<BelType> referenceListType(String name) {
          return Optional.empty();
        }

        @Override
        public Optional<BelType> variableType(String name) {
          return Optional.empty();
        }

        @Override
        public Optional<BelType> componentOutputType(String code, String output) {
          return Optional.empty();
        }
      };

  /**
   * The type of a fact path.
   *
   * @param path the dotted path, with {@code []} for a list element
   * @return the type, empty when the schema does not declare the path (BEL-019)
   */
  Optional<BelType> factPathType(String path);

  /**
   * The type of a policy parameter.
   *
   * @param name the name after {@code param.}
   * @return the type, empty when the parameter set does not declare it (BEL-022)
   */
  Optional<BelType> parameterType(String name);

  /**
   * The type of a reference list.
   *
   * @param name the name after {@code list.}
   * @return the list's type, empty when no such list is pinned (BEL-023)
   */
  Optional<BelType> referenceListType(String name);

  /**
   * The declared type of a policy variable.
   *
   * @param name the variable's name
   * @return the type, empty when the policy declares no such variable
   */
  Optional<BelType> variableType(String name);

  /**
   * The type of another component's named output, for a result reference.
   *
   * @param code the component's code
   * @param output the output's name
   * @return the type, empty when the component or the output is unknown (BEL-016)
   */
  Optional<BelType> componentOutputType(String code, String output);

  /**
   * Whether a fact path is tagged {@code protected} (§9.1).
   *
   * <p>Reading one from rule logic is BEL-017 unless the component carries an approved governance
   * override. Default false, so a table that does not model tags does not block authoring — the
   * compiler's real table does model them.
   *
   * @param path the dotted fact path
   * @return true when the field is protected
   */
  default boolean isProtected(String path) {
    return false;
  }

  /**
   * Whether a fact path is tagged {@code aadhaar} (§9.1).
   *
   * <p>BEL-026, and unlike {@code protected} there is no override: rule logic may never read it.
   *
   * @param path the dotted fact path
   * @return true when the field is an Aadhaar reference
   */
  default boolean isAadhaar(String path) {
    return false;
  }

  /**
   * The permitted values of an enum-typed field, for BEL-011.
   *
   * @param path the dotted fact path
   * @return the values, empty when the field is not an enum
   */
  default Set<String> enumValues(String path) {
    return Set.of();
  }
}
