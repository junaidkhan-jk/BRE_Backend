package dev.bre.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.bre.bundle.BundleException;
import dev.bre.engine.decide.ComponentResult;
import dev.bre.engine.decide.FlowEvaluator;
import dev.bre.engine.ir.Ir.RuntimeStatus;
import dev.bre.facts.FactsValidator;
import dev.bre.facts.LendingFacts;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code bre parallel-run <bundle.json> --cases <cases.jsonl> --pub <k> --kid <id>} (M6-06).
 *
 * <p>The parallel run PRD §12.1 exit criterion 3 asks for: at least four weeks of decisions taken
 * against the pilot lender's documented policy, with <b>every outcome difference explained</b>. The
 * four weeks and the lender are not something code supplies. What code can supply is the part that
 * decides whether those four weeks produce a signed-off result or an unreadable spreadsheet.
 *
 * <p><b>The grouping is the whole design, not a convenience.</b> Four weeks of MFI volume is tens
 * of thousands of applications, and a single policy gap produces a difference on every one of them.
 * A register with 40,000 rows is not evidence — nobody reads it, so the criterion gets met on paper
 * by a document that was never examined. Differences are therefore grouped by their <em>cause</em>
 * — the pair of outcomes, the step that decided it, and that step's reason code — which turns tens
 * of thousands of rows into the handful of causes a credit committee can actually work through,
 * each with a count and a few example references. The count is what tells them which one to fix
 * first.
 *
 * <p><b>A difference and a data gap are not the same finding, and conflating them wastes the
 * pilot.</b> §4.8 is fail-closed: a missing or unevaluable fact refers rather than declines, so a
 * case where the lender's export did not carry a fact this policy needs shows up as REFER against
 * their APPROVE and reads exactly like a policy disagreement. It is not one — it is a field mapping
 * to fix in the extract, and in practice it is most of what the first week finds. So those cases
 * are classified {@code INCOMPARABLE} and reported separately, with the fact paths that were
 * missing. Counting them as policy differences would make the policy look wrong and send a
 * committee to argue about credit criteria over a CSV column.
 *
 * <p><b>No fact values appear in the output</b> (§9.6). Paths, step codes, reason codes, decisions
 * and counts only. A parallel-run report circulates by email to a credit committee, which is the
 * least controlled channel in the whole pilot, and the measured value that triggered a difference
 * is exactly the field that would be personal data. Where a measure would be useful — "declined at
 * a ratio of 0.58" — the report names the step and the reason and the reader looks it up in the
 * decision record, which is access-controlled.
 *
 * <p>What this cannot do is <em>resolve</em> a difference. Resolution is a credit-policy judgement:
 * either the lender's documented policy says something this bundle does not implement, or the
 * bundle is right and the lender's system was doing something undocumented. Only the lender can say
 * which. The harness localises each difference to one step and one reason so that the judgement is
 * about credit policy and not about archaeology.
 */
final class ParallelRunCommand {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** How many example references to carry per difference group. Enough to look up, not a dump. */
  private static final int EXAMPLES_PER_GROUP = 5;

  private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> MAP_TYPE =
      new com.fasterxml.jackson.core.type.TypeReference<>() {};

  private ParallelRunCommand() {}

  /** How a case came out, against the lender's own outcome. */
  private enum Verdict {
    /** Same outcome. */
    AGREE,
    /** Both sides decided, and differently — a policy difference to explain. */
    DIFFER,
    /** This policy referred for human review where the lender's system decided. */
    WE_REFERRED,
    /** The lender's system referred where this policy decided. */
    THEY_REFERRED,
    /** This policy could not evaluate the case: missing or invalid facts. A data gap. */
    INCOMPARABLE
  }

  static int run(String[] args, PrintStream out, PrintStream err) {
    String casesPath = Keys.flag(args, "--cases");
    String pubPath = Keys.flag(args, "--pub");
    String kid = Keys.flag(args, "--kid");
    String outPath = Keys.flag(args, "--out");
    String asOfText = Keys.flag(args, "--as-of");

    if (args.length < 2 || casesPath == null || pubPath == null || kid == null) {
      err.println(
          "usage: bre parallel-run <bundle.json> --cases <cases.jsonl> --pub <key.x509>"
              + " --kid <id> [--as-of <YYYY-MM-DD>] [--out <register.json>]");
      err.println();
      err.println("  One JSON object per line in --cases:");
      err.println(
          "    {\"applicationRef\":\"APP-1\",\"asOf\":\"2026-09-01\","
              + "\"theirDecision\":\"APPROVE\",\"facts\":{...}}");
      err.println(
          "  theirDecision is the outcome the lender's own system reached. asOf is the date it");
      err.println("  reached it on, per case — NOT today, or the run would re-decide at today's");
      err.println("  parameters and report differences that are really the passage of time.");
      return Main.EXIT_USAGE;
    }

    LocalDate fallbackAsOf = null;
    if (asOfText != null) {
      try {
        fallbackAsOf = LocalDate.parse(asOfText);
      } catch (RuntimeException notADate) {
        err.println("--as-of must be an ISO-8601 date, e.g. 2026-10-01");
        return Main.EXIT_USAGE;
      }
    }

    PolicyRunner runner;
    try {
      runner = PolicyRunner.load(Path.of(args[1]), Path.of(pubPath), kid);
    } catch (BundleException e) {
      err.println("the bundle was refused: " + e.getMessage());
      return Main.EXIT_FINDINGS;
    } catch (IOException e) {
      err.println("could not read: " + e.getMessage());
      return Main.EXIT_USAGE;
    }
    if (!runner.hasResolvedAssets()) {
      // A warning, not a refusal. A policy that uses no parameters and no lists is legitimate, and
      // refusing to run one would be this tool deciding what a policy may contain. But a bundle
      // whose assets were left out by mistake compares the lender's policy against a policy whose
      // every threshold is Missing — so every case refers, and the report reads like a data problem
      // at the lender's end rather than a mistake in how the run was set up.
      err.println(
          "warning: this bundle carries no resolved parameters or lists. If the policy uses any,"
              + " every threshold is Missing and every case will refer — re-bundle with --assets.");
    }

    List<String> lines;
    try {
      lines = Files.readAllLines(Path.of(casesPath));
    } catch (IOException e) {
      err.println("could not read " + casesPath);
      return Main.EXIT_USAGE;
    }

    Tally tally = new Tally();
    FactsValidator validator = new FactsValidator(LendingFacts.schema());
    int lineNumber = 0;
    int unreadable = 0;

    for (String line : lines) {
      lineNumber++;
      if (line.isBlank()) {
        continue;
      }
      JsonNode caseNode;
      try {
        caseNode = MAPPER.readTree(line);
      } catch (IOException notJson) {
        // The parser's message can quote the offending text, which on a case line is fact values.
        err.println("line " + lineNumber + ": not readable as JSON, skipped");
        unreadable++;
        continue;
      }
      tally.record(evaluate(caseNode, lineNumber, runner, validator, fallbackAsOf));
    }

    report(tally, unreadable, runner, out);

    if (outPath != null) {
      try {
        Files.writeString(
            Path.of(outPath),
            MAPPER
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(tally.register(runner.bundle().bundleHash())));
        out.println();
        out.println("  register written to " + outPath);
      } catch (IOException e) {
        err.println("could not write " + outPath);
        return Main.EXIT_FINDINGS;
      }
    }

    // Zero on a run that completed, whatever it found. A parallel run that EXPECTED agreement would
    // be the wrong tool: differences are the output, not the failure, and a non-zero exit would put
    // this behind a CI gate that somebody then disables in the week the pilot starts finding
    // things.
    // Non-zero only when the run itself could not be trusted.
    return unreadable > 0 && unreadable == countNonBlank(lines) ? Main.EXIT_FINDINGS : Main.EXIT_OK;
  }

  private static int countNonBlank(List<String> lines) {
    return (int) lines.stream().filter(line -> !line.isBlank()).count();
  }

  /** One case's outcome. */
  private record Outcome(
      String applicationRef,
      Verdict verdict,
      String theirs,
      String ours,
      String decisiveStep,
      String decisiveReason,
      List<String> dataGapPaths) {}

  private static Outcome evaluate(
      JsonNode caseNode,
      int lineNumber,
      PolicyRunner runner,
      FactsValidator validator,
      LocalDate fallbackAsOf) {

    String ref = text(caseNode, "applicationRef").orElse("line-" + lineNumber);
    String theirs =
        text(caseNode, "theirDecision").orElse("ABSENT").toUpperCase(java.util.Locale.ROOT);
    JsonNode facts = caseNode.get("facts");

    if (facts == null || !facts.isObject()) {
      return new Outcome(ref, Verdict.INCOMPARABLE, theirs, "NOT_RUN", "-", "NO_FACTS", List.of());
    }

    LocalDate asOf;
    Optional<String> caseAsOf = text(caseNode, "asOf");
    if (caseAsOf.isPresent()) {
      try {
        asOf = LocalDate.parse(caseAsOf.get());
      } catch (RuntimeException notADate) {
        return new Outcome(
            ref, Verdict.INCOMPARABLE, theirs, "NOT_RUN", "-", "BAD_AS_OF", List.of());
      }
    } else if (fallbackAsOf != null) {
      asOf = fallbackAsOf;
    } else {
      // Not defaulted to today. Deciding a four-week-old application at today's parameter values
      // produces a difference caused by the run's own clock, and it is indistinguishable in the
      // report from a difference caused by the policy.
      return new Outcome(ref, Verdict.INCOMPARABLE, theirs, "NOT_RUN", "-", "NO_AS_OF", List.of());
    }

    // Validated before evaluating, so a schema problem is reported as a schema problem. Without
    // this, an export with a mistyped field reaches the engine, the fact reads as Missing, the
    // policy fails closed, and the report says REFER — which reads as a credit judgement.
    List<FactsValidator.Finding> findings = validator.validate(facts);
    if (!findings.isEmpty()) {
      return new Outcome(
          ref,
          Verdict.INCOMPARABLE,
          theirs,
          "NOT_RUN",
          "-",
          "INVALID_FACTS",
          findings.stream().map(FactsValidator.Finding::path).distinct().sorted().toList());
    }

    FlowEvaluator.DecisionResult result;
    try {
      result = runner.decide(MAPPER.convertValue(facts, MAP_TYPE), asOf);
    } catch (RuntimeException e) {
      // An engine failure on one case must not end the run: twenty thousand cases in, the
      // twenty-thousand-and-first is not a reason to lose the first twenty thousand. The class
      // name, not the message, because an engine message can carry a fact value (§9.6).
      return new Outcome(
          ref,
          Verdict.INCOMPARABLE,
          theirs,
          "NOT_RUN",
          "-",
          "ENGINE_" + e.getClass().getSimpleName(),
          List.of());
    }

    String ours = result.decision().name();

    // A data gap, checked BEFORE comparing outcomes. §4.8 turns DATA_MISSING and ERROR into REFER,
    // so by the time there is a decision the two are indistinguishable — and they need opposite
    // responses from the pilot team.
    List<String> gaps = dataGaps(result);
    if (!gaps.isEmpty()) {
      return new Outcome(
          ref,
          Verdict.INCOMPARABLE,
          theirs,
          ours,
          firstStepWith(result, gapStatuses()),
          "DATA_MISSING",
          gaps);
    }

    if (ours.equals(theirs)) {
      return new Outcome(ref, Verdict.AGREE, theirs, ours, "-", "-", List.of());
    }

    Verdict verdict;
    if ("REFER".equals(ours)) {
      verdict = Verdict.WE_REFERRED;
    } else if ("REFER".equals(theirs)) {
      verdict = Verdict.THEY_REFERRED;
    } else {
      verdict = Verdict.DIFFER;
    }

    // The step that decided it, and its reason. For a DECLINE that is the first FAIL — §4.8's
    // knockout means later steps did not run, so the first one IS the cause. For a REFER it is the
    // first step that referred.
    List<RuntimeStatus> decisive =
        "DECLINE".equals(ours)
            ? List.of(RuntimeStatus.FAIL)
            : List.of(RuntimeStatus.REFER, RuntimeStatus.DEVIATION);
    String step = firstStepWith(result, decisive);
    return new Outcome(ref, verdict, theirs, ours, step, reasonOf(result, step), List.of());
  }

  private static List<RuntimeStatus> gapStatuses() {
    return List.of(RuntimeStatus.DATA_MISSING, RuntimeStatus.ERROR);
  }

  private static List<String> dataGaps(FlowEvaluator.DecisionResult result) {
    List<String> gaps = new ArrayList<>();
    for (FlowEvaluator.StepResult step : result.steps()) {
      for (ComponentResult entity : step.entities()) {
        if (entity.status() == RuntimeStatus.DATA_MISSING
            || entity.status() == RuntimeStatus.ERROR) {
          // The reason code names what was absent. The error TEXT is not used: it can quote a
          // value (§9.6), and the code is what a mapping fix is keyed on anyway.
          gaps.add(step.ref() + "/" + entity.reasonCode().orElse(entity.status().name()));
        }
      }
    }
    return gaps.stream().distinct().sorted().toList();
  }

  private static String firstStepWith(
      FlowEvaluator.DecisionResult result, List<RuntimeStatus> wanted) {
    return result.steps().stream()
        .filter(step -> wanted.contains(step.status()))
        .map(FlowEvaluator.StepResult::ref)
        .findFirst()
        .orElse("-");
  }

  private static String reasonOf(FlowEvaluator.DecisionResult result, String stepRef) {
    return result.steps().stream()
        .filter(step -> step.ref().equals(stepRef))
        .flatMap(step -> step.entities().stream())
        .map(entity -> entity.reasonCode().orElse(null))
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse("-");
  }

  private static Optional<String> text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isTextual() && !value.asText().isBlank()
        ? Optional.of(value.asText())
        : Optional.empty();
  }

  // ---------------------------------------------------------------------------------------------

  /** A group of cases that differ for the same reason. */
  private static final class Group {
    private final String theirs;
    private final String ours;
    private final String step;
    private final String reason;
    private final List<String> paths;
    private int count;
    private final List<String> examples = new ArrayList<>();

    Group(Outcome first) {
      this.theirs = first.theirs();
      this.ours = first.ours();
      this.step = first.decisiveStep();
      this.reason = first.decisiveReason();
      this.paths = first.dataGapPaths();
    }

    void add(Outcome outcome) {
      count++;
      if (examples.size() < EXAMPLES_PER_GROUP) {
        examples.add(outcome.applicationRef());
      }
    }
  }

  private static final class Tally {
    private final Map<Verdict, Integer> byVerdict = new LinkedHashMap<>();
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private int total;

    void record(Outcome outcome) {
      total++;
      byVerdict.merge(outcome.verdict(), 1, Integer::sum);
      if (outcome.verdict() == Verdict.AGREE) {
        return;
      }
      String key =
          outcome.verdict()
              + "|"
              + outcome.theirs()
              + "|"
              + outcome.ours()
              + "|"
              + outcome.decisiveStep()
              + "|"
              + outcome.decisiveReason()
              + "|"
              + String.join(",", outcome.dataGapPaths());
      groups.computeIfAbsent(key, k -> new Group(outcome)).add(outcome);
    }

    int count(Verdict verdict) {
      return byVerdict.getOrDefault(verdict, 0);
    }

    private static double rate(int part, int whole) {
      return whole == 0 ? 0.0 : Math.round(part * 10000.0 / whole) / 100.0;
    }

    List<Map.Entry<String, Group>> ranked() {
      return groups.entrySet().stream()
          .sorted(
              Comparator.comparingInt((Map.Entry<String, Group> e) -> e.getValue().count)
                  .reversed())
          .toList();
    }

    ObjectNode register(String bundleHash) {
      ObjectNode root = MAPPER.createObjectNode();
      root.put("bundleHash", bundleHash);
      root.put("casesCompared", total);
      ObjectNode counts = root.putObject("counts");
      for (Verdict verdict : Verdict.values()) {
        counts.put(verdict.name(), count(verdict));
      }
      // BOTH rates, because a committee will quote one of them and the two differ by however much
      // of the extract did not map. Reporting only the diluted figure invites somebody to
      // recalculate the useful one in a spreadsheet and get it wrong; reporting only the
      // comparable-only figure hides that a quarter of the pilot never ran.
      int comparable = total - count(Verdict.INCOMPARABLE);
      root.put("agreementRate", rate(count(Verdict.AGREE), total));
      root.put("comparableCases", comparable);
      root.put("agreementRateOverComparable", rate(count(Verdict.AGREE), comparable));
      ArrayNode differences = root.putArray("differences");
      for (Map.Entry<String, Group> entry : ranked()) {
        Group group = entry.getValue();
        ObjectNode node = differences.addObject();
        node.put("verdict", entry.getKey().substring(0, entry.getKey().indexOf('|')));
        node.put("theirDecision", group.theirs);
        node.put("ourDecision", group.ours);
        node.put("decisiveStep", group.step);
        node.put("reasonCode", group.reason);
        node.put("cases", group.count);
        ArrayNode examples = node.putArray("exampleApplicationRefs");
        group.examples.forEach(examples::add);
        if (!group.paths.isEmpty()) {
          ArrayNode paths = node.putArray("factPaths");
          group.paths.forEach(paths::add);
        }
        // Left for the lender to fill in. The harness localises; it cannot resolve, and a field
        // the harness pre-filled with a guess is a field a committee would sign without reading.
        node.put("resolution", "");
        node.put("resolvedBy", "");
      }
      return root;
    }
  }

  private static void report(Tally tally, int unreadable, PolicyRunner runner, PrintStream out) {
    out.println();
    out.println("  bundle          " + runner.bundle().bundleHash());
    out.println("  cases compared  " + tally.total);
    out.println();
    out.printf("  %-14s %7d%n", "agree", tally.count(Verdict.AGREE));
    out.printf(
        "  %-14s %7d   different outcome, both sides decided%n",
        "differ", tally.count(Verdict.DIFFER));
    out.printf(
        "  %-14s %7d   we referred, they decided%n",
        "we referred", tally.count(Verdict.WE_REFERRED));
    out.printf(
        "  %-14s %7d   they referred, we decided%n",
        "they referred", tally.count(Verdict.THEY_REFERRED));
    out.printf(
        "  %-14s %7d   could not be compared — a DATA problem, not a policy one%n",
        "incomparable", tally.count(Verdict.INCOMPARABLE));
    if (unreadable > 0) {
      out.printf("  %-14s %7d   lines that were not JSON%n", "unreadable", unreadable);
    }
    int comparable = tally.total - tally.count(Verdict.INCOMPARABLE);
    if (tally.total > 0) {
      out.println();
      out.printf(
          "  agreement       %.2f%% of all %d cases%n",
          tally.count(Verdict.AGREE) * 100.0 / tally.total, tally.total);
      if (comparable > 0 && comparable != tally.total) {
        out.printf(
            "                  %.2f%% of the %d that could be compared%n",
            tally.count(Verdict.AGREE) * 100.0 / comparable, comparable);
      }
      if (comparable == 0) {
        out.println("                  NO case could be compared. Fix the extract before reading");
        out.println("                  anything else here as a statement about the policy.");
      }
    }

    List<Map.Entry<String, Group>> ranked = tally.ranked();
    if (ranked.isEmpty()) {
      out.println();
      out.println("  Every case agreed. Confirm the cases are real before believing it: an export");
      out.println("  whose theirDecision column is always APPROVE agrees with an all-approve");
      out.println("  policy perfectly.");
      return;
    }

    out.println();
    out.println("  Differences by cause, commonest first. Each line is one cause, not one case.");
    out.println();
    out.printf("  %7s  %-9s %-9s %-22s %s%n", "cases", "theirs", "ours", "decided by", "reason");
    for (Map.Entry<String, Group> entry : ranked) {
      Group group = entry.getValue();
      out.printf(
          "  %7d  %-9s %-9s %-22s %s%n",
          group.count, group.theirs, group.ours, group.step, group.reason);
      if (!group.paths.isEmpty()) {
        out.printf("           fact paths: %s%n", String.join(", ", group.paths));
      }
    }
    out.println();
    out.println("  Work down this list, not along the cases. The top line is the one cause whose");
    out.println("  resolution moves the most applications, and resolving it is a credit-policy");
    out.println(
        "  judgement the lender makes — either their documented policy says something this");
    out.println("  bundle does not implement, or the bundle is right and their system was doing");
    out.println("  something undocumented. Record which, per cause, in the register's resolution");
    out.println("  field. INCOMPARABLE lines are not that conversation: they are fields missing");
    out.println("  from the extract, and they are fixed by the mapping rather than by committee.");
  }
}
