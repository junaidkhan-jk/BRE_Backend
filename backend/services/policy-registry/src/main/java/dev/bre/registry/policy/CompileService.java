package dev.bre.registry.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.bre.engine.check.CheckFinding;
import dev.bre.engine.check.StaticChecks;
import dev.bre.engine.cover.CoverageProbe;
import dev.bre.engine.cover.CoverageReport;
import dev.bre.engine.decide.FlowEvaluator;
import dev.bre.engine.eval.EvaluationContext;
import dev.bre.engine.eval.Value;
import dev.bre.engine.ir.Ir;
import dev.bre.facts.FactReader;
import dev.bre.facts.LendingFacts;
import dev.bre.irjson.IrCodec;
import dev.bre.irjson.IrSchemaValidator;
import dev.bre.irjson.PsfCodec;
import dev.bre.irjson.PsfException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Compiles a saved draft and runs its test cases (M2-03, §2.4 M9).
 *
 * <p>Four stages, and each one can only report what the one before it allowed:
 *
 * <ol>
 *   <li><b>PSF → IR.</b> A YAML error stops here, and nothing else can be said about the policy.
 *   <li><b>IR schema.</b> A document that translates but is not a legal PolicyVersion (§2.2).
 *   <li><b>Static checks.</b> Decision-table gaps, overlaps, unreachable rows, and the rest of
 *       §4.13's catalogue. These are the findings a reviewer should never have to find by reading.
 *   <li><b>Test cases.</b> The author's own assertions, run against the real engine — with a
 *       coverage probe attached, so the same run that answers "do they pass" also answers "do they
 *       touch anything" (FR-TEST-003).
 * </ol>
 *
 * <p><b>Every stage's failure is a finding, not an exception.</b> An author mid-edit must be able
 * to save a policy that does not parse — losing an hour's work because a bracket is unbalanced is
 * not an acceptable editor — so this returns a report and the caller decides. The submit gate is
 * where a bad report becomes a refusal.
 *
 * <p><b>Test cases run against the same engine the decision service uses</b>, through the same
 * {@link FactReader} and the same resolved assets. A simulator that was merely similar would let a
 * policy pass its tests here and behave differently in production, which is worse than having no
 * tests: it would be a green signal with no backing.
 */
@Service
public class CompileService {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final FactReader FACT_READER = new FactReader(LendingFacts.schema());

  private final dev.bre.registry.sign.TenantAssets assets;

  /**
   * Creates the compiler.
   *
   * @param assets resolves the tenant's parameter sets and reference lists, so test cases run
   *     against the thresholds the bundle will actually carry
   */
  public CompileService(dev.bre.registry.sign.TenantAssets assets) {
    this.assets = assets;
  }

  /** A test case as stored (§5.2 {@code test_cases}). */
  public record TestCase(String name, java.time.LocalDate asOf, JsonNode facts, JsonNode expect) {}

  /**
   * What the test run established.
   *
   * @param total how many ran
   * @param passed how many passed
   * @param failures a sentence per failure, naming the case and the disagreement
   */
  public record TestResults(int total, int passed, List<String> failures) {

    /** Results for a policy whose source did not translate, so nothing could run. */
    public static TestResults none() {
      return new TestResults(0, 0, List.of());
    }

    /** Creates results, copying the list. */
    public TestResults {
      failures = List.copyOf(failures);
    }
  }

  /**
   * A compile report.
   *
   * @param ir the translated IR, absent when the source did not translate
   * @param findings every static-check finding, plus translation and schema errors as findings
   * @param tests the test-case results
   * @param coverage what those test cases exercised
   * @param factSchemaCode the fact schema the policy declares
   * @param factSchemaVersion its version
   */
  public record Result(
      Optional<ObjectNode> ir,
      List<Finding> findings,
      TestResults tests,
      CoverageReport coverage,
      String factSchemaCode,
      String factSchemaVersion) {

    /** Creates a result, copying the list. */
    public Result {
      findings = List.copyOf(findings);
    }

    /** Whether the policy may be submitted: no error-severity finding. */
    public boolean ok() {
      return findings.stream().noneMatch(f -> "ERROR".equals(f.severity()));
    }

    /** How many findings are errors. */
    public long errorCount() {
      return findings.stream().filter(f -> "ERROR".equals(f.severity())).count();
    }
  }

  /**
   * One finding.
   *
   * @param code the catalogue code, e.g. {@code CHK-001} or {@code PSF-002}
   * @param severity {@code ERROR}, {@code WARN} or {@code INFO}
   * @param path where in the document
   * @param message what is wrong — naming paths and rule codes, never fact values (§9.6)
   */
  public record Finding(String code, String severity, String path, String message) {}

  /**
   * Compiles PSF source and runs the stored test cases.
   *
   * @param tenantId the importing tenant; PSF carries no tenant, which is what makes a policy
   *     portable between installations (§2.6)
   * @param policyCode the policy, for messages
   * @param version the version, for messages
   * @param psfYaml the source
   * @param testCases the stored test cases
   * @return the report
   */
  public Result compile(
      String tenantId, String policyCode, int version, String psfYaml, List<TestCase> testCases) {

    List<Finding> findings = new ArrayList<>();

    ObjectNode ir;
    try {
      ir = PsfCodec.toIrJson(psfYaml, tenantId);
    } catch (PsfException e) {
      findings.add(new Finding(e.code().wire(), "ERROR", e.path(), e.getMessage()));
      return new Result(
          Optional.empty(), findings, TestResults.none(), CoverageReport.empty(), "", "");
    } catch (RuntimeException e) {
      // Any other translation failure. Reported rather than propagated: a 500 from an editor's
      // save button loses the author's work.
      findings.add(
          new Finding(
              "PSF-001",
              "ERROR",
              "",
              "the policy source could not be translated: " + e.getMessage()));
      return new Result(
          Optional.empty(), findings, TestResults.none(), CoverageReport.empty(), "", "");
    }

    List<String> schemaViolations = IrSchemaValidator.violations(ir);
    schemaViolations.forEach(v -> findings.add(new Finding("IR-SCHEMA", "ERROR", "", v)));
    if (!schemaViolations.isEmpty()) {
      // No point running static checks on a document that is not a legal PolicyVersion; the
      // decoder would either throw or silently read defaults, and both produce misleading findings.
      return new Result(
          Optional.of(ir),
          findings,
          TestResults.none(),
          CoverageReport.empty(),
          schemaCode(ir),
          schemaVersion(ir));
    }

    Ir.PolicyIr policy = IrCodec.readPolicy(ir);
    for (CheckFinding finding : StaticChecks.run(policy)) {
      findings.add(
          new Finding(
              finding.code(),
              finding.severity().name(),
              // The component the finding is about — a rule code, a table code — which is what an
              // author needs to find it. CheckFinding has no document path: the static checks run
              // over the decoded IR, where a component is identified by its code rather than by
              // where it happened to sit in the YAML.
              finding.component(),
              finding.witnesses().isEmpty()
                  ? finding.message()
                  : finding.message() + " (e.g. " + finding.witnesses().getFirst() + ")"));
    }

    // Resolved the same way the bundle builder resolves them, through the same component. Running
    // tests with empty parameters made the reference policy — which reads nineteen of them — refer
    // every applicant, so every test failed with "expected APPROVE, got REFER" against a policy
    // that was correct.
    dev.bre.registry.sign.TenantAssets.Resolved resolved =
        assets.resolve(tenantId, ir.get("assets"));
    if (!resolved.present() && dev.bre.registry.sign.TenantAssets.declaresAssets(ir)) {
      findings.add(
          new Finding(
              "ASSET-000",
              "WARN",
              "assets",
              "this policy names a parameter set or reference lists and this installation has no"
                  + " asset source for the tenant, so every param. and list. reference is Missing."
                  + " Test cases will refer rather than decide."));
    }
    resolved
        .authorityMatrix()
        .ifPresent(matrix -> ((ObjectNode) ir.get("assets")).set("authorityMatrix", matrix));

    // One probe for the whole suite, not one per case: coverage is a property of the suite. The
    // same object threads through every derived context — per entity, per filter element — because
    // `measuring` passes the reference rather than a copy.
    CoverageProbe probe = new CoverageProbe();
    Ir.PolicyIr compiled = IrCodec.readPolicy(ir);
    TestResults tests =
        runTests(compiled, testCases, resolved.parameters(), resolved.lists(), probe);
    return new Result(
        Optional.of(ir),
        findings,
        tests,
        CoverageReport.measure(compiled, probe),
        schemaCode(ir),
        schemaVersion(ir));
  }

  private static TestResults runTests(
      Ir.PolicyIr policy,
      List<TestCase> testCases,
      Map<String, Value> parameters,
      Map<String, Value> lists,
      CoverageProbe probe) {
    List<String> failures = new ArrayList<>();
    int passed = 0;
    for (TestCase test : testCases) {
      try {
        Value.RecordV facts = FACT_READER.toRecord(toMap(test.facts()));
        FlowEvaluator.DecisionResult result =
            FlowEvaluator.evaluate(
                policy,
                // The tenant's resolved parameters and lists — the same ones the bundle will be
                // signed with. A test run against different thresholds from the deployed ones is
                // not a test of anything.
                EvaluationContext.of(facts, parameters, lists, test.asOf()).measuring(probe));

        String expectedDecision = test.expect().path("decision").asText("");
        if (!expectedDecision.isEmpty() && !expectedDecision.equals(result.decision().name())) {
          failures.add(
              test.name()
                  + ": expected decision "
                  + expectedDecision
                  + ", got "
                  + result.decision().name());
          continue;
        }

        // Per-step expectations, when the case states any. Checked after the decision, because a
        // decision that is right for the wrong reasons is still a failure worth naming precisely.
        boolean stepsOk = true;
        for (var expected : test.expect().path("steps").properties()) {
          String actual =
              result
                  .step(expected.getKey())
                  .map(step -> step.status().name())
                  .orElse("<did not run>");
          if (!expected.getValue().asText().equals(actual)) {
            failures.add(
                test.name()
                    + ": expected "
                    + expected.getKey()
                    + " = "
                    + expected.getValue().asText()
                    + ", got "
                    + actual);
            stepsOk = false;
          }
        }
        if (stepsOk) {
          passed++;
        }
      } catch (RuntimeException e) {
        // A test that throws is a failing test, not a broken compile. The message names the case.
        failures.add(
            test.name() + ": threw " + e.getClass().getSimpleName() + " — " + e.getMessage());
      }
    }
    return new TestResults(testCases.size(), passed, failures);
  }

  private static Map<String, Object> toMap(JsonNode facts) {
    return JSON.convertValue(
        facts, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
  }

  private static String schemaCode(JsonNode ir) {
    return ir.path("factSchema").path("code").asText("lending-facts");
  }

  private static String schemaVersion(JsonNode ir) {
    return ir.path("factSchema").path("version").asText("1.0.0");
  }
}
