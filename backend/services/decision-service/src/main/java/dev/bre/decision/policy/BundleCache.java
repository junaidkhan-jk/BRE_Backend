package dev.bre.decision.policy;

import dev.bre.bundle.Bundle;
import dev.bre.bundle.BundleException;
import dev.bre.bundle.BundleVerifier;
import dev.bre.engine.ir.Ir;
import dev.bre.irjson.IrCodec;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verified, decoded policies, keyed by content address (LLD §8.3).
 *
 * <p><b>Caching by hash is trivially safe here, and that is the point of content addressing.</b> A
 * bundle hash names exactly one sequence of bytes; there is no invalidation problem, no staleness
 * window and no need to re-check, because a different policy is a different key. That is worth
 * contrasting with caching by {@code (policyCode, version)}, which would need invalidating every
 * time a version was re-cut, and would silently serve the old one if anybody got that wrong.
 *
 * <p><b>Verification happens once, on the way in.</b> A bundle that fails verification is never
 * cached, so a failure cannot be amortised into a success by a second request. Signature
 * verification is cheap with Ed25519, but decoding and compiling is not, and doing it per decision
 * would put the whole compile pipeline on the hot path.
 */
public final class BundleCache {

  private final BundleStore store;
  private final BundleVerifier verifier;
  private final Map<String, Ir.PolicyIr> compiled = new ConcurrentHashMap<>();

  /**
   * Creates a cache.
   *
   * @param store where bundle bytes come from
   * @param verifier the installation's trust rules
   */
  public BundleCache(BundleStore store, BundleVerifier verifier) {
    this.store = store;
    this.verifier = verifier;
  }

  /**
   * Returns the policy for a bundle hash, fetching, verifying and decoding on first use.
   *
   * @param bundleHash the content address
   * @return the policy
   * @throws BundleException if the bundle is absent, unverifiable or untrusted
   */
  public Ir.PolicyIr policy(String bundleHash) {
    Ir.PolicyIr cached = compiled.get(bundleHash);
    if (cached != null) {
      return cached;
    }

    String document =
        store
            .fetch(bundleHash)
            .orElseThrow(
                () ->
                    new BundleException(
                        BundleException.Reason.MALFORMED,
                        "no bundle in this store for " + bundleHash));

    Bundle bundle = Bundle.parse(document);

    // The address asked for must be the address of what came back. Without this, a store that
    // returned the wrong bundle — a stale cache, a mixed-up key, a tampered object store — would
    // be serving a policy nobody asked for while every signature still checked out.
    if (!bundleHash.equals(bundle.bundleHash())) {
      throw new BundleException(
          BundleException.Reason.HASH_MISMATCH,
          "asked for " + bundleHash + " and the store returned " + bundle.bundleHash());
    }

    // Recomputes the hash and checks the signature against the trust store. Throws on any doubt,
    // and nothing is cached on that path.
    verifier.verify(bundle);

    Ir.PolicyIr policy = IrCodec.readPolicy(bundle.ir());
    compiled.put(bundleHash, policy);
    return policy;
  }

  /**
   * Whether a bundle has already been verified and decoded.
   *
   * @param bundleHash the address
   * @return true when it is cached
   */
  public boolean isCached(String bundleHash) {
    return compiled.containsKey(bundleHash);
  }

  /**
   * How many policies are held.
   *
   * @return the count
   */
  public int size() {
    return compiled.size();
  }

  /**
   * Returns a cached policy without fetching.
   *
   * @param bundleHash the address
   * @return the policy, if already cached
   */
  public Optional<Ir.PolicyIr> peek(String bundleHash) {
    return Optional.ofNullable(compiled.get(bundleHash));
  }
}
