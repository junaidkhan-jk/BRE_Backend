package dev.bre.bundle;

import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The keys this installation will accept a signature from (LLD §8.6).
 *
 * <p>Three distinct key roles, and keeping them distinct is the whole design:
 *
 * <ul>
 *   <li><b>Own bundle key</b> — one per installation, signs bundles this installation approved.
 *   <li><b>Trusted exporters</b> — the public keys of <em>other</em> installations whose {@code
 *       ORIGIN} signatures may be imported, for example a separate on-premises UAT whose bundles
 *       are promoted to PROD.
 *   <li>The manifest signing key is one per cell and signs the Active Pointer Manifest, not
 *       bundles; it is deliberately not in this store, because a store that held both would let a
 *       manifest signature be confused for a bundle signature.
 * </ul>
 *
 * <p><b>A pod trusts only its own installation's bundle key.</b> An imported bundle is
 * countersigned {@code IMPORT} by the receiving registry <em>after</em> it has verified the {@code
 * ORIGIN} signature against this list — so by the time any pod sees it, the bundle carries a
 * signature from a key that pod already trusts. That indirection is what stops a compromised
 * upstream installation from pushing a policy straight into another installation's data plane.
 *
 * <p><b>Rotation is overlap-based.</b> A store holds the current and the previous {@code kid} at
 * once, because a rotation that invalidated every bundle signed an hour ago would take the whole
 * data plane down on a key change — the operation most likely to be performed under time pressure.
 */
public final class TrustStore {

  private final Map<String, PublicKey> ownBundleKeys;
  private final Map<String, PublicKey> trustedExporters;

  private TrustStore(
      Map<String, PublicKey> ownBundleKeys, Map<String, PublicKey> trustedExporters) {
    this.ownBundleKeys = Map.copyOf(ownBundleKeys);
    this.trustedExporters = Map.copyOf(trustedExporters);
  }

  /** Builds a trust store. */
  public static final class Builder {

    private final Map<String, PublicKey> own = new LinkedHashMap<>();
    private final Map<String, PublicKey> exporters = new LinkedHashMap<>();

    /**
     * Adds one of this installation's own bundle signing keys.
     *
     * <p>Add both the current and the previous {@code kid} during a rotation.
     *
     * @param kid the key identifier
     * @param key the public key
     * @return this builder
     */
    public Builder ownBundleKey(String kid, PublicKey key) {
      own.put(kid, key);
      return this;
    }

    /**
     * Adds another installation's key, whose {@code ORIGIN} signatures may be imported.
     *
     * @param kid the key identifier
     * @param key the public key
     * @return this builder
     */
    public Builder trustedExporter(String kid, PublicKey key) {
      exporters.put(kid, key);
      return this;
    }

    /**
     * Builds the store.
     *
     * @return the trust store
     */
    public TrustStore build() {
      return new TrustStore(own, exporters);
    }
  }

  /**
   * Starts building a trust store.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * The key for a {@code kid}, if this installation signs with it.
   *
   * @param kid the key identifier
   * @return the key, or empty
   */
  public Optional<PublicKey> ownBundleKey(String kid) {
    return Optional.ofNullable(ownBundleKeys.get(kid));
  }

  /**
   * The key for a {@code kid}, if it belongs to a trusted exporter.
   *
   * @param kid the key identifier
   * @return the key, or empty
   */
  public Optional<PublicKey> trustedExporter(String kid) {
    return Optional.ofNullable(trustedExporters.get(kid));
  }

  /**
   * Whether this store holds no keys at all.
   *
   * <p>An empty store trusts nothing and therefore loads nothing, which is the correct behaviour
   * for a misconfigured pod: serving a policy nobody vouched for is worse than serving none.
   *
   * @return true when empty
   */
  public boolean isEmpty() {
    return ownBundleKeys.isEmpty() && trustedExporters.isEmpty();
  }
}
