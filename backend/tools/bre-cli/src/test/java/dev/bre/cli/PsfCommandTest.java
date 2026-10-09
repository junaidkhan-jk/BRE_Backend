package dev.bre.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The offline policy toolchain (M1-15). */
class PsfCommandTest {

  @TempDir Path tmp;

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

  private static final String HEADER =
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
      """;

  private Path policy(String body) throws Exception {
    Path file = tmp.resolve("policy.psf.yaml");
    Files.writeString(file, HEADER + body);
    return file;
  }

  /** A table covering every present value. */
  private static final String TOTAL_TABLE =
      """
      decisionTables:
        - code: GRP_VRM_CATEGORY
          scope: GROUP
          hitPolicy: FIRST
          inputs:
            - { name: vrm, expr: group.villageRiskCategory }
          rows:
            - { when: ['in ["A","B"]'], then: { status: PASS } }
            - { when: ['-'],            then: { status: REFER, reasonCode: POL_VRM_UNKNOWN } }
      flow:
        stages:
          - name: ELIGIBILITY
            steps:
              - { ref: GRP_VRM_CATEGORY }
      """;

  /** The same table with the catch-all removed, so D, E and anything unlisted match no row. */
  private static final String GAPPED_TABLE =
      """
      decisionTables:
        - code: GRP_VRM_CATEGORY
          scope: GROUP
          hitPolicy: FIRST
          inputs:
            - { name: vrm, expr: group.villageRiskCategory }
          rows:
            - { when: ['in ["A","B"]'], then: { status: PASS } }
      flow:
        stages:
          - name: ELIGIBILITY
            steps:
              - { ref: GRP_VRM_CATEGORY }
      """;

  @Nested
  @DisplayName("psf lint")
  class Lint {

    @Test
    void aCleanPolicyPasses() throws Exception {
      Run result = run("psf", "lint", policy(TOTAL_TABLE).toString());

      assertThat(result.exit()).isEqualTo(Main.EXIT_OK);
      assertThat(result.out()).contains("passes every implemented static check");
    }

    /** The finding an analyst most needs before submitting, and cannot otherwise see. */
    @Test
    void aTableGapIsReportedWithAWitnessAndFailsTheExit() throws Exception {
      Run result = run("psf", "lint", policy(GAPPED_TABLE).toString());

      assertThat(result.exit()).isEqualTo(Main.EXIT_FINDINGS);
      assertThat(result.out())
          .contains("CHK-002")
          .contains("ERROR")
          .contains("refers the application")
          .contains("OTHER");
    }

    /** "No findings" must never be confusable with "nothing was looked at". */
    @Test
    void whatWasNotCheckedIsAlwaysReported() throws Exception {
      Run result = run("psf", "lint", policy(TOTAL_TABLE).toString());

      assertThat(result.out())
          .contains("Not checked by this build")
          .contains("CHK-021")
          .contains("needs the test runner");
    }

    @Test
    void aMalformedPolicyIsAFindingNotACrash() throws Exception {
      Path broken = tmp.resolve("broken.psf.yaml");
      Files.writeString(broken, "policy: X\nversion: not-an-integer\n");

      Run result = run("psf", "lint", broken.toString());
      assertThat(result.exit()).isEqualTo(Main.EXIT_FINDINGS);
      assertThat(result.out()).contains("PSF-");
    }

    @Test
    void aSchemaViolationIsReported() throws Exception {
      Path incomplete = tmp.resolve("incomplete.psf.yaml");
      Files.writeString(
          incomplete,
          """
          policy: X
          version: 1
          productLines: [A]
          stage: S
          factSchema: lending-facts@1.0.0
          """);

      Run result = run("psf", "lint", incomplete.toString());
      assertThat(result.exit()).isEqualTo(Main.EXIT_FINDINGS);
      assertThat(result.out()).contains("schema violation");
    }
  }

  @Nested
  @DisplayName("psf compile")
  class Compile {

    @Test
    void itWritesIrJson() throws Exception {
      Path out = tmp.resolve("out.ir.json");
      Run result = run("psf", "compile", policy(TOTAL_TABLE).toString(), "-o", out.toString());

      assertThat(result.exit()).isEqualTo(Main.EXIT_OK);
      assertThat(Files.readString(out)).contains("\"irVersion\" : 1").contains("GRP_VRM_CATEGORY");
    }
  }

  @Nested
  @DisplayName("psf bundle and verify")
  class BundleAndVerify {

    private Path keys() throws Exception {
      Path priv = tmp.resolve("k.pkcs8");
      PsfCommands.writeKeyPair(priv, tmp.resolve("k.x509"));
      return priv;
    }

    @Test
    void aSignedBundleVerifiesWithItsKey() throws Exception {
      Path priv = keys();
      Path bundle = tmp.resolve("bundle.json");

      Run signed =
          run(
              "psf",
              "bundle",
              policy(TOTAL_TABLE).toString(),
              "--key",
              priv.toString(),
              "--kid",
              "dev-2026",
              "-o",
              bundle.toString());
      assertThat(signed.exit()).isEqualTo(Main.EXIT_OK);
      assertThat(signed.out()).contains("sha256:");

      Run verified =
          run(
              "psf",
              "verify",
              bundle.toString(),
              "--pub",
              tmp.resolve("k.x509").toString(),
              "--kid",
              "dev-2026");
      assertThat(verified.exit()).isEqualTo(Main.EXIT_OK);
      assertThat(verified.out()).contains("signed by dev-2026 as ORIGIN");
    }

    /**
     * A signature is what everything downstream trusts INSTEAD of looking. Putting one on a policy
     * with a known gap is worse than leaving it unsigned.
     */
    @Test
    void itRefusesToSignAPolicyWithAStaticCheckError() throws Exception {
      Run result =
          run(
              "psf",
              "bundle",
              policy(GAPPED_TABLE).toString(),
              "--key",
              keys().toString(),
              "--kid",
              "dev-2026",
              "-o",
              tmp.resolve("b.json").toString());

      assertThat(result.exit()).isEqualTo(Main.EXIT_FINDINGS);
      assertThat(result.out()).contains("refusing to bundle").contains("CHK-002");
      assertThat(Files.exists(tmp.resolve("b.json")))
          .as("nothing is written when signing is refused")
          .isFalse();
    }

    @Test
    void aTamperedBundleFailsVerification() throws Exception {
      Path priv = keys();
      Path bundle = tmp.resolve("bundle.json");
      run(
          "psf",
          "bundle",
          policy(TOTAL_TABLE).toString(),
          "--key",
          priv.toString(),
          "--kid",
          "dev-2026",
          "-o",
          bundle.toString());

      // Swap the first row's outcome, keeping the hash and signature exactly as they were.
      String tampered = Files.readString(bundle).replaceFirst("\"PASS\"", "\"FAIL\"");
      Files.writeString(bundle, tampered);

      Run result =
          run(
              "psf",
              "verify",
              bundle.toString(),
              "--pub",
              tmp.resolve("k.x509").toString(),
              "--kid",
              "dev-2026");
      assertThat(result.exit()).isEqualTo(Main.EXIT_FINDINGS);
      assertThat(result.out()).contains("FAILED").contains("HASH_MISMATCH");
    }

    @Test
    void anotherKeyDoesNotVerify() throws Exception {
      Path bundle = tmp.resolve("bundle.json");
      run(
          "psf",
          "bundle",
          policy(TOTAL_TABLE).toString(),
          "--key",
          keys().toString(),
          "--kid",
          "dev-2026",
          "-o",
          bundle.toString());

      Path otherPublic = tmp.resolve("other.x509");
      PsfCommands.writeKeyPair(tmp.resolve("other.pkcs8"), otherPublic);

      Run result =
          run(
              "psf",
              "verify",
              bundle.toString(),
              "--pub",
              otherPublic.toString(),
              "--kid",
              "dev-2026");
      assertThat(result.exit()).isEqualTo(Main.EXIT_FINDINGS);
      assertThat(result.out()).contains("BAD_SIGNATURE");
    }
  }

  @Nested
  @DisplayName("usage")
  class Usage {

    @Test
    void helpListsThePsfCommands() {
      assertThat(run("--help").out())
          .contains("bre psf lint")
          .contains("bre psf bundle")
          .contains("bre keygen");
    }

    @Test
    void missingArgumentsAreUsageErrors() {
      assertThat(run("psf").exit()).isEqualTo(Main.EXIT_USAGE);
      assertThat(run("psf", "lint").exit()).isEqualTo(Main.EXIT_USAGE);
      assertThat(run("psf", "frobnicate").exit()).isEqualTo(Main.EXIT_USAGE);
      assertThat(run("keygen").exit()).isEqualTo(Main.EXIT_USAGE);
    }

    @Test
    void keygenSaysTheKeyIsForLocalWorkOnly() {
      Run result =
          run("keygen", tmp.resolve("p.pkcs8").toString(), tmp.resolve("p.x509").toString());

      assertThat(result.exit()).isEqualTo(Main.EXIT_OK);
      assertThat(result.out()).contains("LOCAL WORK ONLY").contains("never leaves");
    }
  }
}
