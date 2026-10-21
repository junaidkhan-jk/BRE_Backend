package dev.bre.decision.backtest;

import dev.bre.decision.explain.ReplayService;
import dev.bre.decision.log.DecisionLog;
import dev.bre.decision.log.DecisionRecord;
import dev.bre.engine.decide.FlowEvaluator;
import dev.bre.engine.ir.Ir.RuntimeStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The portfolio back-test (M5-05, FR-TEST-010, PRD U11).
 *
 * <p>Answers the one question a credit committee asks before approving a policy change: <b>whose
 * answer changes, and why.</b> It replays decisions this installation has already made against a
 * <em>candidate</em> bundle and reports the transitions — the applications the candidate would
 * decide differently.
 *
 * <p><b>It is the same code path as the explanation, deliberately.</b> {@link
 * ReplayService#replayAgainst} is what produces both: the explanation pins the bundle that decided,
 * the back-test pins one that has not decided anything yet, and the stored facts, the recorded
 * {@code asOf} and the per-step comparison are identical. A back-test with its own evaluation path
 * would predict an impact the deployment then failed to produce — worse than no back-test, because
 * somebody would have signed the change off against it.
 *
 * <p><b>Grouped by cause, not listed by case.</b> A policy change that moves a threshold flips
 * every application near it, so a portfolio of 50,000 produces thousands of identical transitions.
 * A list with one row per application is not read, which means the change is approved against a
 * document nobody examined. Transitions are therefore grouped by (from, to, the step that decided
 * it, its reason code) and ranked by count — so the committee sees "1,184 APPROVE → DECLINE at
 * GRP_JLG_SIZE/POL_GRP_TOO_SMALL", which is one decision to take rather than 1,184 rows to read.
 * The same discipline as the pilot parallel run, for the same reason.
 *
 * <p><b>What it cannot replay, it says.</b> Fact snapshots are off by default (§9.2), and without
 * them there is nothing to re-decide. A run over a tenant that stores no facts reports "0 of 500
 * back-tested" — a true and useful answer, and emphatically not "no impact". Reporting an empty
 * transition list as a clean result would be the most dangerous thing this class could do: "the
 * change affects nobody" is exactly what an approver wants to hear.
 *
 * <p><b>An erased principal is not a transition.</b> §9.5 erasure makes rules reading that person's
 * fields fail closed, so the replay differs for a reason that has nothing to do with the candidate
 * policy. Counted apart, or an erasure campaign would read as a policy impact.
 *
 * <p>No fact values appear in the result (§9.6) — steps, reason codes, decisions and counts only,
 * plus the decision ids a reader needs to look a case up in the access-controlled record.
 */
public final class BackTestJob {

  private static final Logger log = LoggerFactory.getLogger(BackTestJob.class);

  /** How many example decision ids to carry per transition. Enough to look up, not a dump. */
  private static final int EXAMPLES_PER_GROUP = 5;

  private final JdbcTemplate jdbc;
  private final DecisionLog decisionLog;
  private final ReplayService replay;

  /**
   * Creates the job.
   *
   * @param jdbc the hot store, for sampling
   * @param decisionLog the log, for reading each sampled decision
   * @param replay the replay service
   */
  public BackTestJob(JdbcTemplate jdbc, DecisionLog decisionLog, ReplayService replay) {
    this.jdbc = jdbc;
    this.decisionLog = decisionLog;
    this.replay = replay;
  }

  /**
   * One cause of transition, with the applications it moved.
   *
   * @param from the decision as recorded
   * @param to the decision the candidate reaches
   * @param decisiveStep the step that produced {@code to}, or {@code -} when none could be named
   * @param reasonCode that step's reason code
   * @param decisions how many applications moved this way for this reason
   * @param exampleDecisionIds a handful to look up in the decision record
   */
  public record Transition(
      String from,
      String to,
      String decisiveStep,
      String reasonCode,
      int decisions,
      List<String> exampleDecisionIds) {

    /** Creates a transition, copying the list. */
    public Transition {
      exampleDecisionIds = List.copyOf(exampleDecisionIds);
    }
  }

  /**
   * What a back-test established.
   *
   * @param candidateBundleHash the bundle under test
   * @param sampled how many decisions were drawn
   * @param backTested how many were re-decided — the only population the rest of this describes
   * @param unchanged how many the candidate decides the same way
   * @param changed how many the candidate decides differently
   * @param notReplayable how many had no stored facts, so nothing was re-decided
   * @param shredded how many differed only because a principal was erased (§9.5)
   * @param transitions the causes, commonest first
   */
  public record Result(
      String candidateBundleHash,
      int sampled,
      int backTested,
      int unchanged,
      int changed,
      int notReplayable,
      int shredded,
      List<Transition> transitions) {

    /** Creates a result, copying the list. */
    public Result {
      transitions = List.copyOf(transitions);
    }

    /**
     * Whether this run re-decided anything at all.
     *
     * <p>Asked separately from "were there transitions", because zero transitions out of zero
     * replays is not "no impact" and must never be shown as one.
     *
     * @return true when at least one decision was re-decided
     */
    public boolean meaningful() {
      return backTested > 0;
    }

    /**
     * The share of re-decided applications the candidate would move.
     *
     * @return a percentage of {@link #backTested}, zero when nothing was re-decided
     */
    public double changedPercent() {
      return backTested == 0 ? 0.0 : Math.round(changed * 10000.0 / backTested) / 100.0;
    }
  }

  /**
   * Back-tests a candidate bundle over a tenant's recent decisions.
   *
   * @param tenantId the tenant
   * @param candidateBundleHash the bundle to evaluate against; it must already be in this pod's
   *     verified cache, which means the registry has published it as servable
   * @param sampleSize how many decisions to draw
   * @return what was found
   */
  public Result run(String tenantId, String candidateBundleHash, int sampleSize) {
    List<String> ids = sample(tenantId, sampleSize);

    int unchanged = 0;
    int notReplayable = 0;
    int shredded = 0;
    Map<String, Group> groups = new LinkedHashMap<>();

    for (String decisionId : ids) {
      Optional<DecisionRecord> record = decisionLog.find(tenantId, decisionId);
      if (record.isEmpty()) {
        notReplayable++;
        continue;
      }
      Optional<ReplayService.Replay> replayed =
          replay.backTestFromStore(record.get(), candidateBundleHash);
      if (replayed.isEmpty()) {
        notReplayable++;
        continue;
      }
      ReplayService.Replay result = replayed.get();

      // Before comparing outcomes. An erasure makes the replay differ for a reason that has
      // nothing to do with the candidate, and counting it as an impact would mean a week of
      // honoured erasure requests looked like a policy that had started declining people.
      if (result.mismatches().stream().anyMatch(reason -> reason.startsWith("SHREDDED"))) {
        shredded++;
        continue;
      }

      String from = record.get().response().decision();
      String to = result.decision();
      if (from.equals(to)) {
        unchanged++;
        continue;
      }

      String step = decisiveStep(result, to);
      String reason = reasonOf(result, step);
      groups
          .computeIfAbsent(
              from + "|" + to + "|" + step + "|" + reason, key -> new Group(from, to, step, reason))
          .add(decisionId);
    }

    List<Transition> transitions =
        groups.values().stream()
            .sorted(Comparator.comparingInt((Group g) -> g.decisions).reversed())
            .map(Group::toTransition)
            .toList();
    int changed = transitions.stream().mapToInt(Transition::decisions).sum();

    Result result =
        new Result(
            candidateBundleHash,
            ids.size(),
            unchanged + changed,
            unchanged,
            changed,
            notReplayable,
            shredded,
            transitions);

    if (!result.meaningful() && !ids.isEmpty()) {
      // Said out loud, at WARN. An empty transition list reads as "this change affects nobody",
      // which is what an approver hopes for and the most dangerous thing this job could imply
      // without having checked.
      log.warn(
          "back-test re-decided NOTHING for tenant={} against {}: {} decision(s) sampled, none"
              + " replayable. Fact snapshots are off, so this run says nothing about impact and"
              + " must not be read as 'no impact'.",
          tenantId,
          candidateBundleHash,
          ids.size());
    } else {
      log.info(
          "back-test tenant={} candidate={} sampled={} backTested={} changed={} ({}%)"
              + " notReplayable={} shredded={} causes={}",
          tenantId,
          candidateBundleHash,
          result.sampled(),
          result.backTested(),
          result.changed(),
          result.changedPercent(),
          result.notReplayable(),
          result.shredded(),
          transitions.size());
    }
    return result;
  }

  /**
   * The step that produced the candidate's decision.
   *
   * <p>For a DECLINE that is the first FAIL — §4.8's knockout means later steps did not run, so the
   * first one is the cause rather than merely the earliest. For anything that refers it is the
   * first step that referred or deviated. For an APPROVE there is no deciding step, because
   * approval is the absence of one: a transition INTO approve is caused by a step that no longer
   * fails, and naming a passing step as the cause would be a guess.
   */
  private static String decisiveStep(ReplayService.Replay replay, String decision) {
    List<RuntimeStatus> wanted =
        switch (decision) {
          case "DECLINE" -> List.of(RuntimeStatus.FAIL);
          case "APPROVE" -> List.of();
          default ->
              List.of(
                  RuntimeStatus.REFER,
                  RuntimeStatus.DEVIATION,
                  RuntimeStatus.ERROR,
                  RuntimeStatus.DATA_MISSING);
        };
    if (wanted.isEmpty()) {
      return "-";
    }
    return replay.steps().stream()
        .filter(step -> wanted.contains(step.status()))
        .map(FlowEvaluator.StepResult::ref)
        .findFirst()
        .orElse("-");
  }

  private static String reasonOf(ReplayService.Replay replay, String stepRef) {
    if ("-".equals(stepRef)) {
      return "-";
    }
    return replay.steps().stream()
        .filter(step -> step.ref().equals(stepRef))
        .flatMap(step -> step.entities().stream())
        .map(entity -> entity.reasonCode().orElse(null))
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse("-");
  }

  /**
   * Draws a sample of recent decisions.
   *
   * <p>By recency rather than at random, and for the same reason {@code ReplayVerificationJob}
   * gives: a decision id is a UUIDv7 whose timestamp decides its partition (§5.3), so the most
   * recent is one partition's index scan where {@code ORDER BY random()} is a full scan of every
   * partition in the retention window.
   *
   * <p>The bias is worth naming, because here it is less obviously the right one than it is for
   * verification. A recent sample is the current population, which is what a committee is deciding
   * about — but a policy whose impact is seasonal will be under-read by a sample drawn from one
   * month. PRD U11's population filters are Phase 2; until then, a back-test over a month is a
   * back-test over a month and the report should be read as one.
   */
  private List<String> sample(String tenantId, int sampleSize) {
    return jdbc.query(
        """
        SELECT decision_id::text FROM bre_dec.decisions
         WHERE tenant_id = ?
         ORDER BY decision_date DESC, decision_id DESC
         LIMIT ?
        """,
        (rs, rowNum) -> rs.getString(1),
        tenantId,
        Math.max(1, sampleSize));
  }

  /** Accumulates one cause while the run walks the sample. */
  private static final class Group {
    private final String from;
    private final String to;
    private final String step;
    private final String reason;
    private final List<String> examples = new ArrayList<>();
    private int decisions;

    Group(String from, String to, String step, String reason) {
      this.from = from;
      this.to = to;
      this.step = step;
      this.reason = reason;
    }

    void add(String decisionId) {
      decisions++;
      if (examples.size() < EXAMPLES_PER_GROUP) {
        examples.add(decisionId);
      }
    }

    Transition toTransition() {
      return new Transition(from, to, step, reason, decisions, examples);
    }
  }
}
