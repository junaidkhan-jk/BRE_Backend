package dev.bre.decision.policy;

import dev.bre.bundle.BundleVerifier;
import dev.bre.bundle.TrustStore;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires policy resolution from configuration (LLD §8.3, §8.6).
 *
 * <p>Only <b>public</b> keys appear here, and that is not an accident of this being the data plane:
 * a pod verifies and never signs. The bundle signing key and the manifest signing key live in the
 * registry's KMS (§8.6), so a compromised decision pod cannot mint a policy — it can only refuse
 * ones it does not trust.
 *
 * <p>A pod with no trusted keys configured serves nothing. That is the correct failure for a
 * misconfiguration: a decision taken against a policy nobody vouched for is worse than a decision
 * not taken, because the caller acts on it.
 */
@Configuration
public class PolicyConfiguration {

  private static final Logger log = LoggerFactory.getLogger(PolicyConfiguration.class);

  /**
   * Policy distribution settings.
   *
   * @param bundles the directory bundles are cached in
   * @param manifest the Active Pointer Manifest file
   * @param manifestKey the cell's manifest public key, base64 X.509
   * @param trustedKeys this installation's bundle public keys, by {@code kid}; hold the current and
   *     previous during a rotation (§8.6)
   * @param grandfatherWindowDays how long a retired champion stays pinnable (§8.1)
   */
  @ConfigurationProperties("bre.policy")
  public record PolicyProperties(
      Path bundles,
      Path manifest,
      String manifestKey,
      Map<String, String> trustedKeys,
      int grandfatherWindowDays) {}

  /**
   * The verified policy cache.
   *
   * @param properties the configuration
   * @return the cache
   */
  @Bean
  public BundleCache bundleCache(PolicyProperties properties) {
    TrustStore.Builder trust = TrustStore.builder();
    properties
        .trustedKeys()
        .forEach((kid, encoded) -> trust.ownBundleKey(kid, publicKey(kid, encoded)));
    TrustStore store = trust.build();
    if (store.isEmpty()) {
      log.warn(
          "no trusted bundle keys are configured (bre.policy.trusted-keys): this pod will refuse"
              + " every policy. Set them before serving traffic.");
    }
    return new BundleCache(new FileBundleStore(properties.bundles()), new BundleVerifier(store));
  }

  /**
   * The Active Pointer Manifest source.
   *
   * @param properties the configuration
   * @return the source, already refreshed once
   */
  @Bean
  public FilePointerSource pointerSource(PolicyProperties properties) {
    FilePointerSource source =
        new FilePointerSource(
            properties.manifest(), publicKey("manifest", properties.manifestKey()));
    if (!source.refresh()) {
      log.warn(
          "no Active Pointer Manifest adopted from {}: this pod will refuse every decision until"
              + " one arrives (§8.5)",
          properties.manifest());
    }
    return source;
  }

  /**
   * The resolver the decision path uses.
   *
   * @param pointers the manifest source
   * @param bundles the verified cache
   * @param properties the configuration
   * @return the resolver
   */
  @Bean
  public PolicyResolver policyResolver(
      FilePointerSource pointers, BundleCache bundles, PolicyProperties properties) {
    return new PolicyResolver(pointers, bundles, properties.grandfatherWindowDays());
  }

  private static PublicKey publicKey(String name, String base64) {
    if (base64 == null || base64.isBlank()) {
      // A placeholder key would be worse than none: it would look configured and verify nothing.
      throw new IllegalStateException(
          "no public key configured for '"
              + name
              + "'. A decision pod verifies signatures and"
              + " cannot start without the keys it is meant to trust.");
    }
    try {
      return KeyFactory.getInstance("Ed25519")
          .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64.trim())));
    } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException e) {
      throw new IllegalStateException(
          "the public key configured for '" + name + "' is not a base64 X.509 Ed25519 key", e);
    }
  }
}
