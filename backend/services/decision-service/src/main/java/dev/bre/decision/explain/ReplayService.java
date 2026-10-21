package dev.bre.decision.explain;

import dev.bre.decision.log.DecisionLog;
import dev.bre.decision.log.DecisionRecord;
import dev.bre.decision.policy.BundleCache;
import dev.bre.engine.decide.FlowEvaluator;
import dev.bre.engine.eval.EvaluationContext;
import dev.bre.engine.eval.Value;
import dev.bre.engine.trace.TraceRecorder;
import dev.bre.facts.FactReader;
import dev.bre.facts.LendingFacts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Explanation by replay (LLD §4.12, §6.4).
 *
 * <p><b>An explanation is produced by re-running the decision, never by reading a stored one.</b>
 * The engine is a pure function of {@code (facts, bundle, asOf)} (D2), so re-running those three
 * against the same engine version must give the same answer — and if it does not, determinism is
 * broken and that is a P1 incident, not a rendering problem.
 *
 * <p>That is why replay <b>compares</b> rather than just producing. Every replayed status is
 * checked against the recorded one, and any difference sets {@code matched = false}. A platform
 * that silently showed the replayed answer would turn a determinism bug into a quiet disagreement
 * between what a borrower was told and what the audit trail says — which is the single worst
 * failure this system can have, because nobody would notice it.
 *
 * <p>Three inputs have to come from the record, not from today: the {@code bundleHash} (not the
 * policy's current champion), the {@code asOf} (not now), and the facts as submitted. Taking any of
 * them from the present would explain a decision that was never made.
 */
public final class ReplayService {

  private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
      new com.fasterxml.jackson.databind.ObjectMapper();

  private final BundleCache bundles;
  private final DecisionLog decisionLog;

  /**
   * Creates a replay service.
   *
   * @param bundles the verified policy cache, keyed by content address
   * @param decisionLog where stored fact snapshots come from (§9.2); the log is asked for them
   *     through a method separate from an ordinary record lookup, so facts travel only to the
   *     replay path
   */
  public ReplayService(BundleCache bundles, DecisionLog decisionLog) {
    this.bundles = bundles;
    this.decisionLog = decisionLog;
  }

  /**
   * What a replay established.
   *
   * @param matched whether every replayed status equalled the recorded one
   * @param mismatches each disagreement, as {@code component: recorded vs replayed}
   * @param decision the replayed decision
   * @param steps the replayed step results, with their explanation traces
   * @param bundleHash the bundle the decision was taken against
   */
  public record Replay(
      boolean matched,
      List<String> mismatches,
      String decision,
      List<FlowEvaluator.StepResult> steps,
      String bundleHash) {

    /** Creates a replay result, copying the lists. */
    public Replay {
      mismatches = List.copyOf(mismatches);
      steps = List.copyOf(steps);
    }
  }

  /**
   * Re-runs a decision and compares the result with what was recorded.
   *
   * @param record the stored decision
   * @param facts the facts as they were submitted
   * @param masked whether fact values are masked in the trace (§9.4) — true for any caller without
   *     unmask rights and a recorded reason
   * @return what the replay established
   */
  public Replay replay(DecisionRecord record, Value.RecordV facts, boolean masked) {
    // The bundle is pinned by the RECORD, not by the policy's current champion. Explaining a
    // decision against whatever is live today would explain a decision nobody ever made.
    return replayAgainst(
        record,
        facts,
        record
            .bundleHash()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "decision "
                            + record.decisionId()
                            + " has no bundleHash, so there is"
                            + " nothing to pin a replay to")),
        masked);
  }

  /**
   * Re-runs a decision against a bundle the caller names, and compares with what was recorded.
   *
   * <p><b>This is the back-test, and it is the same code as the explanation on purpose.</b> An
   * explanation replays against the bundle that decided; a back-test replays against a bundle that
   * has not decided anything yet. Everything else — the stored facts, the recorded {@code asOf},
   * the per-step comparison — is identical, and that identity is the point: a back-test whose
   * engine path differed from the explanation path would predict an impact the deployment then did
   * not produce, which is worse than no back-test because somebody would have signed off on it.
   *
   * <p>The {@code mismatches} a back-test produces are not defects. They are the
   * <em>transitions</em> — the applications whose outcome the candidate policy would change — and
   * that list is the deliverable. The caller is responsible for not reading them as errors.
   *
   * @param record the decision as recorded
   * @param facts the facts as submitted
   * @param bundleHash the bundle to evaluate against — the recorded one for an explanation, a
   *     candidate for a back-test
   * @param masked whether fact values are masked in the trace (§9.4)
   * @return what the comparison established
   */
  public Replay replayAgainst(
      DecisionRecord record, Value.RecordV facts, String bundleHash, boolean masked) {
    // The bundle's OWN parameters and lists, not any configured now. They were resolved into this
    // hash when it was signed (§8.4), so pinning the hash pins the parameter values too — which is
    // what makes a replay years later reproduce the decision rather than re-take it.
    BundleCache.Compiled served = bundles.compiled(bundleHash);

    TraceRecorder recorder = masked ? TraceRecorder.masking() : TraceRecorder.unmasked();
    EvaluationContext context =
        EvaluationContext.of(facts, served.parameters(), served.lists(), record.response().asOf())
            .recording(recorder);

    FlowEvaluator.DecisionResult replayed = FlowEvaluator.evaluate(served.policy(), context);

    List<String> mismatches = new ArrayList<>();
    String recordedDecision = record.response().decision();
    if (!replayed.decision().name().equals(recordedDecision)) {
      mismatches.add(
          "decision: recorded " + recordedDecision + ", replayed " + replayed.decision());
    }

    // Per-step comparison, because a decision can match while the reasons behind it do not — and
    // an explanation whose steps disagree with the record explains a different decision.
    Map<String, String> recordedStatuses = new java.util.LinkedHashMap<>();
    record
        .response()
        .results()
        .forEach(outcome -> recordedStatuses.put(outcome.code(), outcome.status()));
    for (FlowEvaluator.StepResult step : replayed.steps()) {
      String recorded = recordedStatuses.get(step.ref());
      if (recorded != null && !recorded.equals(step.status().name())) {
        mismatches.add(step.ref() + ": recorded " + recorded + ", replayed " + step.status());
      }
    }

    return new Replay(
        mismatches.isEmpty(), mismatches, replayed.decision().name(), replayed.steps(), bundleHash);
  }

  /**
   * Replays a decision from its stored snapshot, resolving the facts itself.
   *
   * <p>The preferred entry point, because it is the one that can report <em>why</em> a replay does
   * not match. Two causes are expected rather than exceptional, and both are reported as a mismatch
   * reason instead of as a failure:
   *
   * <ul>
   *   <li><b>{@code SHREDDED}</b> — a data principal has been erased (§9.5), so their identifying
   *       fields are gone and the rules that read them now fail closed. §9.5 says explicitly that
   *       this raises no alert: it is the erasure working.
   *   <li><b>{@code INTEGRITY}</b> — the stored HMAC does not match (§9.2), so the bytes on disk
   *       are not the bytes the decision was taken on. A restore from the wrong backup, or a
   *       migration that re-serialised them.
   * </ul>
   *
   * <p>Distinguishing the two is the point. Both make {@code matched} false, and treating them
   * alike would mean every honoured erasure request looked like possible tampering — which is
   * exactly the signal that gets muted after the third false alarm.
   *
   * @param record the decision to explain
   * @param masked whether to mask fact values in the trace (§9.4)
   * @return the replay, or empty when the facts were never stored
   */
  public Optional<Replay> replayFromStore(DecisionRecord record, boolean masked) {
    return decisionLog
        .facts(record.tenantId(), record.decisionId())
        .map(
            stored -> {
              Replay replay =
                  replay(
                      record,
                      new FactReader(LendingFacts.schema()).toRecord(toMap(stored.facts())),
                      masked);
              List<String> reasons = new ArrayList<>(replay.mismatches());
              if (!stored.shreddedParties().isEmpty()) {
                reasons.add(
                    "SHREDDED: the identifying facts of "
                        + stored.shreddedParties()
                        + " were erased under §9.5, so rules reading them now fail closed. This is"
                        + " the erasure working and raises no alert.");
              } else if (!stored.integrityVerified()) {
                // Only when nothing was shredded. A shredded snapshot cannot match its own HMAC by
                // construction, so reporting INTEGRITY there would be a false positive every time.
                reasons.add(
                    "INTEGRITY: the stored facts do not match the HMAC recorded with the decision"
                        + " (§9.2), so they are not the facts this decision was taken on.");
              }
              return new Replay(
                  reasons.isEmpty(),
                  reasons,
                  replay.decision(),
                  replay.steps(),
                  replay.bundleHash());
            });
  }

  /**
   * Replays a stored decision against a candidate bundle — the back-test's unit of work.
   *
   * @param record the decision to re-decide
   * @param bundleHash the candidate bundle
   * @return the replay, or empty when the facts were never stored
   */
  public Optional<Replay> backTestFromStore(DecisionRecord record, String bundleHash) {
    return decisionLog
        .facts(record.tenantId(), record.decisionId())
        .map(
            stored ->
                replayAgainst(
                    record,
                    new FactReader(LendingFacts.schema()).toRecord(toMap(stored.facts())),
                    bundleHash,
                    // Always masked. A back-test is run by a risk analyst over thousands of real
                    // applications at once, which is the largest concentration of personal data
                    // this system produces in one response — and §9.4's unmask path does not exist
                    // (M3-09). Masked is the only defensible default here even once it does.
                    true));
  }

  /**
   * Whether a decision can be explained at all.
   *
   * <p>Fact snapshots are off by default (§9.2), so a decision taken with them off cannot be
   * replayed. Saying so is better than returning an explanation built from nothing.
   *
   * @param facts the stored facts, if any
   * @return the reason it cannot be replayed, or empty when it can
   */
  public static Optional<String> cannotReplay(Optional<Value.RecordV> facts) {
    if (facts.isEmpty()) {
      return Optional.of(
          "the facts for this decision were not stored, so it cannot be replayed. Fact snapshots"
              + " are off by default because they are the applicant's personal data; turning"
              + " bre.decision-log.store-facts on stores them sealed per §9.2 — the"
              + " non-identifying half under the tenant key, each party's identifying fields under"
              + " that person's own key, so erasure is key destruction.");
    }
    return Optional.empty();
  }

  private static java.util.Map<String, Object> toMap(
      com.fasterxml.jackson.databind.JsonNode facts) {
    return MAPPER.convertValue(
        facts,
        new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
  }
}
