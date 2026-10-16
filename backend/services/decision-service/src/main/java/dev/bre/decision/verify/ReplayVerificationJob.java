package dev.bre.decision.verify;

import dev.bre.decision.explain.ReplayService;
import dev.bre.decision.log.DecisionLog;
import dev.bre.decision.log.DecisionRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Replay verification (M4-07, NFR-MAINT-02, §10.1).
 *
 * <p><b>The claim this exists to keep honest.</b> D8 says a decision is a pure function of its
 * facts, its bundle and its {@code asOf} — so re-running it must produce the same answer, today and
 * in nine years. That is the promise a lender makes to a regulator, and an untested promise about
 * determinism is the kind that is discovered to be false at the worst possible moment: during an
 * audit, on a decision somebody is disputing.
 *
 * <p>So this samples decisions already made, replays each against the bundle it was made with, and
 * compares. A mismatch means one of three things, all serious: the engine changed behaviour between
 * versions, the bundle a pod loaded is not the bundle the hash names, or the stored facts are not
 * the facts the decision was taken on. The job does not try to tell them apart — it reports the
 * mismatch with the evidence, because guessing between those three in an alert would send somebody
 * to the wrong place.
 *
 * <p><b>It can only verify what it can replay.</b> Fact snapshots are off by default, and without
 * them there is nothing to replay against. A run over a tenant that stores no facts reports
 * "verified 0 of 500, because facts are not stored" — which is a true and useful answer, and
 * emphatically not a pass. Reporting success for a sample nothing was checked in would be the
 * single most misleading thing this class could do.
 *
 * <p><b>A shredded principal is not a mismatch.</b> An erased borrower's identifying fields are
 * gone by design (§9.5), so rules reading them now fail closed and the replay differs. That is the
 * erasure working. {@link ReplayService#replayFromStore} already separates it from a real mismatch;
 * this counts it separately so an erasure campaign does not look like an engine defect.
 */
public final class ReplayVerificationJob {

  private static final Logger log = LoggerFactory.getLogger(ReplayVerificationJob.class);

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
  public ReplayVerificationJob(JdbcTemplate jdbc, DecisionLog decisionLog, ReplayService replay) {
    this.jdbc = jdbc;
    this.decisionLog = decisionLog;
    this.replay = replay;
  }

  /**
   * What a run found.
   *
   * @param sampled how many decisions were drawn
   * @param verified how many were replayed and matched
   * @param mismatched how many were replayed and did NOT match — the number that matters
   * @param notReplayable how many had no stored facts, so nothing was checked
   * @param shredded how many differed only because a principal was erased
   * @param findings one finding per mismatch, with the evidence
   */
  public record Result(
      int sampled,
      int verified,
      int mismatched,
      int notReplayable,
      int shredded,
      List<Finding> findings) {

    /** Creates a result, copying the list. */
    public Result {
      findings = List.copyOf(findings);
    }

    /**
     * Whether this run actually checked anything.
     *
     * <p>Asked separately from "were there mismatches", because zero mismatches out of zero replays
     * is not a pass and must never be displayed as one.
     *
     * @return true when at least one decision was replayed
     */
    public boolean meaningful() {
      return verified + mismatched > 0;
    }
  }

  /**
   * Samples a tenant's recent decisions and verifies them.
   *
   * @param tenantId the tenant
   * @param sampleSize how many to draw; §10.1 asks for at least 10,000 a day across the estate
   * @return what was found
   */
  public Result verify(String tenantId, int sampleSize) {
    List<String> ids = sample(tenantId, sampleSize);

    int verified = 0;
    int notReplayable = 0;
    int shredded = 0;
    List<Finding> findings = new ArrayList<>();

    for (String decisionId : ids) {
      Optional<DecisionRecord> record = decisionLog.find(tenantId, decisionId);
      if (record.isEmpty()) {
        notReplayable++;
        continue;
      }
      Optional<ReplayService.Replay> replayed = replay.replayFromStore(record.get(), true);
      if (replayed.isEmpty()) {
        notReplayable++;
        continue;
      }
      ReplayService.Replay result = replayed.get();
      if (result.matched()) {
        verified++;
        continue;
      }
      // An erasure, not a defect. Counted apart so a week of erasure requests does not read as an
      // engine that has started disagreeing with itself.
      if (result.mismatches().stream().anyMatch(reason -> reason.startsWith("SHREDDED"))) {
        shredded++;
        continue;
      }
      findings.add(
          new Finding(
              "REPLAY_MISMATCH",
              Finding.PAGE,
              tenantId,
              ("decision %s does not reproduce: %s. One of three things is true and this job"
                      + " cannot tell which — the engine changed behaviour, the bundle a pod"
                      + " loaded is not the bundle its hash names, or the stored facts are not the"
                      + " facts the decision was taken on. All three are serious.")
                  .formatted(decisionId, String.join("; ", result.mismatches())),
              0,
              0));
    }

    Result result =
        new Result(ids.size(), verified, findings.size(), notReplayable, shredded, findings);

    if (!result.meaningful() && !ids.isEmpty()) {
      // Said out loud. A verification job that silently checks nothing is worse than no job: the
      // green tick is read as evidence, and there is none.
      log.warn(
          "replay verification checked NOTHING for tenant={}: {} decisions sampled, none"
              + " replayable. Fact snapshots are off, so determinism is unverified here.",
          tenantId,
          ids.size());
    } else {
      log.info(
          "replay verification tenant={} sampled={} verified={} mismatched={} notReplayable={}"
              + " shredded={}",
          tenantId,
          result.sampled(),
          result.verified(),
          result.mismatched(),
          result.notReplayable(),
          result.shredded());
    }
    return result;
  }

  /**
   * Draws a sample of recent decisions.
   *
   * <p>Ordered by decision id rather than at random, and the reason is partition pruning: the id is
   * a UUIDv7 whose timestamp decides the partition (§5.3), so taking the most recent is one
   * partition's index scan while {@code ORDER BY random()} is a full scan of every partition. A
   * recency bias is also the right bias here — a defect introduced by yesterday's deployment is
   * what this is looking for.
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
}
