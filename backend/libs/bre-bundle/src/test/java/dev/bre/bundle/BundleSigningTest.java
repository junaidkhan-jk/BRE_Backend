package dev.bre.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.KeyPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Bundle content addressing, Ed25519 signing and verification (LLD §2.6, §8.6). */
class BundleSigningTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final KeyPair OWN = BundleSigner.generateKeyPair();
  private static final KeyPair UPSTREAM = BundleSigner.generateKeyPair();
  private static final KeyPair ATTACKER = BundleSigner.generateKeyPair();

  private static JsonNode json(String text) {
    try {
      return MAPPER.readTree(text);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static JsonNode manifest() {
    return json(
        """
        { "tenantId": "t_acme", "policyCode": "MFI_JLG_CREDIT_UW", "policyVersion": 3,
          "createdAt": "2026-10-01T09:12:44Z",
          "engine": { "irVersion": 1, "minVersion": "1.4.0" },
          "staticCheck": { "errors": 0, "warnings": 2 },
          "tests": { "total": 42, "passed": 42, "smoke": 6 } }
        """);
  }

  private static JsonNode ir() {
    return json(
        """
        { "irVersion": 1, "rules": [ { "code": "MFI_INDEBTEDNESS_CAP",
            "check": "memberUnsecuredOutstanding <= param.INDEBTEDNESS_CAP" } ] }
        """);
  }

  private static JsonNode assets() {
    return json(
        """
        { "parameterSet": { "code": "MFI_PARAMS", "version": 7, "parameters": [
            { "name": "INDEBTEDNESS_CAP", "type": "Decimal",
              "values": [ { "effectiveFrom": "2025-04-01", "value": "200000.00" } ] } ] } }
        """);
  }

  private static Bundle signedByOwn() {
    return BundleSigner.sign(
        manifest(), ir(), assets(), "reg-acme-prod-2026-01", Bundle.Role.ORIGIN, OWN.getPrivate());
  }

  private static TrustStore ownOnly() {
    return TrustStore.builder().ownBundleKey("reg-acme-prod-2026-01", OWN.getPublic()).build();
  }

  @Nested
  @DisplayName("content addressing")
  class ContentAddressing {

    @Test
    void theHashIsOverTheContentAndIsStable() {
      String first = Bundle.computeHash(manifest(), ir(), assets());
      String second = Bundle.computeHash(manifest(), ir(), assets());

      assertThat(first).isEqualTo(second).startsWith("sha256:").hasSize(7 + 64);
      assertThat(first.substring(7)).matches("[0-9a-f]{64}");
    }

    /** Two registries building the same policy must produce the same address. */
    @Test
    void keyOrderDoesNotChangeTheAddress() {
      ObjectNode oneOrder = MAPPER.createObjectNode();
      oneOrder.put("a", 1).put("b", 2);
      ObjectNode otherOrder = MAPPER.createObjectNode();
      otherOrder.put("b", 2).put("a", 1);

      assertThat(Bundle.computeHash(oneOrder, ir(), assets()))
          .as("BRE-JCS canonicalises, so JSON key order is not part of the content")
          .isEqualTo(Bundle.computeHash(otherOrder, ir(), assets()));
    }

    @Test
    void anyChangeToTheContentChangesTheAddress() {
      String original = Bundle.computeHash(manifest(), ir(), assets());

      ObjectNode tamperedIr = (ObjectNode) ir();
      tamperedIr
          .withArray("rules")
          .get(0)
          .withObject("")
          .put("check", "true"); // a rule that always passes
      assertThat(Bundle.computeHash(manifest(), tamperedIr, assets())).isNotEqualTo(original);

      ObjectNode tamperedAssets = (ObjectNode) assets();
      tamperedAssets
          .at("/parameterSet/parameters/0/values/0")
          .withObject("")
          .put("value", "99999999.00");
      assertThat(Bundle.computeHash(manifest(), ir(), tamperedAssets)).isNotEqualTo(original);
    }
  }

  @Nested
  @DisplayName("signing and verification")
  class Verification {

    @Test
    void aBundleSignedByThisInstallationVerifies() {
      BundleVerifier.Verified verified = new BundleVerifier(ownOnly()).verify(signedByOwn());

      assertThat(verified.kid()).isEqualTo("reg-acme-prod-2026-01");
      assertThat(verified.role()).isEqualTo(Bundle.Role.ORIGIN);
      assertThat(verified.bundleHash()).isEqualTo(signedByOwn().bundleHash());
    }

    @Test
    void aBundleSurvivesASerialisationRoundTrip() {
      Bundle parsed = Bundle.parse(signedByOwn().toJson());

      assertThat(parsed.bundleHash()).isEqualTo(signedByOwn().bundleHash());
      assertThat(new BundleVerifier(ownOnly()).verify(parsed).kid())
          .isEqualTo("reg-acme-prod-2026-01");
    }

    /**
     * THE attack this whole class exists to stop. The attacker swaps the IR for one that approves
     * everything, and keeps the original bundleHash string and its original, genuinely valid
     * signature. A verifier that checks the signature against the CLAIMED hash accepts it, because
     * the signature really was made over that hash — just not over this content.
     */
    @Test
    void aSwappedIrWithTheOriginalHashAndSignatureIsRejected() {
      Bundle genuine = signedByOwn();

      ObjectNode approveEverything = MAPPER.createObjectNode();
      approveEverything.put("irVersion", 1).putArray("rules");

      Bundle tampered =
          new Bundle(
              genuine.format(),
              genuine.bundleHash(), // the original address, untouched
              genuine.manifest(),
              approveEverything, // different policy
              genuine.assets(),
              genuine.signatures()); // the original, still-valid signature

      assertThatThrownBy(() -> new BundleVerifier(ownOnly()).verify(tampered))
          .isInstanceOf(BundleException.class)
          .hasMessageContaining("HASH_MISMATCH")
          .satisfies(
              e ->
                  assertThat(((BundleException) e).reason())
                      .isEqualTo(BundleException.Reason.HASH_MISMATCH));
    }

    /** Tampering with a parameter value is the quiet version of the same attack. */
    @Test
    void aRaisedParameterWithTheOriginalSignatureIsRejected() {
      Bundle genuine = signedByOwn();
      ObjectNode raised = (ObjectNode) assets();
      ((ObjectNode) raised.at("/parameterSet/parameters/0/values/0")).put("value", "99999999.00");

      assertThatThrownBy(
              () ->
                  new BundleVerifier(ownOnly())
                      .verify(
                          new Bundle(
                              genuine.format(),
                              genuine.bundleHash(),
                              genuine.manifest(),
                              genuine.ir(),
                              raised,
                              genuine.signatures())))
          .isInstanceOf(BundleException.class)
          .hasMessageContaining("HASH_MISMATCH");
    }

    @Test
    void anUnsignedBundleIsRejected() {
      Bundle genuine = signedByOwn();
      Bundle unsigned =
          new Bundle(
              genuine.format(),
              genuine.bundleHash(),
              genuine.manifest(),
              genuine.ir(),
              genuine.assets(),
              java.util.List.of());

      assertThatThrownBy(() -> new BundleVerifier(ownOnly()).verify(unsigned))
          .isInstanceOf(BundleException.class)
          .hasMessageContaining("MISSING_SIGNATURE");
    }

    @Test
    void aSignatureFromAnUnknownKeyIsRejected() {
      Bundle byAttacker =
          BundleSigner.sign(
              manifest(),
              ir(),
              assets(),
              "attacker-key",
              Bundle.Role.ORIGIN,
              ATTACKER.getPrivate());

      assertThatThrownBy(() -> new BundleVerifier(ownOnly()).verify(byAttacker))
          .isInstanceOf(BundleException.class)
          .hasMessageContaining("UNTRUSTED_KEY");
    }

    /** A real key id with someone else's signature under it. */
    @Test
    void aForgedSignatureUnderATrustedKeyIdIsRejected() {
      Bundle forged =
          BundleSigner.sign(
              manifest(),
              ir(),
              assets(),
              "reg-acme-prod-2026-01",
              Bundle.Role.ORIGIN,
              ATTACKER.getPrivate());

      assertThatThrownBy(() -> new BundleVerifier(ownOnly()).verify(forged))
          .isInstanceOf(BundleException.class)
          .hasMessageContaining("BAD_SIGNATURE");
    }

    @Test
    void aMalformedSignatureIsAFailedVerificationNotACrash() {
      Bundle genuine = signedByOwn();
      Bundle garbled =
          new Bundle(
              genuine.format(),
              genuine.bundleHash(),
              genuine.manifest(),
              genuine.ir(),
              genuine.assets(),
              java.util.List.of(
                  new Bundle.Signature(
                      "reg-acme-prod-2026-01", "Ed25519", Bundle.Role.ORIGIN, "not!base64!")));

      assertThatThrownBy(() -> new BundleVerifier(ownOnly()).verify(garbled))
          .isInstanceOf(BundleException.class)
          .hasMessageContaining("BAD_SIGNATURE");
    }

    @Test
    void anAlgorithmOtherThanEd25519IsRejected() {
      Bundle genuine = signedByOwn();
      Bundle wrongAlg =
          new Bundle(
              genuine.format(),
              genuine.bundleHash(),
              genuine.manifest(),
              genuine.ir(),
              genuine.assets(),
              java.util.List.of(
                  new Bundle.Signature(
                      "reg-acme-prod-2026-01",
                      "RS256",
                      Bundle.Role.ORIGIN,
                      genuine.signatures().get(0).sig())));

      assertThatThrownBy(() -> new BundleVerifier(ownOnly()).verify(wrongAlg))
          .isInstanceOf(BundleException.class)
          .hasMessageContaining("BAD_SIGNATURE");
    }

    /** Domain separation: a signature over a different artifact's message must not transfer. */
    @Test
    void theSigningInputIsDomainSeparated() {
      String hash = signedByOwn().bundleHash();
      assertThat(new String(Bundle.signingInput(hash), java.nio.charset.StandardCharsets.UTF_8))
          .isEqualTo("bre-bundle/1:" + hash)
          .as("the Active Pointer Manifest signs 'bre-manifest/1:' over a different digest")
          .doesNotStartWith("bre-manifest/1:");
    }
  }

  @Nested
  @DisplayName("§8.6 trust")
  class Trust {

    /**
     * A pod trusts only its own installation's key. An upstream UAT registry's ORIGIN signature
     * must not be enough on its own — that indirection is what stops a compromised upstream pushing
     * policy straight into another installation's data plane.
     */
    @Test
    void aPodDoesNotTrustAnUpstreamOriginSignatureDirectly() {
      Bundle fromUpstream =
          BundleSigner.sign(
              manifest(),
              ir(),
              assets(),
              "uat-installation-2026",
              Bundle.Role.ORIGIN,
              UPSTREAM.getPrivate());

      // The pod's store holds only its own keys — no exporters.
      assertThatThrownBy(() -> new BundleVerifier(ownOnly()).verify(fromUpstream))
          .isInstanceOf(BundleException.class)
          .hasMessageContaining("UNTRUSTED_KEY");
    }

    @Test
    void theRegistryAcceptsATrustedExportersOriginSignatureAndCountersignsIt() {
      Bundle fromUpstream =
          BundleSigner.sign(
              manifest(),
              ir(),
              assets(),
              "uat-installation-2026",
              Bundle.Role.ORIGIN,
              UPSTREAM.getPrivate());

      // The importing REGISTRY has the exporter in its store, so it can verify...
      TrustStore registryStore =
          TrustStore.builder()
              .ownBundleKey("reg-acme-prod-2026-01", OWN.getPublic())
              .trustedExporter("uat-installation-2026", UPSTREAM.getPublic())
              .build();
      assertThat(new BundleVerifier(registryStore).verify(fromUpstream).role())
          .isEqualTo(Bundle.Role.ORIGIN);

      // ...and countersigns, after which a pod with only its own keys accepts it.
      Bundle imported =
          BundleSigner.countersign(
              fromUpstream, "reg-acme-prod-2026-01", Bundle.Role.IMPORT, OWN.getPrivate());

      BundleVerifier.Verified verified = new BundleVerifier(ownOnly()).verify(imported);
      assertThat(verified.role()).isEqualTo(Bundle.Role.IMPORT);
      assertThat(verified.kid()).isEqualTo("reg-acme-prod-2026-01");
    }

    @Test
    void countersigningRefusesContentThatDoesNotMatchItsClaimedHash() {
      Bundle genuine = signedByOwn();
      ObjectNode swapped = MAPPER.createObjectNode();
      swapped.put("irVersion", 1).putArray("rules");
      Bundle tampered =
          new Bundle(
              genuine.format(),
              genuine.bundleHash(),
              genuine.manifest(),
              swapped,
              genuine.assets(),
              genuine.signatures());

      assertThatThrownBy(
              () ->
                  BundleSigner.countersign(
                      tampered, "reg-acme-prod-2026-01", Bundle.Role.IMPORT, OWN.getPrivate()))
          .as("an importing registry must not put its trusted signature on unhashed content")
          .isInstanceOf(BundleException.class)
          .hasMessageContaining("HASH_MISMATCH");
    }

    /** Rotation is overlap-based: a key change must not invalidate yesterday's bundles. */
    @Test
    void bothTheCurrentAndThePreviousKeyAreAccepted() {
      KeyPair previous = BundleSigner.generateKeyPair();
      Bundle signedWithPrevious =
          BundleSigner.sign(
              manifest(),
              ir(),
              assets(),
              "reg-acme-prod-2025-07",
              Bundle.Role.ORIGIN,
              previous.getPrivate());

      TrustStore overlapping =
          TrustStore.builder()
              .ownBundleKey("reg-acme-prod-2026-01", OWN.getPublic())
              .ownBundleKey("reg-acme-prod-2025-07", previous.getPublic())
              .build();

      assertThat(new BundleVerifier(overlapping).verify(signedWithPrevious).kid())
          .isEqualTo("reg-acme-prod-2025-07");
      assertThat(new BundleVerifier(overlapping).verify(signedByOwn()).kid())
          .isEqualTo("reg-acme-prod-2026-01");
    }

    /** A misconfigured pod loads nothing rather than everything. */
    @Test
    void anEmptyTrustStoreTrustsNothing() {
      assertThat(TrustStore.builder().build().isEmpty()).isTrue();
      assertThatThrownBy(
              () -> new BundleVerifier(TrustStore.builder().build()).verify(signedByOwn()))
          .isInstanceOf(BundleException.class)
          .hasMessageContaining("UNTRUSTED_KEY");
    }
  }

  @Nested
  @DisplayName("parsing")
  class Parsing {

    @Test
    void aDocumentOfTheWrongFormatIsRejected() {
      assertThatThrownBy(() -> Bundle.parse("{\"format\":\"bre-bundle/2\"}"))
          .isInstanceOf(BundleException.class)
          .hasMessageContaining("MALFORMED")
          .hasMessageContaining("bre-bundle/1");
    }

    @Test
    void aDocumentMissingARequiredFieldIsRejected() {
      assertThatThrownBy(
              () -> Bundle.parse("{\"format\":\"bre-bundle/1\",\"bundleHash\":\"sha256:aa\"}"))
          .isInstanceOf(BundleException.class)
          .hasMessageContaining("missing 'manifest'");
    }

    @Test
    void anUnknownSignatureRoleIsRejected() {
      String json = signedByOwn().toJson().replace("\"ORIGIN\"", "\"SUPERUSER\"");
      assertThatThrownBy(() -> Bundle.parse(json))
          .isInstanceOf(BundleException.class)
          .hasMessageContaining("not ORIGIN or IMPORT");
    }

    @Test
    void textThatIsNotJsonIsRejected() {
      assertThatThrownBy(() -> Bundle.parse("not json at all"))
          .isInstanceOf(BundleException.class)
          .hasMessageContaining("MALFORMED");
    }
  }
}
