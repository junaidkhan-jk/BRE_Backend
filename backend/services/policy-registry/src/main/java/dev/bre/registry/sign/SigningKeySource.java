package dev.bre.registry.sign;

import dev.bre.bundle.BundleSigner;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Where the registry's signing keys come from (§8.6).
 *
 * <p><b>Two keys, and they must be different.</b> The bundle key says <em>what a policy
 * contains</em>; the manifest key says <em>what to serve</em>. §8.6 separates them so that a
 * compromise of one is not automatically a compromise of the other: whoever holds the manifest key
 * can point traffic at any bundle that already exists, and whoever holds the bundle key can mint a
 * policy but cannot make a cell serve it. Needing both is a meaningfully higher bar than needing
 * one.
 *
 * <p><b>In production neither key is here at all.</b> They are generated in, and never leave, the
 * KMS or HSM; this class becomes a client that asks the KMS to sign. The local form exists so the
 * registry runs on a laptop, and it is loud about itself: a configured private key in a deployed
 * environment is the finding.
 *
 * <p>No key configured means <b>no signing</b>, and the service still starts. That is deliberate:
 * an author must be able to write and test a policy without a key, and the refusal belongs at the
 * approval — the one operation that actually needs to sign — where the message can say what is
 * missing. Refusing to start would make a missing key look like a broken build.
 */
@Component
public class SigningKeySource {

  private static final Logger log = LoggerFactory.getLogger(SigningKeySource.class);

  /** A signing key and its identifier. */
  public record Key(String kid, PrivateKey privateKey) {}

  /** No signing key is configured. Mapped to 409, not 500 — it is a configuration state. */
  public static final class NoSigningKeyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    NoSigningKeyException(String message) {
      super(message);
    }
  }

  private final Key bundleKey;
  private final Key manifestKey;

  /**
   * Creates the source.
   *
   * @param bundleKeyBase64 base64 PKCS#8 Ed25519, local development only
   * @param bundleKid the bundle key identifier (§9.3)
   * @param manifestKeyBase64 base64 PKCS#8 Ed25519, local development only
   * @param manifestKid the manifest key identifier
   */
  public SigningKeySource(
      @Value("${bre.registry.bundle-signing-key:}") String bundleKeyBase64,
      @Value("${bre.registry.bundle-signing-kid:reg-local-2026-01}") String bundleKid,
      @Value("${bre.registry.manifest-signing-key:}") String manifestKeyBase64,
      @Value("${bre.registry.manifest-signing-kid:manifest-cell1-2026-01}") String manifestKid) {

    this.bundleKey = load(bundleKeyBase64, bundleKid, "bundle");
    this.manifestKey = load(manifestKeyBase64, manifestKid, "manifest");

    if (bundleKey != null && manifestKey != null) {
      if (bundleKey.privateKey().equals(manifestKey.privateKey())) {
        // Refusing outright. Using one key for both collapses §8.6's separation silently: nothing
        // would fail, and the installation would simply have one key where it believes it has two.
        throw new IllegalStateException(
            "the bundle and manifest signing keys are the same key. §8.6 separates them so that a"
                + " compromise of what-to-serve is not also a compromise of what-a-policy-contains."
                + " Generate two.");
      }
      log.warn(
          "USING LOCALLY CONFIGURED SIGNING KEYS (kids {} and {}). A production key is generated"
              + " in, and never leaves, the KMS or HSM (§8.6). If this message appears in a"
              + " deployed environment, that is the finding.",
          bundleKey.kid(),
          manifestKey.kid());
    } else {
      log.warn(
          "no signing keys configured: policies can be written, compiled and tested, but an"
              + " approval cannot sign a bundle and a deployment cannot sign a manifest. Set"
              + " bre.registry.bundle-signing-key and bre.registry.manifest-signing-key, or point"
              + " this service at a KMS.");
    }
  }

  /**
   * The bundle signing key.
   *
   * @return the key
   * @throws NoSigningKeyException when none is configured
   */
  public Key bundleKey() {
    if (bundleKey == null) {
      throw new NoSigningKeyException(
          "no bundle signing key is configured, so this version cannot be signed and therefore"
              + " cannot be approved — an unsigned bundle is one no pod will serve (§8.3). Set"
              + " bre.registry.bundle-signing-key (generate a pair with: bre keygen) or configure"
              + " a KMS.");
    }
    return bundleKey;
  }

  /**
   * The manifest signing key.
   *
   * @return the key
   * @throws NoSigningKeyException when none is configured
   */
  public Key manifestKey() {
    if (manifestKey == null) {
      throw new NoSigningKeyException(
          "no manifest signing key is configured, so no Active Pointer Manifest can be published"
              + " and this deployment cannot take effect. Set"
              + " bre.registry.manifest-signing-key or configure a KMS.");
    }
    return manifestKey;
  }

  /**
   * Whether this installation can sign at all.
   *
   * @return true when both keys are present
   */
  public boolean canSign() {
    return bundleKey != null && manifestKey != null;
  }

  private static Key load(String base64, String kid, String what) {
    if (base64 == null || base64.isBlank()) {
      return null;
    }
    try {
      PrivateKey key =
          KeyFactory.getInstance(BundleSigner.ALGORITHM)
              .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64.trim())));
      return new Key(kid, key);
    } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException(
          "the configured "
              + what
              + " signing key is not a base64 PKCS#8 Ed25519 private key. Starting without it"
              + " would mean discovering this at the first approval instead of now.",
          e);
    }
  }
}
