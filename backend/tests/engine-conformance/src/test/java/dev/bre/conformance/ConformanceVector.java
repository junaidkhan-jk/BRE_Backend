package dev.bre.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/**
 * One conformance vector, in the format fixed by LLD §13.
 *
 * <p>Vectors are language-neutral data on purpose: every engine build must pass all of them, and so
 * must the studio's TypeScript preview evaluator if one is ever added. Nothing in this record is
 * Java-specific, and the JSON on disk is the artifact of record — this type is only a reader for
 * it.
 *
 * <p>A vector is one of two shapes. An <em>expression</em> vector carries {@code expr} and expects
 * a {@code value}. A <em>policy</em> vector carries a {@code policy} IR fragment and expects a
 * {@code status}, a {@code decision} or {@code steps}.
 *
 * @param id vector id from the LLD §13 table, e.g. {@code S3-01}. Unique across all files.
 * @param description what the vector pins down, in the LLD's own words
 * @param asOf the evaluation date. Evaluation never reads the wall clock (D8); it uses only this.
 * @param scope entity scope, e.g. {@code MEMBER} or {@code GROUP}. Absent for scope-free vectors.
 * @param params policy parameters bound under the {@code param.} namespace
 * @param lists named lists bound under the {@code list.} namespace
 * @param facts the fact snapshot the expression or policy is evaluated against
 * @param expr the BEL expression, for expression vectors
 * @param policy the IR fragment, for rule- and policy-level vectors
 * @param expect the expected outcome
 * @param source the file this vector was read from, for failure messages
 */
public record ConformanceVector(
    String id,
    String description,
    String asOf,
    Optional<String> scope,
    JsonNode params,
    JsonNode lists,
    JsonNode facts,
    Optional<String> expr,
    Optional<JsonNode> policy,
    JsonNode expect,
    String source) {

  /** Which of the two vector shapes this is. */
  public enum Shape {
    /** Carries {@code expr}; expects a {@code value}. */
    EXPRESSION,
    /** Carries {@code policy}; expects a {@code status}, {@code decision} or {@code steps}. */
    POLICY
  }

  /**
   * Returns the shape of this vector.
   *
   * @return {@link Shape#EXPRESSION} if the vector carries an expression, else {@link Shape#POLICY}
   * @throws IllegalStateException if the vector carries neither or both
   */
  public Shape shape() {
    boolean hasExpr = expr.isPresent();
    boolean hasPolicy = policy.isPresent();
    if (hasExpr == hasPolicy) {
      throw new IllegalStateException(
          "vector "
              + id
              + " in "
              + source
              + " must carry exactly one of expr or policy, not "
              + (hasExpr ? "both" : "neither"));
    }
    return hasExpr ? Shape.EXPRESSION : Shape.POLICY;
  }

  /** Returns the vector's id and description, for a test display name. */
  public String displayName() {
    return id + " — " + description;
  }
}
