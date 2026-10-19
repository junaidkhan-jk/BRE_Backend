package dev.bre.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The parallel-run harness (M6-06), as a pilot team would drive it. */
class ParallelRunCommandTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path tmp;

  private Path bundle;
  private Path publicKey;

  private record Run(int exit, String out, String err) {}

  private Run run(String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int exit =
        Main.run(
            args,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    return new Run(
        exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  /**
   * A policy that declines a group whose village risk category is not A or B.
   *
   * <p>One input and one table, because what is under test is the comparison and the grouping, not
   * the engine. A realistic policy would make a failure here ambiguous between the two.
   */
  private static final String POLICY =
      """
      policy: MFI_JLG_CREDIT_UW
      version: 3
      productLines: [MFI_JLG]
      stage: CREDIT_UW
      factSchema: lending-facts@1.0.0
      assets:
        parameterSet: MFI_PARAMS@7
        referenceLists: []
        reasonCodes: CORE_REASONS@5
        authorityMatrix: MFI_DOP@3
      decisionTables:
        - code: GRP_VRM_CATEGORY
          scope: GROUP
          hitPolicy: FIRST
          inputs:
            - { name: vrm, expr: group.villageRiskCategory }
          rows:
            - { when: ['in ["A","B"]'], then: { status: PASS } }
            - { when: ['-'],            then: { status: FAIL, reasonCode: POL_VRM_EXCLUDED } }
      flow:
        stages:
          - name: ELIGIBILITY
            knockout: true
            steps:
              - { ref: GRP_VRM_CATEGORY }
      """;

  @BeforeEach
  void signABundle() throws Exception {
    Path policy = tmp.resolve("policy.psf.yaml");
    Files.writeString(policy, POLICY);
    Path privateKey = tmp.resolve("k.pkcs8");
    publicKey = tmp.resolve("k.x509");
    PsfCommands.writeKeyPair(privateKey, publicKey);
    bundle = tmp.resolve("bundle.json");
    Run signed =
        run(
            "psf",
            "bundle",
            policy.toString(),
            "--key",
            privateKey.toString(),
            "--kid",
            "dev-2026",
            "-o",
            bundle.toString());
    assertThat(signed.exit()).as(signed.err()).isEqualTo(Main.EXIT_OK);
  }

  /**
   * @param category A or B passes; anything else is declined.
   */
  private static String facts(String reference, String category, String theirDecision) {
    return """
        {"applicationRef":"%s","asOf":"2026-10-01","theirDecision":"%s",
         "facts":{"group":{"groupRef":"GRP-1","type":"JLG","villageRiskCategory":"%s"}}}"""
        .formatted(reference, theirDecision, category)
        .replace("\n", " ");
  }

  private Path cases(String... lines) throws Exception {
    Path file = tmp.resolve("cases.jsonl");
    Files.writeString(file, String.join("\n", lines) + "\n");
    return file;
  }

  private Run parallelRun(Path cases, String... extra) {
    List<String> args =
        new java.util.ArrayList<>(
            List.of(
                "parallel-run", bundle.toString(),
                "--cases", cases.toString(),
                "--pub", publicKey.toString(),
                "--kid", "dev-2026"));
    args.addAll(List.of(extra));
    return run(args.toArray(String[]::new));
  }

  @Test
  @DisplayName("cases the lender's system decided the same way agree")
  void agreementIsReported() throws Exception {
    Run result = parallelRun(cases(facts("APP-1", "A", "APPROVE"), facts("APP-2", "C", "DECLINE")));

    assertThat(result.exit()).isEqualTo(Main.EXIT_OK);
    assertThat(result.out()).contains("cases compared  2").contains("agree                2");
    assertThat(result.out()).contains("Every case agreed");
  }

  @Test
  @DisplayName("a difference names the step and the reason that caused it")
  void aDifferenceIsLocalised() throws Exception {
    // Their system approved a group this policy excludes. That is the whole point of the pilot: the
    // report has to say WHERE the two disagree, because "1 difference" sends somebody to read the
    // policy from the top.
    Run result = parallelRun(cases(facts("APP-1", "C", "APPROVE")));

    assertThat(result.out())
        .contains("differ               1")
        .contains("GRP_VRM_CATEGORY")
        .contains("POL_VRM_EXCLUDED");
  }

  @Test
  @DisplayName("a thousand cases differing for one reason are one line, not a thousand")
  void differencesAreGroupedByCause() throws Exception {
    // The design claim. Four weeks of MFI volume with one policy gap produces a difference on every
    // application, and a register with one row per application is not read by anybody — so the
    // criterion gets signed off against a document nobody examined.
    String[] lines = new String[1000];
    for (int i = 0; i < lines.length; i++) {
      lines[i] = facts("APP-" + i, "C", "APPROVE");
    }
    Path register = tmp.resolve("register.json");
    Run result = parallelRun(cases(lines), "--out", register.toString());

    assertThat(result.out()).contains("differ            1000");
    JsonNode root = MAPPER.readTree(Files.readString(register));
    assertThat(root.get("differences")).hasSize(1);
    assertThat(root.get("differences").get(0).get("cases").asInt()).isEqualTo(1000);
    assertThat(root.get("differences").get(0).get("exampleApplicationRefs"))
        .as("examples enough to look one up, not a dump of all thousand")
        .hasSize(5);
    assertThat(root.get("differences").get(0).get("resolution").asText())
        .as("left blank for the lender — a pre-filled guess is a field somebody signs unread")
        .isEmpty();
  }

  @Test
  @DisplayName("facts that do not match the schema are a DATA gap, not a policy difference")
  void invalidFactsAreIncomparable() throws Exception {
    // §4.8 is fail-closed, so an unmappable field refers — which reads exactly like a credit
    // disagreement. Counting it as one sends a committee to argue about policy over a CSV column.
    Run result =
        parallelRun(
            cases(
                """
                {"applicationRef":"APP-1","asOf":"2026-10-01","theirDecision":"APPROVE",
                 "facts":{"group":{"groupRef":"GRP-1","type":"NOT_A_GROUP_TYPE"}}}"""
                    .replace("\n", " ")));

    assertThat(result.out()).contains("incomparable         1").contains("INVALID_FACTS");
    assertThat(result.out()).doesNotContain("differ               1");
  }

  @Test
  @DisplayName("a case with no asOf is refused rather than decided at today's date")
  void aMissingAsOfIsNotDefaultedToToday() throws Exception {
    // Deciding a four-week-old application at today's parameters produces a difference caused by
    // the run's own clock, indistinguishable in the report from one caused by the policy.
    Run result =
        parallelRun(
            cases(
                """
                {"applicationRef":"APP-1","theirDecision":"APPROVE",
                 "facts":{"group":{"groupRef":"GRP-1","type":"JLG","villageRiskCategory":"A"}}}"""
                    .replace("\n", " ")));

    assertThat(result.out()).contains("incomparable         1").contains("NO_AS_OF");
  }

  @Test
  @DisplayName("no fact value reaches the report (§9.6)")
  void theReportCarriesNoFactValues() throws Exception {
    // A parallel-run report circulates to a credit committee by email, which is the least
    // controlled channel in the pilot. The value that triggered a difference is exactly the field
    // that would be personal data, so the report names the step and the reason and the reader
    // looks the case up in the access-controlled decision record.
    Path register = tmp.resolve("register.json");
    Run result =
        parallelRun(
            cases(
                """
                {"applicationRef":"APP-1","asOf":"2026-10-01","theirDecision":"APPROVE",
                 "facts":{"group":{"groupRef":"GRP-SECRET-7781","type":"JLG",
                                   "villageRiskCategory":"D"}}}"""
                    .replace("\n", " ")),
            "--out",
            register.toString());

    assertThat(result.out())
        .as("the group reference is a fact value and must not appear")
        .doesNotContain("GRP-SECRET-7781");
    assertThat(Files.readString(register)).doesNotContain("GRP-SECRET-7781");
    // applicationRef is the caller's own key for the case and IS in the report, deliberately: a
    // difference nobody can look up cannot be resolved.
    assertThat(Files.readString(register)).contains("APP-1");
  }

  @Test
  @DisplayName("a line that is not JSON is skipped, and the rest of the run still counts")
  void oneBadLineDoesNotEndTheRun() throws Exception {
    // Twenty thousand cases in, the twenty-thousand-and-first is not a reason to lose the first
    // twenty thousand.
    Run result =
        parallelRun(
            cases(facts("APP-1", "A", "APPROVE"), "{not json", facts("APP-2", "A", "APPROVE")));

    assertThat(result.exit()).isEqualTo(Main.EXIT_OK);
    assertThat(result.out()).contains("cases compared  2").contains("unreadable           1");
    assertThat(result.err()).contains("line 2");
  }

  @Test
  @DisplayName("both agreement rates are reported, because a committee will quote one of them")
  void bothRatesAreReported() throws Exception {
    Path register = tmp.resolve("register.json");
    Run result =
        parallelRun(
            cases(
                facts("APP-1", "A", "APPROVE"),
                facts("APP-2", "C", "APPROVE"),
                """
                {"applicationRef":"APP-3","theirDecision":"APPROVE",
                 "facts":{"group":{"groupRef":"G","type":"JLG","villageRiskCategory":"A"}}}"""
                    .replace("\n", " ")),
            "--out",
            register.toString());

    // 1 of 3 agreed; 1 of the 2 that could be compared.
    assertThat(result.out()).contains("33.33% of all 3 cases").contains("50.00% of the 2");
    JsonNode root = MAPPER.readTree(Files.readString(register));
    assertThat(root.get("agreementRate").asDouble()).isEqualTo(33.33);
    assertThat(root.get("agreementRateOverComparable").asDouble()).isEqualTo(50.0);
  }
}
