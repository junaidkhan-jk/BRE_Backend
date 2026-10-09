package dev.bre.engine.check;

import dev.bre.engine.ir.DependencyGraph;
import dev.bre.engine.ir.Ir;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The §4.13 check catalogue over a whole policy, stage 7 of the compile pipeline.
 *
 * <p>Errors block approval; warnings are reported. The split matters because the studio runs this
 * on every edit (§4.1): a check that blocked on style would train authors to ignore it, and a check
 * that merely warned about a gap would let a policy reach production with a hole in it.
 *
 * <p><b>Implemented here:</b> CHK-001, 002, 003, 004, 005, 012, 014, 015, 016 and 018. The
 * remainder are not omissions but dependencies — CHK-006, 007, 008, 011 and 013 need the resolved
 * asset snapshot (the reason catalogue, the authority matrix, the parameter effective windows),
 * CHK-021 needs the test runner, and CHK-020 needs the cost estimator. Each is named in {@link
 * #notYetImplemented()} rather than silently absent, because a check catalogue that quietly skips
 * half its entries reads as a clean policy.
 */
public final class StaticChecks {

  private StaticChecks() {}

  /** The reserved prefixes a policy may not define reason codes in (CHK-016). */
  private static final List<String> RESERVED_PREFIXES = List.of("SYS_", "DATA_MISSING_");

  /** The reserved exact codes (CHK-016). */
  private static final List<String> RESERVED_CODES = List.of("DEV_NO_AUTHORITY");

  /**
   * Runs every implemented check over a policy.
   *
   * @param policy the policy IR
   * @return every finding, errors and warnings together, in a stable order
   */
  public static List<CheckFinding> run(Ir.PolicyIr policy) {
    List<CheckFinding> findings = new ArrayList<>();

    policy.decisionTables().forEach(table -> findings.addAll(TableAnalysis.analyse(table)));
    policy.scorecards().forEach(scorecard -> findings.addAll(TableAnalysis.analyse(scorecard)));

    findings.addAll(flowCoverage(policy));
    findings.addAll(outcomes(policy));
    findings.addAll(shadowing(policy));
    return List.copyOf(findings);
  }

  /** CHK-004 and CHK-005: anything declared that the flow never runs. */
  private static List<CheckFinding> flowCoverage(Ir.PolicyIr policy) {
    List<CheckFinding> findings = new ArrayList<>();

    Set<String> inFlow = new LinkedHashSet<>();
    policy.flow().stages().forEach(stage -> stage.steps().forEach(step -> inFlow.add(step.ref())));

    for (String code : componentCodes(policy)) {
      if (!inFlow.contains(code)) {
        findings.add(
            CheckFinding.of(
                "CHK-005",
                CheckFinding.Severity.WARNING,
                code,
                "this component is not in the flow, so it never runs. Dead policy is worse than"
                    + " absent policy: a reviewer reads it as being in force."));
      }
    }

    // A variable is used if anything reachable depends on it.
    DependencyGraph graph = DependencyGraph.of(policy);
    Set<String> used = new LinkedHashSet<>();
    graph.nodes().forEach(node -> used.addAll(graph.dependenciesOf(node)));
    for (Ir.VariableDef variable : policy.variables()) {
      if (!used.contains(variable.name())) {
        findings.add(
            CheckFinding.of(
                "CHK-004",
                CheckFinding.Severity.WARNING,
                variable.name(),
                "this variable is not used by any component"));
      }
    }
    return findings;
  }

  /** CHK-015 and CHK-016 over every outcome a policy can emit. */
  private static List<CheckFinding> outcomes(Ir.PolicyIr policy) {
    List<CheckFinding> findings = new ArrayList<>();

    for (Ir.RuleDef rule : policy.rules()) {
      checkOutcome(
          rule.code(),
          rule.onFail().status(),
          rule.onFail().reasonCode().orElse(null),
          rule.onFail().measure().isPresent(),
          findings);
      rule.onMissing().reasonCode().ifPresent(code -> checkReserved(rule.code(), code, findings));
    }
    for (Ir.DecisionTableDef table : policy.decisionTables()) {
      for (Ir.TableRow row : table.rows()) {
        row.then()
            .status()
            .ifPresent(
                status ->
                    checkOutcome(
                        table.code(),
                        status,
                        row.then().reasonCode().orElse(null),
                        row.then().measure().isPresent(),
                        findings));
      }
    }
    for (Ir.ScorecardDef scorecard : policy.scorecards()) {
      scorecard
          .characteristics()
          .forEach(
              characteristic -> {
                characteristic
                    .reasonCode()
                    .ifPresent(code -> checkReserved(scorecard.code(), code, findings));
                characteristic
                    .bins()
                    .forEach(
                        bin ->
                            bin.reasonCode()
                                .ifPresent(
                                    code -> checkReserved(scorecard.code(), code, findings)));
              });
    }
    return findings;
  }

  private static void checkOutcome(
      String component,
      Ir.OutcomeStatus status,
      String reasonCode,
      boolean hasMeasure,
      List<CheckFinding> findings) {

    // CHK-015: a deviation with no measure can never be covered by an approval (§4.9), so it
    // refers forever — an approver can authorise it and the next identical application refers
    // again, because coverage matches on the measured value.
    if (status == Ir.OutcomeStatus.DEVIATION && !hasMeasure) {
      findings.add(
          CheckFinding.of(
              "CHK-015",
              CheckFinding.Severity.WARNING,
              component,
              "a DEVIATION without a measure can never be covered by an approval, so it will"
                  + " refer every time no matter who approves it"));
    }
    if (reasonCode != null) {
      checkReserved(component, reasonCode, findings);
    }
  }

  /** CHK-016: a policy may not mint a code the engine reserves for its own states. */
  private static void checkReserved(
      String component, String reasonCode, List<CheckFinding> findings) {
    boolean reserved =
        RESERVED_CODES.contains(reasonCode)
            || RESERVED_PREFIXES.stream().anyMatch(reasonCode::startsWith);
    if (reserved) {
      findings.add(
          CheckFinding.of(
              "CHK-016",
              CheckFinding.Severity.ERROR,
              component,
              "'"
                  + reasonCode
                  + "' is reserved for the engine's own states. A policy code that"
                  + " collides with one would be filtered out of the customer reasons (§4.10) and"
                  + " read as a system condition in the audit trail."));
    }
  }

  /** CHK-018: a variable named like a fact root it could be confused with. */
  private static List<CheckFinding> shadowing(Ir.PolicyIr policy) {
    List<CheckFinding> findings = new ArrayList<>();
    Set<String> factRoots =
        Set.of(
            "application",
            "parties",
            "group",
            "households",
            "collaterals",
            "business",
            "portfolio",
            "consent",
            "deviationApprovals",
            "member",
            "party");
    for (Ir.VariableDef variable : policy.variables()) {
      if (factRoots.contains(variable.name())) {
        findings.add(
            CheckFinding.of(
                "CHK-018",
                CheckFinding.Severity.WARNING,
                variable.name(),
                "this variable shadows a fact root of the same name. A declared variable wins"
                    + " (§3.8), so every expression that looks like it reads the facts reads the"
                    + " variable instead."));
      }
    }
    return findings;
  }

  private static List<String> componentCodes(Ir.PolicyIr policy) {
    List<String> codes = new ArrayList<>();
    policy.rules().forEach(rule -> codes.add(rule.code()));
    policy.decisionTables().forEach(table -> codes.add(table.code()));
    policy.scorecards().forEach(scorecard -> codes.add(scorecard.code()));
    return codes;
  }

  /**
   * The catalogue entries this build does not yet run, and what each waits on.
   *
   * <p>Exposed rather than left implicit so that a caller reporting "no findings" can also report
   * what was not looked at. A check catalogue that silently skips half its entries reads as a clean
   * policy, which is the most expensive possible way to be wrong about one.
   *
   * @return code to the reason it is not yet run
   */
  public static java.util.Map<String, String> notYetImplemented() {
    return java.util.Map.of(
        "CHK-006", "needs the fact schema's protected tags resolved per component (M1-03 wiring)",
        "CHK-007", "needs the pinned reason-code catalogue",
        "CHK-008", "needs the pinned reason-code catalogue's adverse flags",
        "CHK-009", "needs the fact schema to know which fields are optional",
        "CHK-010", "needs the fact schema to know which lists may be empty",
        "CHK-011", "needs the pinned authority matrix and the served product lines",
        "CHK-013", "needs the parameter set's effective windows",
        "CHK-017", "needs the fact schema to know which collections may be empty",
        "CHK-020", "needs the cost estimator (M1-05's CostEstimator)",
        "CHK-021", "needs the test runner (M3-04)");
  }
}
