package dev.bre.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs every LLD §13 conformance vector against the engine.
 *
 * <p>At M0 there is no engine, so every vector reports as <em>pending</em> (skipped with a reason)
 * rather than as a failure: a red suite at M0 would be indistinguishable from a broken one, and the
 * milestone's exit criterion is that CI is green and the runner lists every vector. From M1 onward
 * each vector turns green as the corresponding engine behaviour lands, and the engine is finished
 * when all of them are.
 *
 * <p>The guard against the suite quietly becoming vacuous is {@link
 * #vectorsCoverTheSpecification()} — it pins the families the LLD table defines, so a vector file
 * that goes missing fails the build instead of shrinking the suite in silence.
 */
class EngineConformanceTest {

  private final EngineUnderTest engine = new BreEngine();

  @TestFactory
  @DisplayName("LLD §13 conformance vectors")
  List<DynamicTest> conformanceVectors() {
    List<ConformanceVector> vectors = VectorLoader.loadAll();
    assertThat(vectors)
        .as(
            "no vectors found in %s — the suite would pass by checking nothing",
            VectorLoader.vectorDirectory())
        .isNotEmpty();

    return vectors.stream()
        .map(
            vector ->
                DynamicTest.dynamicTest(
                    vector.displayName(),
                    () -> {
                      ActualOutcome actual;
                      try {
                        actual = engine.evaluate(vector);
                      } catch (EngineUnderTest.NotImplemented pending) {
                        Assumptions.abort(
                            "pending: " + pending.getMessage() + " [" + vector.source() + "]");
                        return;
                      } catch (dev.bre.engine.eval.EvalException runtimeError) {
                        // §3.11: a runtime error is reported ON the result, not thrown out of the
                        // API, so a vector expecting one compares against its code.
                        actual =
                            new ActualOutcome(
                                new com.fasterxml.jackson.databind.ObjectMapper()
                                    .valueToTree(
                                        java.util.Map.of("error", runtimeError.code().name())));
                      }
                      assertThat(JsonCanonical.normalize(actual.expect()))
                          .as(
                              "vector %s (%s) from %s%nexpected %s%nactual   %s",
                              vector.id(),
                              vector.description(),
                              vector.source(),
                              vector.expect(),
                              actual.expect())
                          .isEqualTo(JsonCanonical.normalize(vector.expect()));
                    }))
        .toList();
  }

  /**
   * The LLD §13 table defines vectors in named families. This test pins the family prefixes so the
   * suite cannot shrink unnoticed — the failure mode it guards against is a vector file being
   * deleted or renamed and the suite still reporting success over whatever is left.
   */
  @Test
  @DisplayName("every vector family from the LLD §13 table is present")
  void vectorsCoverTheSpecification() {
    Set<String> expectedFamilies =
        Set.of(
            "L", // three-valued logic, in, if
            "D", // decimal and rounding
            "S1", // when / onMissing
            "S2", // empty lists, strict propagation, projection
            "S3", // fail-closed filters
            "S4", // flatten
            "S5", // decision-table dash, missing row
            "S6", // WORST aggregation
            "S7", // THRESHOLD aggregation
            "S8", // deviation coverage
            "S10", // dates
            "S11", // scorecards
            "S12", // knockout
            "M", // onMissing modes
            "T", // hit policies
            "A", // ALL_MUST_PASS
            "P", // decision precedence and authority
            "C", // champion/challenger traffic split
            "F", // fuel
            "E" // end-to-end
            );

    Set<String> actualFamilies =
        VectorLoader.loadAll().stream()
            .map(v -> v.id().substring(0, v.id().indexOf('-')))
            .collect(Collectors.toSet());

    assertThat(actualFamilies)
        .as(
            "a missing family means part of the LLD §13 specification is not being checked; "
                + "add the vectors, do not relax this list")
        .containsAll(expectedFamilies);
  }

  @Test
  @DisplayName("every vector asserts something, so key projection cannot pass one vacuously")
  void everyVectorAssertsSomething() {
    // The runner compares only the keys a vector names (see BreEngine#project). That is right for
    // the §13 table, whose rows assert different things — but it means an empty `expect` would
    // match anything at all.
    assertThat(VectorLoader.loadAll())
        .allSatisfy(
            vector ->
                assertThat(vector.expect().size())
                    .as("vector %s asserts nothing", vector.id())
                    .isPositive());
  }

  @Test
  @DisplayName("every vector id is unique and every file parses")
  void vectorsAreWellFormed() {
    // loadAll() throws on a duplicate id, a missing required field, or a vector carrying both or
    // neither of expr/policy. Calling it is the assertion.
    List<ConformanceVector> vectors = VectorLoader.loadAll();
    assertThat(vectors).isNotEmpty();
    assertThat(vectors.stream().map(ConformanceVector::id).distinct().count())
        .isEqualTo(vectors.size());
  }
}
