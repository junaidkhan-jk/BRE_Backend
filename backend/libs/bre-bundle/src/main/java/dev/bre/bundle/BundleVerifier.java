package dev.bre.bundle;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Optional;

/**
 * Verifies a bundle before a pod will load it (LLD §2.6, §8.3, §8.6).
 *
 * <p><b>Three checks, in this order, and the order is the security.</b>
 *
 * <ol>
 *   <li><b>Recompute the hash from the content</b> and compare it with the address the bundle
 *       claims. This is the check that cannot be skipped: a verifier that validates the signature
 *       over the <em>claimed</em> {@code bundleHash} accepts any bundle whose IR has been replaced,
 *       because the attacker simply keeps the original hash string and its original valid
 *       signature. The signature then proves only that someone once signed <em>a</em> hash — not
 *       that this document is the thing that was signed.
 *   <li><b>Find a signature from a key this installation trusts.</b> A pod trusts only its own
 *       installation's bundle keys; an imported bundle must carry an {@code IMPORT}
 *       countersignature added by the local registry after it checked the {@code ORIGIN} signature
 *       against the trusted-exporter list.
 *   <li><b>Verify that signature over the domain-separated message.</b>
 * </ol>
 *
 * <p>Every failure is fail-closed: the bundle is not loaded, the pod keeps serving what it already
 * had, and the deployment is reported {@code LOAD_FAILED} (§8.4). A policy nobody can vouch for is
 * not a policy, and the alternative — loading it and hoping — decides real credit applications.
 */
public final class BundleVerifier {

  private final TrustStore trustStore;

  /**
   * Creates a verifier.
   *
   * @param trustStore the keys this installation accepts
   */
  public BundleVerifier(TrustStore trustStore) {
    this.trustStore = trustStore;
  }

  /**
   * What a successful verification established.
   *
   * @param bundleHash the recomputed content address
   * @param kid the key whose signature was accepted
   * @param role what that signature spoke for
   */
  public record Verified(String bundleHash, String kid, Bundle.Role role) {}

  /**
   * Verifies a bundle.
   *
   * @param bundle the bundle
   * @return what was established
   * @throws BundleException if the content, the signature or the key does not check out
   */
  public Verified verify(Bundle bundle) {
    // 1. The address must be a fact about the content, not a claim about it.
    String recomputed = bundle.recomputeHash();
    if (!recomputed.equals(bundle.bundleHash())) {
      throw new BundleException(
          BundleException.Reason.HASH_MISMATCH,
          "content hashes to " + recomputed + " but the bundle claims " + bundle.bundleHash());
    }

    if (bundle.signatures().isEmpty()) {
      throw new BundleException(
          BundleException.Reason.MISSING_SIGNATURE, "the bundle carries no signature");
    }

    // 2 and 3. A signature counts only if BOTH the key is trusted and the signature verifies.
    // Checked together so that a trusted key with a bad signature cannot be reported as merely
    // untrusted, which would send an operator looking at the wrong thing.
    BundleException last = null;
    for (Bundle.Signature signature : bundle.signatures()) {
      try {
        PublicKey key = trustedKeyFor(signature);
        if (verifies(recomputed, signature, key)) {
          return new Verified(recomputed, signature.kid(), signature.role());
        }
        last =
            new BundleException(
                BundleException.Reason.BAD_SIGNATURE,
                "the signature from '" + signature.kid() + "' does not verify over this content");
      } catch (BundleException untrusted) {
        last = untrusted;
      }
    }
    throw last;
  }

  /**
   * The public key for a signature, if this installation trusts it for that role.
   *
   * <p>An {@code ORIGIN} signature is accepted from this installation's own key — the normal case,
   * where the local registry approved the policy. A trusted exporter's {@code ORIGIN} signature is
   * accepted too, which is what lets the <em>registry</em> verify an import; a pod is given a trust
   * store containing only its own keys, so the same code refuses it there.
   */
  private PublicKey trustedKeyFor(Bundle.Signature signature) {
    if (!BundleSigner.ALGORITHM.equals(signature.alg())) {
      throw new BundleException(
          BundleException.Reason.BAD_SIGNATURE,
          "algorithm '" + signature.alg() + "' is not " + BundleSigner.ALGORITHM);
    }

    Optional<PublicKey> own = trustStore.ownBundleKey(signature.kid());
    if (own.isPresent()) {
      return own.get();
    }
    if (signature.role() == Bundle.Role.ORIGIN) {
      Optional<PublicKey> exporter = trustStore.trustedExporter(signature.kid());
      if (exporter.isPresent()) {
        return exporter.get();
      }
    }
    throw new BundleException(
        BundleException.Reason.UNTRUSTED_KEY,
        "key '"
            + signature.kid()
            + "' is not trusted by this installation for a "
            + signature.role()
            + " signature");
  }

  private static boolean verifies(String bundleHash, Bundle.Signature signature, PublicKey key) {
    try {
      Signature verifier = Signature.getInstance(BundleSigner.ALGORITHM);
      verifier.initVerify(key);
      verifier.update(Bundle.signingInput(bundleHash));
      return verifier.verify(Base64.getUrlDecoder().decode(signature.sig()));
    } catch (GeneralSecurityException | IllegalArgumentException malformed) {
      // A signature that is not even decodable is a failed verification, not a crash. An attacker
      // must not be able to turn a malformed field into a different code path.
      return false;
    }
  }
}
