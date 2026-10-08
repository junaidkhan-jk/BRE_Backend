package dev.bre.irjson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.bre.engine.ir.Ir;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** PSF to IR translation, against the mapping rules of LLD §2.4. */
class PsfCodecTest {

  private static final String TENANT = "t_acme";

  /** The §3.14 example's only fixture reference, stubbed — the store belongs to the studio (M9). */
  private static JsonNode fixture(String ref) {
    return new ObjectMapper().createObjectNode().put("fixtureRef", ref);
  }

  private static String canonicalExample() throws IOException {
    try (InputStream in =
        PsfCodecTest.class.getResourceAsStream("/dev/bre/irjson/canonical-terms-3.14.psf.yaml")) {
      assertThat(in).as("the §3.14 PSF example resource").isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  // --- the specification's own example ----------------------------------------------------------

  /**
   * The PSF example from {@code 00_Decisions_and_Canonical_Terms.md} §3.14, which M0-04 requires be
   * imported as data rather than paraphrased into a test.
   *
   * <p>It is the only worked example of the format that both documents agree on, so a change to
   * either the codec or the schema that breaks it is a change to the published format.
   */
  @Test
  void theCanonicalExampleTranslatesAndIsSchemaValid() throws IOException {
    ObjectNode ir = PsfCodec.toIrJson(canonicalExample(), TENANT, PsfCodecTest::fixture);

    assertThat(IrSchemaValidator.violations(ir))
        .as("the §3.14 example must be a legal PolicyVersion under the §2.2 schema")
        .isEmpty();

    assertThat(ir.get("irVersion").intValue()).isEqualTo(1);
    assertThat(ir.at("/policy/tenantId").textValue()).isEqualTo(TENANT);
    assertThat(ir.at("/policy/code").textValue()).isEqualTo("MFI_JLG_CREDIT_UW");
    assertThat(ir.at("/policy/version").intValue()).isEqualTo(3);
    assertThat(ir.at("/policy/stage").textValue()).isEqualTo("CREDIT_UW");
  }

  @Test
  void theCanonicalExampleDecodesIntoAnEvaluablePolicy() throws IOException {
    ObjectNode ir = PsfCodec.toIrJson(canonicalExample(), TENANT, PsfCodecTest::fixture);
    Ir.PolicyIr policy = IrCodec.readPolicy(ir);

    assertThat(policy.variables())
        .extracting(Ir.VariableDef::name)
        .containsExactly("proposedAmount", "memberUnsecuredOutstanding");
    assertThat(policy.rules()).extracting(Ir.RuleDef::code).containsExactly("MFI_INDEBTEDNESS_CAP");
    assertThat(policy.decisionTables())
        .extracting(Ir.DecisionTableDef::code)
        .containsExactly("GRP_VRM_CATEGORY");
    assertThat(policy.scorecards())
        .extracting(Ir.ScorecardDef::code)
        .containsExactly("MFI_APP_SCORE_V1");
    assertThat(policy.flow().stages())
        .extracting(Ir.StageDef::name)
        .containsExactly("KNOCKOUT", "ELIGIBILITY", "SCORING");

    // The per-entity variable keeps its declared scope; the other defaults to APPLICATION (M8).
    assertThat(policy.variables().get(0).scope()).isEqualTo(Ir.Scope.APPLICATION);
    assertThat(policy.variables().get(1).scope()).isEqualTo(Ir.Scope.MEMBER);
  }

  // --- M4: reference forms ----------------------------------------------------------------------

  @Test
  void factSchemaAndAssetReferencesSplitIntoCodeAndVersion() throws IOException {
    ObjectNode ir = PsfCodec.toIrJson(canonicalExample(), TENANT, PsfCodecTest::fixture);

    assertThat(ir.at("/factSchema/code").textValue()).isEqualTo("lending-facts");
    assertThat(ir.at("/factSchema/version").textValue()).isEqualTo("1.0.0");
    assertThat(ir.at("/assets/parameterSet/code").textValue()).isEqualTo("MFI_PARAMS");
    assertThat(ir.at("/assets/parameterSet/version").intValue()).isEqualTo(7);
    assertThat(ir.at("/assets/referenceLists/0/code").textValue())
        .isEqualTo("UNSECURED_ACCOUNT_TYPES");
    assertThat(ir.at("/assets/referenceLists/0/version").intValue()).isEqualTo(2);
    assertThat(ir.at("/assets/authorityMatrix/code").textValue()).isEqualTo("MFI_DOP");
  }

  // --- M5, M6, M7: the compact author forms -----------------------------------------------------

  @Test
  void onMissingExpandsFromAWordAndFromUseDefault() {
    ObjectNode explicit = translate("    onMissing: REFER\n");
    assertThat(explicit.at("/rules/0/onMissing/policy").textValue()).isEqualTo("REFER");

    ObjectNode withDefault = translate("    onMissing: USE_DEFAULT(true)\n");
    assertThat(withDefault.at("/rules/0/onMissing/policy").textValue()).isEqualTo("USE_DEFAULT");
    assertThat(withDefault.at("/rules/0/onMissing/value").booleanValue()).isTrue();
  }

  /**
   * M8, and the one default worth a test of its own: an omitted {@code onMissing} is REFER, not
   * "carry on". A rule whose fact is absent must not quietly pass (D9).
   */
  @Test
  void anOmittedOnMissingIsReferNotPass() {
    ObjectNode ir = translate("");
    assertThat(ir.at("/rules/0/onMissing/policy").textValue()).isEqualTo("REFER");
  }

  @Test
  void collectHitPolicyTakesItsOutputFromTheSiblingKey() {
    ObjectNode ir =
        translate(
            "",
            """
            decisionTables:
              - code: T1
                hitPolicy: COLLECT(SUM)
                collectOutput: total
                inputs: [{ name: a, expr: application.a }]
                outputs: [{ name: total, type: Decimal }]
                rows:
                  - { when: ['> 1'], then: { outputs: { total: '1' } } }
            """);
    assertThat(ir.at("/decisionTables/0/hitPolicy/kind").textValue()).isEqualTo("COLLECT");
    assertThat(ir.at("/decisionTables/0/hitPolicy/op").textValue()).isEqualTo("SUM");
    assertThat(ir.at("/decisionTables/0/hitPolicy/collectOutput").textValue()).isEqualTo("total");
  }

  @Test
  void aCollectTableWithNowhereToPutTheResultIsRejected() {
    assertThatThrownBy(
            () ->
                translate(
                    "",
                    """
                    decisionTables:
                      - code: T1
                        hitPolicy: COLLECT(SUM)
                        inputs: [{ name: a, expr: application.a }]
                        rows:
                          - { when: ['> 1'], then: { status: PASS } }
                    """))
        .isInstanceOf(PsfException.class)
        .hasMessageContaining("PSF-003")
        .hasMessageContaining("collectOutput");
  }

  /** M7: the threshold grammar, exactly as §3.14 writes it. */
  @Test
  void thresholdAggregateParsesTheExampleSpelling() throws IOException {
    ObjectNode ir = PsfCodec.toIrJson(canonicalExample(), TENANT, PsfCodecTest::fixture);
    JsonNode aggregate = ir.at("/flow/stages/1/steps/1/aggregate");

    assertThat(aggregate.get("kind").textValue()).isEqualTo("THRESHOLD");
    assertThat(aggregate.get("metric").textValue()).isEqualTo("PERCENT");
    assertThat(aggregate.get("op").textValue()).isEqualTo(">=");
    assertThat(aggregate.get("value").textValue()).isEqualTo("20");
    assertThat(aggregate.get("statuses").toString()).isEqualTo("[\"DEVIATION\"]");
    assertThat(aggregate.get("result").textValue()).isEqualTo("REFER");
  }

  @Test
  void anOmittedAggregateIsWorst() {
    ObjectNode ir = translate("");
    assertThat(ir.at("/flow/stages/0/steps/0/aggregate/kind").textValue()).isEqualTo("WORST");
  }

  // --- M3: the reason this codec composes rather than loads --------------------------------------

  /**
   * An unquoted decimal is PSF-002. This is the rule that protects every threshold in the product:
   * YAML would make {@code 0.45} a binary double, and a FOIR cut-off that is 0.45000000000000001 is
   * a rule nobody wrote.
   */
  @Test
  void anUnquotedDecimalIsRejectedWhereverItAppears() {
    assertThatThrownBy(() -> translate("", "settings:\n  fuelLimit: 2000\nextras: 0.45\n"))
        .isInstanceOf(PsfException.class)
        .hasMessageContaining("PSF-002")
        .hasMessageContaining("quote decimal literals");
  }

  @Test
  void aQuotedDecimalIsAccepted() {
    ObjectNode ir =
        translate(
            "",
            """
            scorecards:
              - code: S1
                baseScore: 300
                adverseReasons: 2
                scaling: { multiplier: '0.5', offset: '0', scale: 0, roundingMode: HALF_UP }
                characteristics:
                  - name: c
                    expr: application.a
                    bins: [{ when: '= 1', points: 10 }]
            """);
    assertThat(ir.at("/scorecards/0/scaling/multiplier").textValue()).isEqualTo("0.5");
  }

  /**
   * M1, the Norway problem. Under YAML 1.1 implicit typing {@code NO} is the boolean false, so a
   * product line coded {@code NO} would silently stop being a product line. The YAML 1.2 core
   * schema keeps it a string.
   */
  @Test
  void upperCaseCodesThatLookLikeBooleansStayStrings() {
    ObjectNode ir =
        PsfCodec.toIrJson(
            """
            policy: P
            version: 1
            productLines: [NO, ON, YES, Y, OFF]
            stage: CREDIT_UW
            factSchema: lending-facts@1.0.0
            """,
            TENANT);
    assertThat(ir.at("/policy/productLines").toString())
        .isEqualTo("[\"NO\",\"ON\",\"YES\",\"Y\",\"OFF\"]");
  }

  // --- M2 ---------------------------------------------------------------------------------------

  @Test
  void aBooleanInAnExpressionPositionBecomesItsText() throws IOException {
    ObjectNode ir = PsfCodec.toIrJson(canonicalExample(), TENANT, PsfCodecTest::fixture);
    // The example writes `when: true`, which is the expression "true".
    assertThat(ir.at("/rules/0/when").textValue()).isEqualTo("true");
  }

  // --- PSF-003 ----------------------------------------------------------------------------------

  /**
   * A misspelled key is an error, not a silently ignored one. {@code onMising: FAIL} would
   * otherwise leave the rule on its REFER default while the author believed it would fail — a
   * fail-closed default hiding a typo is still the wrong rule.
   */
  @Test
  void anUnknownKeyIsRejectedAndNamesWhatIsAccepted() {
    assertThatThrownBy(() -> translate("    onMising: FAIL\n"))
        .isInstanceOf(PsfException.class)
        .hasMessageContaining("PSF-003")
        .hasMessageContaining("rules[0].onMising")
        .hasMessageContaining("unknown key");
  }

  @Test
  void aMissingRequiredKeyNamesThePath() {
    assertThatThrownBy(
            () ->
                PsfCodec.toIrJson(
                    "policy: P\nversion: 1\nproductLines: [X]\nfactSchema: f@1.0.0\n", TENANT))
        .isInstanceOf(PsfException.class)
        .hasMessageContaining("PSF-003")
        .hasMessageContaining("stage")
        .hasMessageContaining("required key is missing");
  }

  @Test
  void aDuplicateKeyIsAParseError() {
    assertThatThrownBy(() -> translate("    check: 'true'\n"))
        .isInstanceOf(PsfException.class)
        .hasMessageContaining("PSF-001");
  }

  @Test
  void anEmptyDocumentIsAParseError() {
    assertThatThrownBy(() -> PsfCodec.toIrJson("", TENANT))
        .isInstanceOf(PsfException.class)
        .hasMessageContaining("PSF-001");
  }

  /** A $ref with no fixture store is an error rather than an empty facts document. */
  @Test
  void aFactsRefWithoutAStoreIsRejected() {
    assertThatThrownBy(
            () ->
                PsfCodec.toIrJson(
                    """
                    policy: P
                    version: 1
                    productLines: [X]
                    stage: S
                    factSchema: lending-facts@1.0.0
                    tests:
                      - name: t
                        asOf: '2026-10-01'
                        facts: { $ref: fixtures/a.json }
                        expect: { decision: APPROVE }
                    """,
                    TENANT))
        .isInstanceOf(PsfException.class)
        .hasMessageContaining("fixture store");
  }

  // ---------------------------------------------------------------------------------------------

  /** A minimal one-rule policy with {@code extraRuleKeys} spliced into the rule. */
  private static ObjectNode translate(String extraRuleKeys) {
    return translate(extraRuleKeys, "");
  }

  private static ObjectNode translate(String extraRuleKeys, String extraSections) {
    String yaml =
        """
        policy: P
        version: 1
        productLines: [X]
        stage: CREDIT_UW
        factSchema: lending-facts@1.0.0
        rules:
          - code: R1
            name: A rule
            check: application.a > 0
            onFail: { status: FAIL, reasonCode: POL_A }
        %s
        flow:
          stages:
            - name: ONLY
              steps:
                - { ref: R1 }
        %s
        """
            .formatted(extraRuleKeys.isEmpty() ? "" : extraRuleKeys.stripTrailing(), extraSections);
    return PsfCodec.toIrJson(yaml, TENANT, PsfCodecTest::fixture);
  }
}
