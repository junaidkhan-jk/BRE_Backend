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
   * A repository path inside backticks. Anchored on the roots this repository actually has, so
   * prose in backticks — a column name, a parameter, a SQL fragment — is not mistaken for a path.
   *
   * <p><b>{@code ui/} is deliberately not in the list.</b> The studio moved to its own repository
   * (BRE_UI), so a citation of a UI file cannot be checked from here — and asserting it would fail
   * the build for everybody who cloned only the backend, which is most people. Those citations are
   * written {@code BRE_UI: src/…} in the documents so a reader can see they point elsewhere, and
   * the usability protocol says in its own text that they are unverified by CI. A check that cannot
   * run honestly is better removed than left to be worked around.
   */
  private static final Pattern REPO_PATH =
      Pattern.compile("`((?:backend|docs|devstack|reference-policy|BRE_PRD)/[A-Za-z0-9_./@-]+)`");

  /**
   * The documents whose claims are anchored to code, and therefore rot.
   *
   * <p>One list rather than one test per document, because the failure mode is identical in each:
   * the document names a file as evidence, somebody renames the file, and the document keeps
   * asserting. The compliance register is the one with a signature attached; the others are read by
   * a penetration tester, a pilot team and a session facilitator, and each of them is read ONCE, in
   * a situation where going and checking is not practical.
   */
  private static final List<String> EVIDENCE_DOCUMENTS =
      List.of(
          "docs/COMPLIANCE.md",
          "docs/USABILITY-PROTOCOL.md",
          "docs/PARALLEL-RUN.md",
          "docs/THREAT-MODEL.md",
          "docs/runbooks/dr-drill.md");

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
  @DisplayName("every file the evidence documents cite exists")
  void everyCitedPathExists() throws IOException {
    Path root = repoRoot();
    List<String> missing = new ArrayList<>();
    int cited = 0;

    for (String document : EVIDENCE_DOCUMENTS) {
      Path file = root.resolve(document);
      assertThat(file).as("%s is in the checked set but is not there", document).exists();

      Matcher matcher = REPO_PATH.matcher(Files.readString(file));
      while (matcher.find()) {
        cited++;
        String cite = matcher.group(1);
        // A trailing slash cites a package or directory, which is legitimate where a row points at
        // a group of classes rather than one.
        Path target =
            root.resolve(cite.endsWith("/") ? cite.substring(0, cite.length() - 1) : cite);
        if (!Files.exists(target)) {
          missing.add(document + " -> " + cite);
        }
      }
    }

    assertThat(cited)
        .as("no paths were cited at all, so this test is checking nothing")
        .isGreaterThan(30);
    assertThat(missing)
        .as(
            "%d cited path(s) do not exist. Each one is a document asserting that a control, a"
                + " mitigation or a procedure lives somewhere it does not. Fix the document, not"
                + " this test.",
            missing.size())
        .isEmpty();
  }

  @Test
  @DisplayName("the usability protocol's RK1 audit still matches what is built")
  void theRk1AuditIsStillTrue() throws IOException {
    Path root = repoRoot();
    String protocol = Files.readString(root.resolve("docs/USABILITY-PROTOCOL.md"));

    // The protocol's central claim is that three of RK1's six mitigations do not exist, and the
    // sessions are designed around that. If one gets built and the protocol is not updated, the
    // facilitator runs tasks aimed at deciding whether to build something that already exists —
    // and the session is spent on a question with no decision attached.
    assertThat(protocol).contains("Three of them do not exist");

    // The file-existence checks these replaced pointed into ui/studio-web, which is now the BRE_UI
    // repository. What survives the split is the claim itself: the protocol must keep saying which
    // mitigations are missing and that the grid is read-only, because a facilitator who runs Task 4
    // against a studio that has since gained an editable grid spends the session deciding whether
    // to build something that already exists. Verifying the UI side is a manual step, and the
    // protocol now says so in its own text rather than pretending CI covers it.
    assertThat(protocol)
        .as("the protocol must keep naming the three mitigations that do not exist")
        // Ignoring case: both phrases appear capitalised in the RK1 table's rows and lower-case
        // in the prose, and a case-sensitive assertion here fails on a document that says exactly
        // what it should.
        .containsIgnoringCase("policy-sentence view")
        .containsIgnoringCase("starter pack");
    assertThat(protocol)
        .as("Task 4's recommendation rests on the grid being read-only; if that changed, say so")
        .containsIgnoringCase("read-only");
    assertThat(protocol)
        .as("a reader must be able to see which citations CI cannot check")
        .contains("BRE_UI");
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
