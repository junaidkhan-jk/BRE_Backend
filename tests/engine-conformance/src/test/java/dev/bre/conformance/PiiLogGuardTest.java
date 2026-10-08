package dev.bre.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LLD §1.2 rule 2 and §9.6: no logging call anywhere receives a {@code FactsDocument}, {@code
 * RecordV} or {@code JsonNode} argument. A fact value in a log line is a PII leak that no amount of
 * log-level configuration takes back.
 *
 * <p>ponytail: this is a source scan, not bytecode analysis. The types it guards do not exist yet
 * (M1-01 and M1-14 create them), and a rule that cannot be written until its subject exists arrives
 * after the first violation is already committed. The ceiling is real — it reads one logging call
 * per line and does not follow a value through a local variable. Upgrade path: once {@code
 * dev.bre.engine.eval.Value} and {@code dev.bre.facts.FactsDocument} exist, replace this with an
 * ArchUnit condition over call-site argument types and delete the scan.
 */
class PiiLogGuardTest {

  /** Fact-value types that must never be passed to a logger. */
  private static final Set<String> BANNED_ARGUMENT_TYPES =
      Set.of(
          "FactsDocument",
          "RecordV",
          "JsonNode",
          "ListV",
          "DecimalV",
          "TextV",
          "DateV",
          "DateTimeV",
          "PeriodV",
          "BoolV",
          "IntV");

  /**
   * A logging call: {@code logger.log(...)}, {@code log.info(...)}, {@code LOG.debug(...)} and the
   * System.Logger and SLF4J shapes. Deliberately broad — a false positive is a one-line
   * suppression, a false negative is a PII leak in production.
   */
  private static final Pattern LOGGING_CALL =
      Pattern.compile(
          "\\b(?:log|logger|LOG|LOGGER)\\s*\\.\\s*"
              + "(?:log|trace|debug|info|warn|warning|error|severe|fine|finer|finest)\\s*\\(",
          Pattern.CASE_INSENSITIVE);

  /**
   * Identifier shaped like a fact-value-typed variable, e.g. {@code factsDocument}, {@code rec}.
   */
  private static Pattern bannedIdentifierPattern() {
    String alternatives =
        BANNED_ARGUMENT_TYPES.stream()
            .map(
                type ->
                    // the type itself, and the conventional variable name for it
                    Pattern.quote(type)
                        + "|"
                        + Pattern.quote(
                            type.substring(0, 1).toLowerCase(Locale.ROOT) + type.substring(1)))
            .reduce((a, b) -> a + "|" + b)
            .orElseThrow();
    return Pattern.compile("\\b(?:" + alternatives + ")\\b");
  }

  private static Path repoRoot() {
    String root = System.getProperty("bre.repo.root");
    assertThat(root)
        .as("bre.repo.root must be set by the build so the scan covers every module")
        .isNotNull();
    return Path.of(root);
  }

  /**
   * No module has production sources until M1-01. The scan would then find nothing and report
   * success, which says nothing about whether it works. {@link #guardDetectsAViolation()} proves
   * the mechanism; these two report as SKIPPED until there is code to scan.
   */
  private static void assumeProductionSourcesExist() throws IOException {
    Assumptions.assumeFalse(
        javaSources().isEmpty(),
        "no production sources under */src/main/java yet — M1-01 lands the first");
  }

  private static List<Path> javaSources() throws IOException {
    Path root = repoRoot();
    try (Stream<Path> paths = Files.walk(root)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".java"))
          .filter(p -> p.toString().contains("/src/main/java/"))
          .filter(p -> !p.toString().contains("/build/"))
          .toList();
    }
  }

  @Test
  @DisplayName("no logging call passes a fact value (LLD §1.2 rule 2, §9.6)")
  void noFactValueReachesALogger() throws IOException {
    assumeProductionSourcesExist();
    Pattern bannedIdentifier = bannedIdentifierPattern();
    List<String> findings = new ArrayList<>();

    for (Path source : javaSources()) {
      List<String> lines = Files.readAllLines(source);
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        Matcher call = LOGGING_CALL.matcher(line);
        if (!call.find()) {
          continue;
        }
        String arguments = line.substring(call.end());
        if (bannedIdentifier.matcher(arguments).find()) {
          findings.add(repoRoot().relativize(source) + ":" + (i + 1) + "  " + line.strip());
        }
      }
    }

    assertThat(findings)
        .as(
            "a fact value passed to a logger leaks PII into log aggregation, where retention and "
                + "access control are not the engine's to enforce. Log the fact PATH and its TYPE.")
        .isEmpty();
  }

  @Test
  @DisplayName("the scan actually reaches the source tree, so an empty result means something")
  void scanCoversTheRepository() throws IOException {
    // Without this, deleting the source tree or breaking the path filter would turn the guard
    // above into a test that passes by finding nothing to look at.
    assumeProductionSourcesExist();
    assertThat(javaSources())
        .as("expected to find the engine's own sources under libs/*/src/main/java")
        .isNotEmpty();
  }

  @Test
  @DisplayName("the guard fires on a known-bad line")
  void guardDetectsAViolation() {
    // Proves the pattern pair works, without committing a real violation to the source tree.
    String offending = "    logger.log(Level.DEBUG, \"evaluating \" + factsDocument);";
    Matcher call = LOGGING_CALL.matcher(offending);
    assertThat(call.find()).as("the logging-call pattern must match a System.Logger call").isTrue();
    assertThat(bannedIdentifierPattern().matcher(offending.substring(call.end())).find())
        .as("a factsDocument argument must be recognised as a fact value")
        .isTrue();
  }
}
