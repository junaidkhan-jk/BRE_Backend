package dev.bre.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.bre.engine.decide.ComponentResult;
import dev.bre.engine.decide.FlowEvaluator;
import dev.bre.engine.eval.Decimals;
import dev.bre.engine.eval.EvaluationContext;
import dev.bre.engine.eval.Value;
import dev.bre.engine.ir.Ir;
import dev.bre.facts.FactReader;
import dev.bre.facts.FactsValidator;
import dev.bre.facts.LendingFacts;
import dev.bre.irjson.AssetCodec;
import dev.bre.irjson.IrCodec;
import dev.bre.irjson.PsfCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The MFI reference policy, decided against real facts (M6-01's second half).
 *
 * <p>The ninety conformance vectors prove the engine implements §13's semantics one rule at a time.
 * This proves something different and larger: that a lender's whole credit policy — eleven rules,
 * three decision tables, a scorecard, a five-stage flow with a knockout and a THRESHOLD aggregate,
 * resolved parameters, three reference lists and a delegation-of-powers matrix — produces the
 * decisions its author intended, over documents that conform to the published fact schema.
 *
 * <p>It is deliberately an <em>outcome</em> test, not a trace test. It asserts the decision, the
 * step that drove it and the reason code, because those are what a lender signs off; asserting
 * every intermediate would make any policy edit a test edit and the suite would stop being
 * maintained.
 *
 * <p>Each case started as a bug. Before component outputs were published between flow steps, every
 * one of these returned REFER / DATA_SCORE_MISSING — the scorecard computed a score and the cut-off
 * table one step below it could not see it. Before the Authority Matrix was resolved from the asset
 * source, the soft-cap case reported DEV_NO_AUTHORITY instead of the deviation itself. Neither
 * failure looked like a missing feature from the outside; both looked like bad input data.
 */
class ReferencePolicyAcceptanceTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The business date every case is decided on; fixed, because the engine reads no clock (D8). */
  private static final LocalDate AS_OF = LocalDate.of(2026, 10, 1);

  private static Path root;
  private static Ir.PolicyIr policy;
  private static Map<String, Value> parameters;
  private static Map<String, Value> lists;

  @BeforeAll
  static void compileTheReferencePolicy() throws IOException {
    root = Path.of(System.getProperty("bre.reference.policy"));

    ObjectNode ir =
        PsfCodec.toIrJson(
            Files.readString(root.resolve("mfi-jlg-credit-uw.psf.yaml")), "t_acceptance");
    JsonNode assetSource = JSON.readTree(Files.readString(root.resolve("assets.json")));

    // Exactly what `bre psf bundle --assets` does, minus the signing: the parameters and lists
    // become the bundle's assets, and the Authority Matrix is substituted into the IR because that
    // is where IrCodec reads it from.
    ObjectNode resolved = AssetCodec.resolveForPolicy(assetSource, ir.get("assets"));
    AssetCodec.resolveAuthorityMatrix(assetSource, ir.get("assets"))
        .ifPresent(matrix -> ((ObjectNode) ir.get("assets")).set("authorityMatrix", matrix));

    policy = IrCodec.readPolicy(ir);
    parameters = AssetCodec.parameters(resolved);
    lists = AssetCodec.lists(resolved);
  }

  @Test
  @DisplayName("a clean five-member JLG is approved straight through")
  void cleanGroupApproves() throws IOException {
    FlowEvaluator.DecisionResult result = decide("approve-5-member-jlg.json");

    assertThat(result.decision().name()).isEqualTo("APPROVE");
    assertThat(result.stpEligible())
        .as("no deviation and no referral, so nothing needs a human")
        .isTrue();
    assertThat(result.customerReasons()).isEmpty();

    // Every step either passed or did not apply. A policy that approves while a step errored is
    // approving despite not having evaluated something.
    assertThat(result.steps())
        .allSatisfy(
            step ->
                assertThat(step.status().name())
                    .as("step %s", step.ref())
                    .isIn("PASS", "NOT_APPLICABLE"));

    // The scorecard's output must reach the cut-off table: 300 base + 50 (bureau 720) + 45 (cycle
    // 2) + 60 (no delinquency) = 455, against SCORE_APPROVE_MIN of 420.
    assertThat(scoreOf(result)).isEqualTo("455");
  }

  @Test
  @DisplayName("one member on the negative list declines the whole application at the knockout")
  void negativeListKnocksOut() throws IOException {
    FlowEvaluator.DecisionResult result = decide("decline-negative-list.json");

    assertThat(result.decision().name()).isEqualTo("DECLINE");
    assertThat(result.internalReasons()).contains("FRD_NEGATIVE_LIST");

    // §4.8: the whole knockout stage runs before the flow stops, so the other four members are
    // still reported as screened — and every later stage is marked skipped rather than passed.
    FlowEvaluator.StepResult screening = result.step("SCR_SCREENING").orElseThrow();
    assertThat(screening.entities()).hasSize(5);
    assertThat(screening.entities())
        .filteredOn(entity -> entity.reasonCode().filter("FRD_NEGATIVE_LIST"::equals).isPresent())
        .hasSize(1);

    assertThat(result.step("MFI_SCORE_CUTOFF").orElseThrow().entities())
        .allSatisfy(
            entity -> assertThat(entity.reasonCode()).contains(FlowEvaluator.SKIPPED_KNOCKOUT));
  }

  @Test
  @DisplayName(
      "a soft-cap deviation on a fifth of the group escalates to REFER, not to an approver")
  void softCapThresholdRefers() throws IOException {
    FlowEvaluator.DecisionResult result = decide("refer-soft-cap-threshold.json");

    // Guide E7: one member over the internal soft cap is a deviation a branch manager can sign;
    // a fifth of the group over it is a pattern, and the policy's THRESHOLD aggregate refers it.
    assertThat(result.decision().name()).isEqualTo("REFER");
    assertThat(result.internalReasons()).contains("AFF_INDEBTEDNESS_SOFT_CAP");
    assertThat(result.stpEligible()).isFalse();

    FlowEvaluator.StepResult softCap = result.step("MFI_INDEBTEDNESS_SOFT_CAP").orElseThrow();
    assertThat(softCap.status().name()).isEqualTo("REFER");
    assertThat(softCap.entities())
        .filteredOn(entity -> entity.status().name().equals("DEVIATION"))
        .singleElement()
        .satisfies(
            entity -> {
              assertThat(entity.entityRef()).contains("P-0001");
              assertThat(entity.reasonCode()).contains("AFF_INDEBTEDNESS_SOFT_CAP");
              // The hard cap is 200,000 and this member sits at 140,000, so it is the SOFT cap
              // that fired — the measure is what an approver is actually shown.
              assertThat(entity.measure()).isPresent();
            });

    // The authority amount is 150,000, which the matrix puts in the 100,000–300,000 band.
    assertThat(result.requiredApprovals())
        .singleElement()
        .satisfies(item -> assertThat(item.level()).isEqualTo("AREA_MANAGER"));
  }

  @Test
  @DisplayName("every fixture conforms to the published fact schema")
  void fixturesAreSchemaValid() throws IOException {
    // Not a formality: a fixture that quietly drifts from the schema would be typed as undeclared
    // text, and the three tests above would start failing for a reason that has nothing to do with
    // the policy.
    FactsValidator validator = new FactsValidator(LendingFacts.schema());
    try (var fixtures = Files.list(root.resolve("fixtures"))) {
      List<Path> all = fixtures.filter(f -> f.toString().endsWith(".json")).sorted().toList();
      assertThat(all).as("the fixtures directory must not be empty").isNotEmpty();
      for (Path fixture : all) {
        assertThat(validator.validate(JSON.readTree(Files.readString(fixture))))
            .as("%s", fixture.getFileName())
            .isEmpty();
      }
    }
  }

  // ---------------------------------------------------------------------------------------------

  private FlowEvaluator.DecisionResult decide(String fixture) throws IOException {
    Value.RecordV facts =
        new FactReader(LendingFacts.schema()).toRecord(read(root.resolve("fixtures/" + fixture)));
    return FlowEvaluator.evaluate(policy, EvaluationContext.of(facts, parameters, lists, AS_OF));
  }

  private static Map<String, Object> read(Path file) throws IOException {
    return JSON.readValue(Files.readString(file), new TypeReference<Map<String, Object>>() {});
  }

  /** The score the scorecard published, as the cut-off table would have seen it. */
  private static String scoreOf(FlowEvaluator.DecisionResult result) {
    ComponentResult scorecard = result.step("MFI_APP_SCORE_V1").orElseThrow().entities().getFirst();
    return Decimals.toDecimal(scorecard.outputs().get("score"))
        .stripTrailingZeros()
        .toPlainString();
  }
}
