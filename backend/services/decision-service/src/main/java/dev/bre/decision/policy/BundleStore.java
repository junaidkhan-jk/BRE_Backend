package dev.bre.decision.policy;

import java.util.Optional;

/**
 * Where bundle bytes come from (LLD §8.3).
 *
 * <p>A port, so the hot path does not care whether a bundle arrives from the registry over HTTP,
 * from an object store, or from a directory on disk. The data plane's rule is the same either way:
 * fetch by content address, verify, then use. A store that returned the wrong bytes for a hash
 * would be caught by {@link BundleCache}'s verification rather than trusted.
 */
public interface BundleStore {

  /**
   * Fetches a bundle by its content address.
   *
   * @param bundleHash the address
   * @return the bundle document, or empty when this store does not have it
   */
  Optional<String> fetch(String bundleHash);
}
