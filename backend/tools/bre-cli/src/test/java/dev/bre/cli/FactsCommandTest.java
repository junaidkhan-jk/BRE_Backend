package dev.bre.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bre.facts.ProhibitedData;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The fact-schema validation CLI (M6-05), as an integrator's CI would run it. */
class FactsCommandTest {

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

  private Path factsFile(String json) throws Exception {
    Path file = tmp.resolve("facts.json");
    Files.writeString(file, json);
    return file;
  }

  private static final String VALID =
      """
      { "application": { "applicationRef": "APP-2026-000123", "productLine": "MFI_JLG",
                         "productCode": "JLGDL", "stage": "CREDIT_UW", "channel": "SLI",
                         "requestedAmount": "212500.00", "tenureMonths": 24,
                         "purpose": "WORKING_CAPITAL" } }
      """;

  @Test
  void aCleanDocumentExitsZero() throws Exception {
    Run result = run("facts", "validate", factsFile(VALID).toString());

    assertThat(result.exit()).isEqualTo(Main.EXIT_OK);
    assertThat(result.out()).contains("ok").contains("lending-facts@1.0.0");
  }

  /** The exit code IS the feature: a CI step that cannot fail is decoration. */
  @Test
  void anyFindingExitsNonZeroSoCiFails() throws Exception {
    Run result = run("facts", "validate", factsFile("{\"application\":{\"nope\": 1}}").toString());

    assertThat(result.exit()).isEqualTo(Main.EXIT_FINDINGS);
    assertThat(result.out()).contains("is not a field");
  }

  /**
   * The typo that is otherwise invisible: a JSON API accepts the field, the engine reads a missing
   * fact, and fail-closed turns it into an unexplained REFER in production.
   */
  @Test
  void aMistypedPathIsReportedWithASuggestion() throws Exception {
    Run result =
        run(
            "facts",
            "validate",
            factsFile("{\"application\":{\"requstedAmount\":\"1000.00\"}}").toString());

    assertThat(result.exit()).isEqualTo(Main.EXIT_FINDINGS);
    assertThat(result.out())
        .contains("application.requstedAmount")
        .contains("did you mean: application.requestedAmount");
  }

  /** Money as a JSON number loses precision before the engine ever sees it (§2.1 rule 4). */
  @Test
  void aDecimalSentAsAJsonNumberIsReported() throws Exception {
    Run result =
        run(
            "facts",
            "validate",
            factsFile("{\"application\":{\"requestedAmount\": 212500.00}}").toString());

    assertThat(result.exit()).isEqualTo(Main.EXIT_FINDINGS);
    assertThat(result.out()).contains("must be a JSON STRING").contains("loses precision");
  }

  /**
   * The finding that justifies the whole command. A raw Aadhaar is a compliance incident the moment
   * it is transmitted; catching it in the integrator's CI is the only place it is caught before it
   * exists anywhere else.
   */
  @Test
  void aRawAadhaarIsCaughtBeforeItIsEverSent() throws Exception {
    String aadhaar = "12345678901" + ProhibitedData.verhoeffCheckDigit("12345678901");
    Run result =
        run(
            "facts",
            "validate",
            factsFile("{\"parties\":[{\"ext\":{\"idNumber\":\"" + aadhaar + "\"}}]}").toString());

    assertThat(result.exit()).isEqualTo(Main.EXIT_FINDINGS);
    assertThat(result.out()).contains("raw Aadhaar").contains("vault reference or token");
  }

  /** §9.6, and most pointedly here: CI output is retained, searchable and widely readable. */
  @Test
  void noFindingEverEchoesAValue() throws Exception {
    String aadhaar = "99887766554" + ProhibitedData.verhoeffCheckDigit("99887766554");
    Run result =
        run(
            "facts",
            "validate",
            factsFile(
                    "{\"parties\":[{\"ext\":{\"idNumber\":\""
                        + aadhaar
                        + "\"}}],\"application\":{\"purpose\":\"SECRET_VALUE_XYZ\","
                        + "\"tenureMonths\":\"not-an-int\"}}")
                .toString());

    assertThat(result.exit()).isEqualTo(Main.EXIT_FINDINGS);
    assertThat(result.out())
        .as("the prohibited number must not be echoed into a CI log")
        .doesNotContain(aadhaar)
        .doesNotContain("99887766")
        .doesNotContain("SECRET_VALUE_XYZ");
    assertThat(result.out()).contains("No value from the document appears above");
  }

  @Test
  void aBarePanIsReported() throws Exception {
    Run result =
        run(
            "facts",
            "validate",
            factsFile("{\"parties\":[{\"ext\":{\"pan\":\"ABCDE1234F\"}}]}").toString());

    assertThat(result.exit()).isEqualTo(Main.EXIT_FINDINGS);
    assertThat(result.out()).contains("bare PAN").contains("token");
  }

  @Test
  void anEnumOutsideItsPermittedValuesIsReportedWithTheSet() throws Exception {
    Run result =
        run(
            "facts",
            "validate",
            factsFile("{\"parties\":[{\"role\":\"MANAGER\",\"type\":\"INDIVIDUAL\"}]}").toString());

    assertThat(result.exit()).isEqualTo(Main.EXIT_FINDINGS);
    assertThat(result.out()).contains("permitted values").contains("APPLICANT");
  }

  @Test
  void theSchemaCommandListsPathsTypesAndTags() {
    Run result = run("facts", "schema");

    assertThat(result.exit()).isEqualTo(Main.EXIT_OK);
    assertThat(result.out())
        .contains("application.requestedAmount")
        .contains("Decimal")
        .contains("parties[].bureauReports[].tradelines[].currentBalance")
        .contains("governance override")
        .contains("may never read this");
  }

  @Test
  void aMissingFileIsAUsageErrorNotACrash() {
    Run result = run("facts", "validate", tmp.resolve("absent.json").toString());

    assertThat(result.exit()).isEqualTo(Main.EXIT_USAGE);
    assertThat(result.err()).contains("could not be read as JSON");
  }

  @Test
  void helpIsAvailableAndSucceeds() {
    assertThat(run("--help").exit()).isEqualTo(Main.EXIT_OK);
    assertThat(run("--help").out()).contains("bre facts validate");
  }

  @Test
  void noArgumentsIsAUsageError() {
    assertThat(run().exit()).isEqualTo(Main.EXIT_USAGE);
  }

  @Test
  void anUnknownCommandIsAUsageError() {
    assertThat(run("psf", "lint").exit()).isEqualTo(Main.EXIT_USAGE);
    assertThat(run("facts", "frobnicate").exit()).isEqualTo(Main.EXIT_USAGE);
  }
}
