package dev.bre.decision.explain;

import dev.bre.decision.log.DecisionRecord;
import dev.bre.decision.policy.BundleCache;
import dev.bre.engine.decide.FlowEvaluator;
import dev.bre.engine.eval.EvaluationContext;
import dev.bre.engine.eval.Value;
import dev.bre.engine.trace.TraceRecorder;
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

  private final BundleCache bundles;

  /**
   * Creates a replay service.
   *
   * @param bundles the verified policy cache, keyed by content address
   */
  public ReplayService(BundleCache bundles) {
    this.bundles = bundles;
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
    String bundleHash =
        record
            .bundleHash()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "decision "
                            + record.decisionId()
                            + " has no bundleHash, so there is"
                            + " nothing to pin a replay to"));
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
   * Whether a decision can be explained at all.
   *
   * <p>Facts are not stored by default (§9.2 envelope encryption is M4), so most decisions cannot
   * be replayed yet. Saying so is better than returning an explanation built from nothing.
   *
   * @param facts the stored facts, if any
   * @return the reason it cannot be replayed, or empty when it can
   */
  public static Optional<String> cannotReplay(Optional<Value.RecordV> facts) {
    if (facts.isEmpty()) {
      return Optional.of(
          "the facts for this decision were not stored, so it cannot be replayed. Fact snapshots"
              + " are off by default because §9.2 envelope encryption does not exist yet (M4-04);"
              + " bre.decision-log.store-facts enables the development-only plaintext table.");
    }
    return Optional.empty();
  }
}
