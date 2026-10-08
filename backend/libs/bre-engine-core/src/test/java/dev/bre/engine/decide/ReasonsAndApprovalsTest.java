package dev.bre.engine.decide;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bre.engine.eval.EvaluationContext;
import dev.bre.engine.eval.Value;
import dev.bre.engine.ir.Ir;
import dev.bre.engine.ir.Ir.AuthorityLevel;
import dev.bre.engine.ir.Ir.AuthorityMatrix;
import dev.bre.engine.ir.Ir.RuntimeStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Customer-facing reason selection (§4.10) and required-approval routing (§4.9). */
class ReasonsAndApprovalsTest {

  private static final ReasonCatalogue CATALOGUE =
      ReasonCatalogue.builder()
          .adverse("CR_BUREAU_SCORE", "Your credit bureau score is below our requirement.")
          .adverse("AFF_FOIR", "Your existing obligations are too high for this loan.")
          .adverse("POL_VRM_DE", "We do not currently lend in this area.")
          .internal("OPS_MANUAL_CHECK")
          .build();

  /** An entity result at a given status, carrying a reason code. */
  private static ComponentResult result(String code, RuntimeStatus status, String reasonCode) {
    return ComponentResult.of(code, status, reasonCode);
  }

  private static Reasons.Ordered at(ComponentResult result, int stage, int step) {
    return new Reasons.Ordered(result, Optional.empty(), stage, step);
  }

  private static Reasons.Ordered at(ComponentResult result, int priority, int stage, int step) {
    return new Reasons.Ordered(result, Optional.of(priority), stage, step);
  }

  private static List<String> codesOf(List<Reasons.CustomerReason> reasons) {
    return reasons.stream().map(Reasons.CustomerReason::code).toList();
  }

  @Nested
  @DisplayName("§4.10 customer-facing reasons")
  class CustomerReasons {

    /** The first line of §4.10, and the one most worth getting wrong-proof. */
    @Test
    void areProducedOnlyForADecline() {
      List<Reasons.Ordered> entities =
          List.of(at(result("R1", RuntimeStatus.FAIL, "CR_BUREAU_SCORE"), 0, 0));

      for (Outcomes.Decision decision : Outcomes.Decision.values()) {
        List<Reasons.CustomerReason> reasons =
            Reasons.customerReasons(decision, entities, CATALOGUE, 4, "en");
        if (decision == Outcomes.Decision.DECLINE) {
          assertThat(codesOf(reasons)).containsExactly("CR_BUREAU_SCORE");
        } else {
          assertThat(reasons)
              .as("a %s has refused the applicant nothing, so it discloses nothing", decision)
              .isEmpty();
        }
      }
    }

    @Test
    void onlyFailContributes() {
      List<Reasons.Ordered> entities =
          List.of(
              at(result("R1", RuntimeStatus.DEVIATION, "AFF_FOIR"), 0, 0),
              at(result("R2", RuntimeStatus.REFER, "POL_VRM_DE"), 0, 1),
              at(result("R3", RuntimeStatus.FAIL, "CR_BUREAU_SCORE"), 0, 2));

      assertThat(codesOf(decline(entities)))
          .as("a deviation is a tolerance and a refer is a question; neither is a refusal")
          .containsExactly("CR_BUREAU_SCORE");
    }

    @Test
    void systemCodesAreDropped() {
      List<Reasons.Ordered> entities =
          List.of(
              at(result("R1", RuntimeStatus.FAIL, FlowEvaluator.SKIPPED_KNOCKOUT), 0, 0),
              at(result("R2", RuntimeStatus.FAIL, FlowEvaluator.SYS_ERROR), 0, 1),
              at(result("R3", RuntimeStatus.FAIL, "DATA_MISSING_BUREAU"), 0, 2),
              at(result("R4", RuntimeStatus.FAIL, Coverage.DEV_NO_AUTHORITY), 0, 3),
              at(result("R5", RuntimeStatus.FAIL, "CR_BUREAU_SCORE"), 0, 4));

      assertThat(codesOf(decline(entities)))
          .as("the engine's own state is not a credit ground the applicant can be told")
          .containsExactly("CR_BUREAU_SCORE");
    }

    @Test
    void nonAdverseCodesAreDropped() {
      List<Reasons.Ordered> entities =
          List.of(at(result("R1", RuntimeStatus.FAIL, "OPS_MANUAL_CHECK"), 0, 0));
      assertThat(decline(entities)).isEmpty();
    }

    /**
     * A code the catalogue has never seen is not disclosed. Showing an unreviewed internal code to
     * a declined applicant is the worse of the two failures — the internal list still records it.
     */
    @Test
    void anUncataloguedCodeIsNotDisclosed() {
      List<Reasons.Ordered> entities =
          List.of(at(result("R1", RuntimeStatus.FAIL, "SOME_NEW_CODE"), 0, 0));

      assertThat(decline(entities)).isEmpty();
      assertThat(Reasons.internalReasons(entities)).containsExactly("SOME_NEW_CODE");
    }

    /** A deviation an approval covered reads as PASS, so it is not a ground for refusal. */
    @Test
    void anApprovedDeviationDoesNotSurface() {
      ComponentResult approved =
          result("R1", RuntimeStatus.DEVIATION, "AFF_FOIR")
              .approved(Coverage.DEVIATION_APPROVED, "APR-0001");

      assertThat(approved.effectiveStatus()).isEqualTo(RuntimeStatus.PASS);
      assertThat(decline(List.of(at(approved, 0, 0)))).isEmpty();
    }

    @Test
    void duplicatesKeepTheFirstOccurrence() {
      List<Reasons.Ordered> entities =
          List.of(
              at(result("R1", RuntimeStatus.FAIL, "AFF_FOIR"), 0, 0),
              at(result("R2", RuntimeStatus.FAIL, "AFF_FOIR"), 0, 1),
              at(result("R3", RuntimeStatus.FAIL, "CR_BUREAU_SCORE"), 0, 2));

      assertThat(codesOf(decline(entities))).containsExactly("AFF_FOIR", "CR_BUREAU_SCORE");
    }

    @Test
    void theListIsCappedAtTheConfiguredLimit() {
      List<Reasons.Ordered> entities =
          List.of(
              at(result("R1", RuntimeStatus.FAIL, "CR_BUREAU_SCORE"), 0, 0),
              at(result("R2", RuntimeStatus.FAIL, "AFF_FOIR"), 0, 1),
              at(result("R3", RuntimeStatus.FAIL, "POL_VRM_DE"), 0, 2));

      assertThat(
              codesOf(
                  Reasons.customerReasons(Outcomes.Decision.DECLINE, entities, CATALOGUE, 2, "en")))
          .containsExactly("CR_BUREAU_SCORE", "AFF_FOIR");
      assertThat(Reasons.customerReasons(Outcomes.Decision.DECLINE, entities, CATALOGUE, 0, "en"))
          .as("a limit of zero discloses nothing")
          .isEmpty();
    }

    /** Step 1: priority first, and an absent priority sorts last ({@code priority ?: ∞}). */
    @Test
    void orderIsPriorityThenStageThenStep() {
      List<Reasons.Ordered> entities =
          List.of(
              // Declared late in the flow but with the lowest priority number, so it comes first.
              at(result("R3", RuntimeStatus.FAIL, "POL_VRM_DE"), 1, 2, 0),
              at(result("R1", RuntimeStatus.FAIL, "CR_BUREAU_SCORE"), 0, 0),
              at(result("R2", RuntimeStatus.FAIL, "AFF_FOIR"), 5, 0, 1));

      assertThat(codesOf(decline(entities)))
          .containsExactly("POL_VRM_DE", "AFF_FOIR", "CR_BUREAU_SCORE");
    }

    @Test
    void wordingFallsBackFromRegionToLanguageToTheCode() {
      ReasonCatalogue catalogue =
          new ReasonCatalogue(
              java.util.Map.of(
                  "CR_BUREAU_SCORE",
                  new ReasonCatalogue.Entry(
                      true, java.util.Map.of("en", "Score too low", "hi", "स्कोर बहुत कम है")),
                  "AFF_FOIR",
                  new ReasonCatalogue.Entry(true, java.util.Map.of())));

      assertThat(catalogue.render("CR_BUREAU_SCORE", "hi")).isEqualTo("स्कोर बहुत कम है");
      assertThat(catalogue.render("CR_BUREAU_SCORE", "en-IN"))
          .as("a region tag falls back to its language before the default")
          .isEqualTo("Score too low");
      assertThat(catalogue.render("CR_BUREAU_SCORE", "ta")).isEqualTo("Score too low");
      assertThat(catalogue.render("AFF_FOIR", "en"))
          .as("an entry with no wording at all falls back to the code rather than to null")
          .isEqualTo("AFF_FOIR");
    }

    @Test
    void theRenderedTextIsCarriedOnTheReason() {
      List<Reasons.CustomerReason> reasons =
          decline(List.of(at(result("R1", RuntimeStatus.FAIL, "CR_BUREAU_SCORE"), 0, 0)));

      assertThat(reasons)
          .singleElement()
          .satisfies(
              reason ->
                  assertThat(reason.text())
                      .isEqualTo("Your credit bureau score is below our requirement."));
    }

    private List<Reasons.CustomerReason> decline(List<Reasons.Ordered> entities) {
      return Reasons.customerReasons(Outcomes.Decision.DECLINE, entities, CATALOGUE, 4, "en");
    }
  }

  @Nested
  @DisplayName("§4.10 internal reasons")
  class InternalReasons {

    /** The audit trail is complete where the customer list is filtered. */
    @Test
    void keepEveryNonPassCodeIncludingTheSystemOnes() {
      List<Reasons.Ordered> entities =
          List.of(
              at(result("R1", RuntimeStatus.PASS, null), 0, 0),
              at(result("R2", RuntimeStatus.NOT_APPLICABLE, "SYS_SKIPPED_KNOCKOUT"), 0, 1),
              at(result("R3", RuntimeStatus.DATA_MISSING, "DATA_MISSING_BUREAU"), 0, 2),
              at(result("R4", RuntimeStatus.DEVIATION, "AFF_FOIR"), 0, 3),
              at(result("R5", RuntimeStatus.REFER, Coverage.DEV_NO_AUTHORITY), 0, 4),
              at(result("R6", RuntimeStatus.FAIL, "AFF_FOIR"), 0, 5));

      assertThat(Reasons.internalReasons(entities))
          .containsExactly("DATA_MISSING_BUREAU", "AFF_FOIR", Coverage.DEV_NO_AUTHORITY);
    }
  }

  @Nested
  @DisplayName("§4.9 required approvals")
  class Approvals {

    /** Ranks deliberately not in alphabetical order of their codes. */
    private final AuthorityMatrix matrix =
        new AuthorityMatrix(
            "MFI_DOP",
            3,
            "HIGHEST_ONLY",
            List.of(
                new AuthorityLevel("L2", 2),
                new AuthorityLevel("L10", 10),
                new AuthorityLevel("BOARD", 20)),
            List.of());

    private AuthorityMatrix withMode(String mode) {
      return new AuthorityMatrix(
          matrix.code(), matrix.version(), mode, matrix.levels(), matrix.entries());
    }

    private ComponentResult deviation(String code, String deviationType, String level) {
      return ComponentResult.of(
              code,
              RuntimeStatus.DEVIATION,
              "AFF_FOIR",
              deviationType,
              null,
              null,
              java.util.Map.of())
          .forEntity("MEMBER", code + "-entity")
          .requiring(level);
    }

    @Test
    void nothingIsRequiredWhenNothingDeviates() {
      assertThat(
              RequiredApprovals.resolve(
                  List.of(result("R1", RuntimeStatus.PASS, null)), Optional.of(matrix)))
          .isEmpty();
    }

    @Test
    void highestOnlyGivesOneItemAtTheHighestRankCoveringEverything() {
      List<ComponentResult> entities =
          List.of(
              deviation("R1", "FOIR", "L2"),
              deviation("R2", "INDEBTEDNESS_CAP", "L10"),
              deviation("R3", "VRM_CATEGORY", "L2"));

      List<RequiredApprovals.Item> items =
          RequiredApprovals.resolve(entities, Optional.of(withMode("HIGHEST_ONLY")));

      assertThat(items)
          .singleElement()
          .satisfies(
              item -> {
                assertThat(item.level()).isEqualTo("L10");
                assertThat(item.deviations())
                    .extracting(RequiredApprovals.Deviation::ruleCode)
                    .containsExactly("R1", "R2", "R3");
              });
    }

    /**
     * The level codes are ranked by the matrix, not sorted as text. {@code L10} sorts before {@code
     * L2} alphabetically, which would route a ten-lakh deviation to the junior approver.
     */
    @Test
    void theHighestLevelIsByDeclaredRankNotByCodeSpelling() {
      List<ComponentResult> entities =
          List.of(deviation("R1", "FOIR", "L10"), deviation("R2", "CAP", "L2"));

      assertThat(RequiredApprovals.resolve(entities, Optional.of(withMode("HIGHEST_ONLY"))))
          .singleElement()
          .satisfies(item -> assertThat(item.level()).isEqualTo("L10"));
    }

    @Test
    void eachGivesOneItemPerDeviationInFlowOrder() {
      List<ComponentResult> entities =
          List.of(
              deviation("R1", "FOIR", "L2"),
              deviation("R2", "INDEBTEDNESS_CAP", "L10"),
              deviation("R3", "VRM_CATEGORY", "L2"));

      List<RequiredApprovals.Item> items =
          RequiredApprovals.resolve(entities, Optional.of(withMode("EACH")));

      assertThat(items).hasSize(3);
      assertThat(items)
          .extracting(RequiredApprovals.Item::level)
          .containsExactly("L2", "L10", "L2");
      assertThat(items).allSatisfy(item -> assertThat(item.deviations()).hasSize(1));
      assertThat(items.get(1).deviations().get(0).deviationType()).isEqualTo("INDEBTEDNESS_CAP");
    }

    @Test
    void anApprovedDeviationIsNotRequestedAgain() {
      List<ComponentResult> entities =
          List.of(
              deviation("R1", "FOIR", "L2").approved(Coverage.DEVIATION_APPROVED, "APR-0001"),
              deviation("R2", "CAP", "L2"));

      assertThat(RequiredApprovals.resolve(entities, Optional.of(matrix)))
          .singleElement()
          .satisfies(
              item ->
                  assertThat(item.deviations())
                      .extracting(RequiredApprovals.Deviation::ruleCode)
                      .containsExactly("R2"));
    }

    /**
     * A deviation no matrix band governs became REFER with {@code DEV_NO_AUTHORITY} and carries no
     * required level — there is nobody to ask, so it is not an approval request.
     */
    @Test
    void aDeviationWithNoAuthorityBandIsNotAnApprovalRequest() {
      ComponentResult unmapped =
          ComponentResult.of(
                  "R1", RuntimeStatus.DEVIATION, "AFF_FOIR", "FOIR", null, null, java.util.Map.of())
              .withEffectiveStatus(RuntimeStatus.REFER, Coverage.DEV_NO_AUTHORITY);

      assertThat(RequiredApprovals.resolve(List.of(unmapped), Optional.of(matrix))).isEmpty();
    }

    @Test
    void theModeComesFromTheMatrixVersionAndDefaultsToHighestOnly() {
      List<ComponentResult> entities =
          List.of(deviation("R1", "FOIR", "L2"), deviation("R2", "CAP", "L2"));

      assertThat(RequiredApprovals.resolve(entities, Optional.empty()))
          .as("no matrix, so §5.3's column default applies")
          .hasSize(1);
      assertThat(RequiredApprovals.DEFAULT_ROUTING_MODE).isEqualTo("HIGHEST_ONLY");
    }

    @Test
    void theEntityIsCarriedOnEachDeviation() {
      List<RequiredApprovals.Item> items =
          RequiredApprovals.resolve(
              List.of(deviation("R1", "FOIR", "L2")), Optional.of(withMode("EACH")));

      RequiredApprovals.Deviation only = items.get(0).deviations().get(0);
      assertThat(only.entityType()).contains("MEMBER");
      assertThat(only.entityRef()).contains("R1-entity");
    }
  }

  @Nested
  @DisplayName("assembled through the flow")
  class ThroughTheFlow {

    /** The lists are reachable from a real evaluation, not only from the helpers above. */
    @Test
    void aDeclineCarriesItsCustomerReasonsAndTheFlowStillRuns() {
      Ir.RuleDef rule =
          new Ir.RuleDef(
              "CR_SCORE",
              "Bureau score floor",
              Ir.Scope.APPLICATION,
              Optional.empty(),
              "application.score >= 650",
              new Ir.Outcome(
                  Ir.OutcomeStatus.FAIL,
                  Optional.of("CR_BUREAU_SCORE"),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty()),
              Ir.OnMissing.DEFAULT,
              java.util.Map.of(),
              Optional.empty());

      Ir.PolicyIr policy =
          new Ir.PolicyIr(
              1,
              List.of(),
              List.of(rule),
              List.of(),
              List.of(),
              new Ir.FlowDef(
                  List.of(
                      new Ir.StageDef(
                          "ELIGIBILITY",
                          false,
                          List.of(
                              Ir.StepDef.of(
                                  "CR_SCORE", Ir.AggregateSpec.of(Ir.AggregateSpec.Kind.WORST)))))),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Ir.PolicyIr.DEFAULT_CUSTOMER_REASON_LIMIT);

      Value.RecordV facts =
          new Value.RecordV(
              java.util.Map.of(
                  "application",
                  new Value.RecordV(java.util.Map.of("score", new Value.IntV(600)))));
      EvaluationContext context =
          EvaluationContext.of(
              facts, java.util.Map.of(), java.util.Map.of(), LocalDate.parse("2026-10-01"));

      FlowEvaluator.DecisionResult decision =
          FlowEvaluator.evaluate(policy, context, CATALOGUE, "en");

      assertThat(decision.decision()).isEqualTo(Outcomes.Decision.DECLINE);
      assertThat(codesOf(decision.customerReasons())).containsExactly("CR_BUREAU_SCORE");
      assertThat(decision.internalReasons()).containsExactly("CR_BUREAU_SCORE");
      assertThat(decision.requiredApprovals()).isEmpty();
    }
  }
}
