package dev.bre.decision.explain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.bre.bundle.Bundle;
import dev.bre.bundle.BundleSigner;
import dev.bre.bundle.BundleVerifier;
import dev.bre.bundle.TrustStore;
import dev.bre.decision.api.DecisionApi.DecisionResponse;
import dev.bre.decision.api.DecisionApi.RuleOutcome;
import dev.bre.decision.log.DecisionRecord;
import dev.bre.decision.log.InMemoryDecisionLog;
import dev.bre.decision.policy.BundleCache;
import dev.bre.decision.policy.FileBundleStore;
import dev.bre.engine.eval.Value;
import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Explanation by replay (LLD §4.12, §6.4). */
class ReplayServiceTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final KeyPair KEY = BundleSigner.generateKeyPair();

  @TempDir Path tmp;

  private BundleCache cache;
  private String bundleHash;

  @BeforeEach
  void seedTheBundle() throws IOException {
    FileBundleStore store = new FileBundleStore(tmp.resolve("bundles"));

    ObjectNode ir = JSON.createObjectNode();
    ir.put("irVersion", 1);
    ObjectNode rule = ir.putArray("rules").addObject();
    rule.put("code", "CR_SCORE")
        .put("name", "CR_SCORE")
        .put("scope", "APPLICATION")
        .put("check", "application.score >= 650");
    rule.putObject("onFail").put("status", "FAIL").put("reasonCode", "CR_LOW");
    rule.putObject("onMissing").put("policy", "REFER");

    Bundle bundle =
        BundleSigner.sign(
            JSON.createObjectNode(),
            ir,
            JSON.createObjectNode(),
            "k",
            Bundle.Role.ORIGIN,
            KEY.getPrivate());
    store.put(bundle.bundleHash(), bundle.toJson());
    bundleHash = bundle.bundleHash();

    cache =
        new BundleCache(
            store,
            new BundleVerifier(TrustStore.builder().ownBundleKey("k", KEY.getPublic()).build()));
  }

  private static Value.RecordV facts(int score) {
    return new Value.RecordV(
        Map.of("application", new Value.RecordV(Map.of("score", new Value.IntV(score)))));
  }

  private DecisionRecord record(String decision, String stepStatus) {
    return new DecisionRecord(
        "01a11b0b-df03-76a2-a30c-255a7934d513",
        "t_acme",
        "REQ-1",
        Optional.of("APP-1"),
        Instant.parse("2026-10-01T10:00:00Z"),
        Instant.parse("2026-10-01T10:00:00Z"),
        1000,
        "Asia/Kolkata",
        0,
        Optional.of("P"),
        Optional.of(1),
        Optional.of(bundleHash),
        "lending-facts@1.0.0",
        new DecisionResponse(
            "01a11b0b-df03-76a2-a30c-255a7934d513",
            "t_acme",
            "REQ-1",
            LocalDate.parse("2026-10-01"),
            decision,
            false,
            List.of(new RuleOutcome("CR_SCORE", stepStatus, null, null))),
        "{}");
  }

  @Test
  @DisplayName("a replay that agrees with the record matches")
  void anAgreeingReplayMatches() {
    ReplayService.Replay replay =
        new ReplayService(cache, new InMemoryDecisionLog())
            .replay(record("APPROVE", "PASS"), facts(712), true);

    assertThat(replay.matched()).isTrue();
    assertThat(replay.mismatches()).isEmpty();
    assertThat(replay.decision()).isEqualTo("APPROVE");
    assertThat(replay.bundleHash()).isEqualTo(bundleHash);
  }

  /**
   * The failure this comparison exists for. If a replay disagrees, determinism is broken — and a
   * platform that quietly showed the replayed answer would leave what a borrower was told and what
   * the audit trail says permanently out of step, with nobody looking.
   */
  @Test
  @DisplayName("a replay that disagrees is reported, not silently shown")
  void aDisagreeingReplayIsReported() {
    // The record claims APPROVE, but these facts decide DECLINE.
    ReplayService.Replay replay =
        new ReplayService(cache, new InMemoryDecisionLog())
            .replay(record("APPROVE", "PASS"), facts(600), true);

    assertThat(replay.matched()).isFalse();
    assertThat(replay.mismatches())
        .anyMatch(m -> m.contains("decision") && m.contains("APPROVE") && m.contains("DECLINE"));
    assertThat(replay.mismatches())
        .as("a per-step disagreement matters even when the decision happens to agree")
        .anyMatch(m -> m.startsWith("CR_SCORE"));
  }

  @Test
  @DisplayName("the explanation carries a trace for every step")
  void theExplanationCarriesTraces() {
    ReplayService.Replay replay =
        new ReplayService(cache, new InMemoryDecisionLog())
            .replay(record("APPROVE", "PASS"), facts(712), false);

    assertThat(replay.steps()).isNotEmpty();
    assertThat(replay.steps().get(0).ref()).isEqualTo("CR_SCORE");
  }

  /** §9.4: without unmask rights, the structure is visible and the borrower's figures are not. */
  @Test
  @DisplayName("masking is the caller's right, not the record's property")
  void maskingIsDecidedPerCaller() {
    ReplayService service = new ReplayService(cache, new InMemoryDecisionLog());

    assertThat(service.replay(record("APPROVE", "PASS"), facts(712), true).matched()).isTrue();
    assertThat(service.replay(record("APPROVE", "PASS"), facts(712), false).matched())
        .as("masking must not change the OUTCOME, only what is shown")
        .isTrue();
  }

  @Test
  @DisplayName("a decision whose facts were not stored says so rather than inventing one")
  void unreplayableDecisionsSayWhy() {
    assertThat(ReplayService.cannotReplay(Optional.empty()))
        .get()
        .asString()
        .contains("were not stored")
        .contains("store-facts");

    assertThat(ReplayService.cannotReplay(Optional.of(facts(712)))).isEmpty();
  }

  /** A replay pins the RECORD's bundle, never today's champion. */
  @Test
  @DisplayName("a record with no bundleHash cannot be replayed")
  void aRecordWithoutABundleCannotBeReplayed() {
    DecisionRecord unpinned =
        new DecisionRecord(
            "id",
            "t_acme",
            "REQ-2",
            Optional.empty(),
            Instant.parse("2026-10-01T10:00:00Z"),
            Instant.parse("2026-10-01T10:00:00Z"),
            1,
            "Asia/Kolkata",
            0,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            "lending-facts@1.0.0",
            new DecisionResponse(
                "id", "t_acme", "REQ-2", LocalDate.parse("2026-10-01"), "APPROVE", true, List.of()),
            "{}");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                new ReplayService(cache, new InMemoryDecisionLog())
                    .replay(unpinned, facts(712), true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nothing to pin a replay to");
  }
}
