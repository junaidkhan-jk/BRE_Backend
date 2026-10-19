package dev.bre.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks that {@code docs/COMPLIANCE.md} still refers to things that exist (M6-07).
 *
 * <p>The register is the artefact a compliance officer signs, and its whole value is that each row
 * names the code implementing a control and the test proving it. That makes it a document full of
 * file paths — and a document full of file paths decays the first time somebody renames a class.
 *
 * <p><b>What decay looks like matters.</b> It is not a broken link. It is a register that reads
 * like evidence and asserts, in writing, that a regulatory control is proven by a test that no
 * longer exists. Nothing about reading the document reveals it; the signature happens anyway. So
 * the references are checked mechanically, in CI, and a rename that breaks one fails the build with
 * the path in the message — which is the moment it is cheap to fix.
 *
 * <p>What this cannot check is whether a named test actually proves the control it is cited for.
 * That is a judgement, and it is the reason the register cites a test rather than asserting a
 * conclusion: a reader can go and look.
 */
class ComplianceRegisterTest {

  /**
   * A repository path inside backticks. Anchored on the two roots the register cites, so prose in
   * backticks — a column name, a parameter, a SQL fragment — is not mistaken for a path.
   */
  private static final Pattern REPO_PATH =
      Pattern.compile("`((?:backend|docs|devstack|reference-policy)/[A-Za-z0-9_./@-]+)`");

  private static Path repoRoot() {
    String root = System.getProperty("bre.repo.root");
    assertThat(root).as("bre.repo.root must be set by the build").isNotNull();
    // bre.repo.root is the BACKEND Gradle root; the register and the policy live beside it.
    return Path.of(root).getParent();
  }

  private static Path register() {
    Path file = repoRoot().resolve("docs/COMPLIANCE.md");
    assertThat(file)
        .as(
            "docs/COMPLIANCE.md is the artefact M6-07 is signed against. If it has moved, this test"
                + " must move with it rather than quietly stop checking anything.")
        .exists();
    return file;
  }

  @Test
  @DisplayName("every file the register cites exists")
  void everyCitedPathExists() throws IOException {
    Path root = repoRoot();
    List<String> missing = new ArrayList<>();

    Matcher matcher = REPO_PATH.matcher(Files.readString(register()));
    int cited = 0;
    while (matcher.find()) {
      cited++;
      String cite = matcher.group(1);
      // A trailing slash cites a package or directory, which is legitimate in a row that points at
      // a group of classes rather than one.
      Path target = root.resolve(cite.endsWith("/") ? cite.substring(0, cite.length() - 1) : cite);
      if (!Files.exists(target)) {
        missing.add(cite);
      }
    }

    assertThat(cited)
        .as("the register cites no paths at all, so this test is checking nothing")
        .isGreaterThan(20);
    assertThat(missing)
        .as(
            "the compliance register names %d path(s) that do not exist. Each one is a row claiming"
                + " a regulatory control is implemented or proven somewhere it is not. Fix the"
                + " register, not this test.",
            missing.size())
        .isEmpty();
  }

  @Test
  @DisplayName("the register still states its gaps, so a clean-looking one is not trusted")
  void gapsAreStillDeclared() throws IOException {
    String text = Files.readString(register());

    // A register with no GAP rows is either a finished product or a document somebody tidied. At
    // this stage of the project it is the second, and the specific gap most likely to be quietly
    // dropped is the one with the largest consequence — so it is named here. When legal hold is
    // genuinely built, this assertion is the thing that makes somebody come and delete it
    // deliberately rather than the register drifting into looking complete.
    assertThat(text)
        .as(
            "FR-AUD-009 legal hold is a Phase-1 Must that is not built, and §10.4 and §10.6 both"
                + " rest on it. If it has been implemented, remove this assertion in the same"
                + " commit — on purpose.")
        .contains("legal hold");
    assertThat(text).contains("GAP");

    // The claim the whole document rests on. Without it the register is a self-assessment.
    assertThat(text)
        .as("the register must keep saying that it is checked, and by what")
        .contains("ComplianceRegisterTest");
  }

  @Test
  @DisplayName("the two regulatory parameters are in the reference parameter set")
  void regulatoryParametersExist() throws IOException {
    // §10.2 sets two limits, and the register's table promises both are in the reference set with
    // the values a compliance officer is confirming. The obligation cap was missing until M6-07
    // added it; this is what stops it going missing again, because removing a parameter is a
    // one-line edit in an asset file that nothing else would notice.
    String assets = Files.readString(repoRoot().resolve("reference-policy/assets.json"));
    assertThat(assets).contains("MFI_HH_ANNUAL_INCOME_MAX").contains("MFI_HH_OBLIGATION_CAP_PCT");

    String policy =
        Files.readString(repoRoot().resolve("reference-policy/mfi-jlg-credit-uw.psf.yaml"));
    assertThat(policy)
        .as("a regulatory parameter nothing reads is not a control")
        .contains("param.MFI_HH_ANNUAL_INCOME_MAX")
        .contains("param.MFI_HH_OBLIGATION_CAP_PCT");

    String registerText = Files.readString(register());
    assertThat(registerText)
        .contains("MFI_HH_ANNUAL_INCOME_MAX")
        .contains("MFI_HH_OBLIGATION_CAP_PCT");
  }
}
