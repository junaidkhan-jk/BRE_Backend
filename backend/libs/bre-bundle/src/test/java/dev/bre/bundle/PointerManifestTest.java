package dev.bre.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The Active Pointer Manifest: signing, and the pinning window (LLD §8.1). */
class PointerManifestTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final KeyPair CELL_KEY = BundleSigner.generateKeyPair();
  private static final KeyPair OTHER_KEY = BundleSigner.generateKeyPair();

  private static ObjectNode document() {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("format", PointerManifest.FORMAT);
    root.put("cellId", 7);
    root.put("manifestVersion", 48213L);
    root.put("issuedAt", "2026-10-01T03:30:00.215Z");
    ObjectNode entry = root.putArray("entries").addObject();
    entry.put("tenantId", "t_acme");
    entry.put("environment", "PROD");
    entry.put("policyCode", "MFI_JLG_CREDIT_UW");
    entry.put("timeZone", "Asia/Kolkata");
    entry.put("pinningAllowed", true);
    entry
        .putObject("champion")
        .put("deploymentId", "0192e8d0-11aa-7c3e-9f00-5a5b5c5d5e5f")
        .put("bundleHash", "sha256:3f6c");
    entry
        .putArray("challengers")
        .addObject()
        .put("deploymentId", "0192e9aa-2b3c-7d4e-8f50-617283940a1b")
        .put("bundleHash", "sha256:7a1b")
        .put("experimentId", "MFI_JLG_CREDIT_UW:EXP-1")
        .put("bucketFrom", 0)
        .put("bucketTo", 1000);
    ObjectNode servable = entry.putArray("servable").addObject();
    servable
        .put("bundleHash", "sha256:3f6c")
        .put("deploymentId", "0192e8d0-11aa-7c3e-9f00-5a5b5c5d5e5f")
        .putNull("championUntil");
    entry
        .withArray("servable")
        .addObject()
        .put("bundleHash", "sha256:9d2e")
        .put("deploymentId", "0192c111-3c4d-7e5f-9a60-718293a4b5c6")
        .put("championUntil", "2026-08-14T05:00:00Z");
    return root;
  }

  @Nested
  @DisplayName("signing")
  class Signing {

    @Test
    void aSignedManifestVerifiesWithTheCellKey() {
      ObjectNode signed =
          PointerManifest.sign(document(), "manifest-cell7-2026-01", CELL_KEY.getPrivate());

      assertThat(PointerManifest.verify(signed, CELL_KEY.getPublic())).isTrue();
      assertThat(signed.at("/signature/alg").asText()).isEqualTo("Ed25519");
      assertThat(signed.at("/signature/kid").asText()).isEqualTo("manifest-cell7-2026-01");
    }

    @Test
    void anotherKeyDoesNotVerify() {
      ObjectNode signed =
          PointerManifest.sign(document(), "manifest-cell7-2026-01", CELL_KEY.getPrivate());
      assertThat(PointerManifest.verify(signed, OTHER_KEY.getPublic())).isFalse();
    }

    /** Changing which bundle is champion must invalidate the signature. */
    @Test
    void repointingTheChampionInvalidatesTheSignature() {
      ObjectNode signed =
          PointerManifest.sign(document(), "manifest-cell7-2026-01", CELL_KEY.getPrivate());
      ((ObjectNode) signed.at("/entries/0/champion")).put("bundleHash", "sha256:attacker");

      assertThat(PointerManifest.verify(signed, CELL_KEY.getPublic()))
          .as("the manifest is what says WHAT to serve; tampering with it must not verify")
          .isFalse();
    }

    @Test
    void anUnsignedManifestDoesNotVerify() {
      assertThat(PointerManifest.verify(document(), CELL_KEY.getPublic())).isFalse();
    }

    /**
     * A manifest and a bundle sign different prefixes, so one signature can never be presented as
     * the other (§8.1 vs §2.6).
     */
    @Test
    void theSigningPrefixDiffersFromABundles() {
      assertThat(PointerManifest.SIGNING_PREFIX).isEqualTo("bre-manifest/1:");
      assertThat(Bundle.SIGNING_PREFIX).isEqualTo("bre-bundle/1:");
      assertThat(PointerManifest.SIGNING_PREFIX).isNotEqualTo(Bundle.SIGNING_PREFIX);
    }
  }

  @Nested
  @DisplayName("§8.1 pinning window")
  class Pinning {

    private final PointerManifest.Entry entry =
        PointerManifest.of(PointerManifest.sign(document(), "k", CELL_KEY.getPrivate()))
            .entry("t_acme", "PROD", "MFI_JLG_CREDIT_UW")
            .orElseThrow();

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

    @Test
    void theCurrentChampionIsAlwaysServable() {
      assertThat(PointerManifest.mayServePinned(entry, "sha256:3f6c", NOW, 90)).isTrue();
    }

    @Test
    void aRetiredChampionIsServableInsideTheWindow() {
      // championUntil 2026-08-14, so 90 days reaches 2026-11-12.
      assertThat(PointerManifest.mayServePinned(entry, "sha256:9d2e", NOW, 90)).isTrue();
    }

    @Test
    void aRetiredChampionIsRefusedOutsideTheWindow() {
      assertThat(PointerManifest.mayServePinned(entry, "sha256:9d2e", NOW, 30))
          .as("2026-08-14 + 30 days is before 2026-10-01")
          .isFalse();
    }

    @Test
    void theBoundaryIsInclusive() {
      Instant exactly = Instant.parse("2026-08-14T05:00:00Z").plus(Duration.ofDays(90));
      assertThat(PointerManifest.mayServePinned(entry, "sha256:9d2e", exactly, 90)).isTrue();
      assertThat(PointerManifest.mayServePinned(entry, "sha256:9d2e", exactly.plusSeconds(1), 90))
          .isFalse();
    }

    @Test
    void aBundleNotInServableIsRefused() {
      assertThat(PointerManifest.mayServePinned(entry, "sha256:neverdeployed", NOW, 90)).isFalse();
    }

    @Test
    void pinningCanBeDisabledForATenant() {
      PointerManifest.Entry noPinning =
          new PointerManifest.Entry(
              entry.tenantId(),
              entry.environment(),
              entry.policyCode(),
              entry.timeZone(),
              false,
              entry.champion(),
              entry.challengers(),
              entry.servable());

      assertThat(PointerManifest.mayServePinned(noPinning, "sha256:3f6c", NOW, 90))
          .as("not even the current champion, when the tenant forbids pinning")
          .isFalse();
    }
  }

  @Nested
  @DisplayName("parsing")
  class Parsing {

    @Test
    void theEntryAndItsTargetsAreRead() {
      PointerManifest manifest = PointerManifest.of(document());

      assertThat(manifest.cellId()).isEqualTo(7);
      assertThat(manifest.manifestVersion()).isEqualTo(48213L);

      PointerManifest.Entry entry =
          manifest.entry("t_acme", "PROD", "MFI_JLG_CREDIT_UW").orElseThrow();
      assertThat(entry.champion().bundleHash()).isEqualTo("sha256:3f6c");
      assertThat(entry.challengers())
          .singleElement()
          .satisfies(
              c -> {
                assertThat(c.experimentId()).isEqualTo("MFI_JLG_CREDIT_UW:EXP-1");
                assertThat(c.bucketFrom()).isZero();
                assertThat(c.bucketTo()).isEqualTo(1000);
              });
      assertThat(entry.servable()).hasSize(2);
      assertThat(entry.servable().get(0).championUntil()).isEmpty();
      assertThat(entry.servable().get(1).championUntil())
          .contains(Instant.parse("2026-08-14T05:00:00Z"));
    }

    @Test
    void anUnknownTenantOrPolicyYieldsNoEntry() {
      PointerManifest manifest = PointerManifest.of(document());

      assertThat(manifest.entry("t_other", "PROD", "MFI_JLG_CREDIT_UW")).isEmpty();
      assertThat(manifest.entry("t_acme", "UAT", "MFI_JLG_CREDIT_UW")).isEmpty();
      assertThat(manifest.entry("t_acme", "PROD", "OTHER_POLICY")).isEmpty();
    }

    @Test
    void theWrongFormatIsRejected() {
      ObjectNode wrong = document();
      wrong.put("format", "bre-bundle/1");
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> PointerManifest.of(wrong))
          .isInstanceOf(BundleException.class)
          .hasMessageContaining("bre-manifest/1");
    }
  }
}
