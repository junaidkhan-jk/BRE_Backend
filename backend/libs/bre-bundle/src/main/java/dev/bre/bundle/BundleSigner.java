package dev.bre.bundle;

import com.fasterxml.jackson.databind.JsonNode;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;

/**
 * Signs a bundle with Ed25519 (LLD §2.6).
 *
 * <p>Ed25519 rather than RSA or ECDSA, and the reasons are operational rather than aesthetic:
 * signatures are 64 bytes, verification is fast enough to do on every bundle load without a cache,
 * and — the one that matters here — Ed25519 is deterministic. ECDSA needs a random nonce per
 * signature, and a repeated or predictable nonce leaks the private key. A signing key that could
 * leak from a weak entropy source on one pod is not a key you want signing credit policy.
 *
 * <p>The private key is a parameter, never a field. In production it lives in a KMS or HSM (§8.6)
 * and this class is handed a {@code PrivateKey} handle that performs the operation without exposing
 * the material. Nothing here ever holds, logs or serialises a private key.
 */
public final class BundleSigner {

  /** The only algorithm a bundle signature may use. */
  public static final String ALGORITHM = "Ed25519";

  private BundleSigner() {}

  /**
   * Builds and signs a bundle from its content.
   *
   * <p>The hash is computed from the content given here, so a bundle produced by this method cannot
   * have a hash that disagrees with what it contains.
   *
   * @param manifest the manifest
   * @param ir the policy IR
   * @param assets the resolved assets
   * @param kid the signing key's identifier
   * @param role what the signature speaks for
   * @param privateKey the signing key
   * @return the signed bundle
   */
  public static Bundle sign(
      JsonNode manifest,
      JsonNode ir,
      JsonNode assets,
      String kid,
      Bundle.Role role,
      PrivateKey privateKey) {

    String bundleHash = Bundle.computeHash(manifest, ir, assets);
    Bundle.Signature signature = signature(bundleHash, kid, role, privateKey);
    return new Bundle(
        Bundle.FORMAT, bundleHash, manifest, ir, assets, java.util.List.of(signature));
  }

  /**
   * Countersigns an existing bundle, for an {@code IMPORT} signature (§8.6).
   *
   * <p>The hash is <b>recomputed</b> rather than taken from the document. Countersigning the
   * claimed hash would let an importing registry put its own trusted signature on content it never
   * actually hashed — which is precisely the attack the import flow exists to prevent.
   *
   * @param bundle the bundle to countersign
   * @param kid the countersigning key's identifier
   * @param role what the signature speaks for
   * @param privateKey the signing key
   * @return the bundle with the extra signature
   * @throws BundleException if the bundle's content does not hash to its claimed address
   */
  public static Bundle countersign(
      Bundle bundle, String kid, Bundle.Role role, PrivateKey privateKey) {
    String recomputed = bundle.recomputeHash();
    if (!recomputed.equals(bundle.bundleHash())) {
      throw new BundleException(
          BundleException.Reason.HASH_MISMATCH,
          "refusing to countersign: content hashes to "
              + recomputed
              + " but the bundle claims "
              + bundle.bundleHash());
    }
    return bundle.withSignature(signature(recomputed, kid, role, privateKey));
  }

  private static Bundle.Signature signature(
      String bundleHash, String kid, Bundle.Role role, PrivateKey privateKey) {
    try {
      Signature signer = Signature.getInstance(ALGORITHM);
      signer.initSign(privateKey);
      signer.update(Bundle.signingInput(bundleHash));
      String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
      return new Bundle.Signature(kid, ALGORITHM, role, encoded);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("the bundle could not be signed", e);
    }
  }

  /**
   * Generates an Ed25519 key pair.
   *
   * <p>For tests and for a development installation that has no KMS. A production installation's
   * key is generated in, and never leaves, the KMS or HSM (§8.6).
   *
   * @return a new key pair
   */
  public static KeyPair generateKeyPair() {
    try {
      return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(ALGORITHM + " is required of every Java 17+ platform", e);
    }
  }
}
