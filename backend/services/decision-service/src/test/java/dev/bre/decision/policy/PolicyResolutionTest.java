package dev.bre.decision.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.bre.bundle.Bundle;
import dev.bre.bundle.BundleException;
import dev.bre.bundle.BundleSigner;
import dev.bre.bundle.BundleVerifier;
import dev.bre.bundle.PointerManifest;
import dev.bre.bundle.TrustStore;
import dev.bre.engine.decide.TrafficSplitter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Policy resolution end to end: manifest, pin, challenger, champion (LLD §8.1, §8.3, §4.11).
 *
 * <p>Exercises the whole path the decision service now takes instead of reading a policy off the
 * request — fetch by content address, verify the signature, decode, and pick between champion,
 * challenger and pin.
 */
class PolicyResolutionTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final KeyPair BUNDLE_KEY = BundleSigner.generateKeyPair();
  private static final KeyPair MANIFEST_KEY = BundleSigner.generateKeyPair();
  private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

  @TempDir Path tmp;

  private FileBundleStore store;
  private BundleCache cache;
  private String championHash;
  private String challengerHash;
  private String retiredHash;

  private static JsonNode ir(String ruleCode, String check) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("irVersion", 1);
    ObjectNode rule = root.putArray("rules").addObject();
    rule.put("code", ruleCode)
        .put("name", ruleCode)
        .put("scope", "APPLICATION")
        .put("check", check);
    rule.putObject("onFail").put("status", "FAIL").put("reasonCode", "POL_" + ruleCode);
    rule.putObject("onMissing").put("policy", "REFER");
    return root;
  }

  private String storeBundle(String ruleCode, String check) throws IOException {
    Bundle bundle =
        BundleSigner.sign(
            MAPPER.createObjectNode().put("policyCode", "MFI_JLG_CREDIT_UW"),
            ir(ruleCode, check),
            MAPPER.createObjectNode(),
            "reg-prod-2026-01",
            Bundle.Role.ORIGIN,
            BUNDLE_KEY.getPrivate());
    store.put(bundle.bundleHash(), bundle.toJson());
    return bundle.bundleHash();
  }

  @BeforeEach
  void setUp() throws IOException {
    store = new FileBundleStore(tmp.resolve("bundles"));
    cache =
        new BundleCache(
            store,
            new BundleVerifier(
                TrustStore.builder()
                    .ownBundleKey("reg-prod-2026-01", BUNDLE_KEY.getPublic())
                    .build()));
    championHash = storeBundle("CHAMPION_RULE", "application.score >= 650");
    challengerHash = storeBundle("CHALLENGER_RULE", "application.score >= 600");
    retiredHash = storeBundle("RETIRED_RULE", "application.score >= 700");
  }

  /** Builds and signs a manifest, optionally with a challenger over the whole bucket space. */
  private FilePointerSource pointerSource(boolean withChallenger, boolean pinningAllowed)
      throws IOException {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("format", PointerManifest.FORMAT);
    root.put("cellId", 7).put("manifestVersion", 1L).put("issuedAt", NOW.toString());
    ObjectNode entry = root.putArray("entries").addObject();
    entry
        .put("tenantId", "t_acme")
        .put("environment", "PROD")
        .put("policyCode", "MFI_JLG_CREDIT_UW")
        .put("timeZone", "Asia/Kolkata")
        .put("pinningAllowed", pinningAllowed);
    entry.putObject("champion").put("deploymentId", "dep-champion").put("bundleHash", championHash);
    if (withChallenger) {
      entry
          .putArray("challengers")
          .addObject()
          .put("deploymentId", "dep-challenger")
          .put("bundleHash", challengerHash)
          .put("experimentId", "EXP-1")
          .put("bucketFrom", 0)
          .put("bucketTo", 10000);
    } else {
      entry.putArray("challengers");
    }
    entry
        .putArray("servable")
        .addObject()
        .put("bundleHash", championHash)
        .put("deploymentId", "dep-champion")
        .putNull("championUntil");
    entry
        .withArray("servable")
        .addObject()
        .put("bundleHash", retiredHash)
        .put("deploymentId", "dep-retired")
        .put("championUntil", "2026-08-14T05:00:00Z");

    Path file = tmp.resolve("manifest.json");
    Files.writeString(
        file, PointerManifest.sign(root, "manifest-cell7", MANIFEST_KEY.getPrivate()).toString());
    FilePointerSource source = new FilePointerSource(file, MANIFEST_KEY.getPublic());
    source.refresh();
    return source;
  }

  private PolicyResolver resolver(FilePointerSource source) {
    return new PolicyResolver(source, cache, 90);
  }

  @Nested
  @DisplayName("choosing a bundle")
  class Choosing {

    @Test
    void theChampionIsServedByDefault() throws IOException {
      PolicyResolution resolution =
          resolver(pointerSource(false, true))
              .resolve("t_acme", "PROD", "MFI_JLG_CREDIT_UW", "APP-1", Optional.empty(), NOW);

      assertThat(resolution.kind()).isEqualTo(PolicyResolution.Kind.CHAMPION);
      assertThat(resolution.bundleHash()).isEqualTo(championHash);
      assertThat(resolution.policy().rules())
          .singleElement()
          .satisfies(r -> assertThat(r.code()).isEqualTo("CHAMPION_RULE"));
    }

    @Test
    void aChallengerCoveringEveryBucketTakesTheTraffic() throws IOException {
      PolicyResolution resolution =
          resolver(pointerSource(true, true))
              .resolve("t_acme", "PROD", "MFI_JLG_CREDIT_UW", "APP-1", Optional.empty(), NOW);

      assertThat(resolution.kind()).isEqualTo(PolicyResolution.Kind.CHALLENGER);
      assertThat(resolution.experimentId()).contains("EXP-1");
      assertThat(resolution.bundleHash()).isEqualTo(challengerHash);
    }

    /** The same application must land in the same bucket on every retry and every pod (§4.11). */
    @Test
    void theBucketIsDeterministicForAnApplication() {
      int first = TrafficSplitter.bucketOf("t_acme", "APP-2026-000123", "EXP-1");
      for (int i = 0; i < 50; i++) {
        assertThat(TrafficSplitter.bucketOf("t_acme", "APP-2026-000123", "EXP-1"))
            .as("a random split would give one applicant two policies on a retry")
            .isEqualTo(first);
      }
    }
  }

  @Nested
  @DisplayName("§8.1 pinning")
  class Pinning {

    @Test
    void aPinnedCurrentChampionIsHonoured() throws IOException {
      PolicyResolution resolution =
          resolver(pointerSource(false, true))
              .resolve(
                  "t_acme", "PROD", "MFI_JLG_CREDIT_UW", "APP-1", Optional.of(championHash), NOW);

      assertThat(resolution.kind()).isEqualTo(PolicyResolution.Kind.PINNED);
      assertThat(resolution.bundleHash()).isEqualTo(championHash);
    }

    @Test
    void aPinnedRetiredChampionIsHonouredInsideTheWindow() throws IOException {
      PolicyResolution resolution =
          resolver(pointerSource(false, true))
              .resolve(
                  "t_acme", "PROD", "MFI_JLG_CREDIT_UW", "APP-1", Optional.of(retiredHash), NOW);

      assertThat(resolution.kind()).isEqualTo(PolicyResolution.Kind.PINNED);
      assertThat(resolution.policy().rules())
          .singleElement()
          .satisfies(r -> assertThat(r.code()).isEqualTo("RETIRED_RULE"));
    }

    @Test
    void aPinOutsideTheWindowIsRefusedRatherThanSilentlyDowngraded() throws IOException {
      PolicyResolver narrow = new PolicyResolver(pointerSource(false, true), cache, 30);

      assertThatThrownBy(
              () ->
                  narrow.resolve(
                      "t_acme",
                      "PROD",
                      "MFI_JLG_CREDIT_UW",
                      "APP-1",
                      Optional.of(retiredHash),
                      NOW))
          .as(
              "serving today's champion to a caller who asked for a specific bundle is worse"
                  + " than refusing")
          .isInstanceOf(PolicyResolutionException.class)
          .hasMessageContaining("PIN_REFUSED");
    }

    /** A pinned request asks for one policy; routing it into an experiment contradicts that. */
    @Test
    void aPinnedRequestIgnoresChallengers() throws IOException {
      PolicyResolution resolution =
          resolver(pointerSource(true, true))
              .resolve(
                  "t_acme", "PROD", "MFI_JLG_CREDIT_UW", "APP-1", Optional.of(championHash), NOW);

      assertThat(resolution.kind()).isEqualTo(PolicyResolution.Kind.PINNED);
      assertThat(resolution.bundleHash()).isEqualTo(championHash);
      assertThat(resolution.experimentId()).isEmpty();
    }

    @Test
    void aTenantCanForbidPinningEntirely() throws IOException {
      assertThatThrownBy(
              () ->
                  resolver(pointerSource(false, false))
                      .resolve(
                          "t_acme",
                          "PROD",
                          "MFI_JLG_CREDIT_UW",
                          "APP-1",
                          Optional.of(championHash),
                          NOW))
          .isInstanceOf(PolicyResolutionException.class)
          .hasMessageContaining("PIN_REFUSED");
    }

    @Test
    void aBundleThatWasNeverChampionCannotBePinned() throws IOException {
      assertThatThrownBy(
              () ->
                  resolver(pointerSource(true, true))
                      .resolve(
                          "t_acme",
                          "PROD",
                          "MFI_JLG_CREDIT_UW",
                          "APP-1",
                          Optional.of(challengerHash),
                          NOW))
          .as("a challenger is not in servable, so it is not pinnable")
          .isInstanceOf(PolicyResolutionException.class)
          .hasMessageContaining("PIN_REFUSED");
    }
  }

  @Nested
  @DisplayName("fail-closed")
  class FailClosed {

    @Test
    void aPodWithNoManifestServesNothing() {
      PolicyResolver blind = new PolicyResolver(Optional::<PointerManifest>empty, cache, 90);

      assertThatThrownBy(
              () ->
                  blind.resolve(
                      "t_acme", "PROD", "MFI_JLG_CREDIT_UW", "APP-1", Optional.empty(), NOW))
          .isInstanceOf(PolicyResolutionException.class)
          .hasMessageContaining("NO_MANIFEST");
    }

    @Test
    void anUnservedTenantOrPolicyIsRefused() throws IOException {
      PolicyResolver resolver = resolver(pointerSource(false, true));

      assertThatThrownBy(
              () ->
                  resolver.resolve(
                      "t_other", "PROD", "MFI_JLG_CREDIT_UW", "A", Optional.empty(), NOW))
          .isInstanceOf(PolicyResolutionException.class)
          .hasMessageContaining("NOT_SERVED");
      assertThatThrownBy(
              () ->
                  resolver.resolve(
                      "t_acme", "UAT", "MFI_JLG_CREDIT_UW", "A", Optional.empty(), NOW))
          .isInstanceOf(PolicyResolutionException.class)
          .hasMessageContaining("NOT_SERVED");
    }

    @Test
    void anUnsignedManifestIsNotAdopted() throws IOException {
      Path file = tmp.resolve("unsigned.json");
      ObjectNode root = MAPPER.createObjectNode();
      root.put("format", PointerManifest.FORMAT)
          .put("cellId", 7)
          .put("manifestVersion", 1L)
          .put("issuedAt", NOW.toString())
          .putArray("entries");
      Files.writeString(file, root.toString());

      FilePointerSource source = new FilePointerSource(file, MANIFEST_KEY.getPublic());
      assertThat(source.refresh()).isFalse();
      assertThat(source.current()).isEmpty();
    }

    /** Versions are monotonic; an older one is a replay or an out-of-band rollback (§8.1). */
    @Test
    void anOlderManifestVersionIsNotAdopted() throws IOException {
      FilePointerSource source = pointerSource(false, true);
      assertThat(source.current().orElseThrow().manifestVersion()).isEqualTo(1L);

      ObjectNode older = MAPPER.createObjectNode();
      older
          .put("format", PointerManifest.FORMAT)
          .put("cellId", 7)
          .put("manifestVersion", 0L)
          .put("issuedAt", NOW.toString())
          .putArray("entries");
      Files.writeString(
          tmp.resolve("manifest.json"),
          PointerManifest.sign(older, "manifest-cell7", MANIFEST_KEY.getPrivate()).toString());

      assertThat(source.refresh()).isFalse();
      assertThat(source.current().orElseThrow().manifestVersion()).isEqualTo(1L);
    }

    @Test
    void aMissingManifestFileLeavesWhatIsAlreadyInForce() throws IOException {
      FilePointerSource source = pointerSource(false, true);
      Files.delete(tmp.resolve("manifest.json"));

      assertThat(source.refresh()).isFalse();
      assertThat(source.current()).as("§8.5: keep serving what we have").isPresent();
    }
  }

  @Nested
  @DisplayName("the cache verifies")
  class Caching {

    @Test
    void aBundleIsVerifiedOnceAndThenCached() {
      assertThat(cache.isCached(championHash)).isFalse();
      cache.policy(championHash);
      assertThat(cache.isCached(championHash)).isTrue();
      assertThat(cache.policy(championHash)).isSameAs(cache.policy(championHash));
    }

    @Test
    void aBundleSignedByAnUntrustedKeyIsNeverCached() throws IOException {
      KeyPair attacker = BundleSigner.generateKeyPair();
      Bundle rogue =
          BundleSigner.sign(
              MAPPER.createObjectNode(),
              ir("ROGUE", "true"),
              MAPPER.createObjectNode(),
              "attacker",
              Bundle.Role.ORIGIN,
              attacker.getPrivate());
      store.put(rogue.bundleHash(), rogue.toJson());

      assertThatThrownBy(() -> cache.policy(rogue.bundleHash()))
          .isInstanceOf(BundleException.class)
          .hasMessageContaining("UNTRUSTED_KEY");
      assertThat(cache.isCached(rogue.bundleHash()))
          .as("a failure must not be amortised into a success by a second request")
          .isFalse();
    }

    /** A store that returns the wrong bundle for a hash must be caught, not trusted. */
    @Test
    void aStoreReturningTheWrongBundleIsRejected() throws IOException {
      String wrongContent =
          Files.readString(
              tmp.resolve("bundles").resolve(FileBundleStore.fileNameOf(challengerHash)));
      Files.writeString(
          tmp.resolve("bundles").resolve(FileBundleStore.fileNameOf(retiredHash)), wrongContent);

      assertThatThrownBy(() -> cache.policy(retiredHash))
          .isInstanceOf(BundleException.class)
          .hasMessageContaining("HASH_MISMATCH");
    }

    @Test
    void anAbsentBundleIsAnError() {
      assertThatThrownBy(() -> cache.policy("sha256:notinthestore"))
          .isInstanceOf(BundleException.class)
          .hasMessageContaining("no bundle in this store");
    }
  }
}
