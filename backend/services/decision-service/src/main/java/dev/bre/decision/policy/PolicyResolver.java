package dev.bre.decision.policy;

import dev.bre.bundle.PointerManifest;
import dev.bre.engine.decide.TrafficSplitter;
import java.time.Instant;
import java.util.Optional;

/**
 * Chooses which bundle a request is decided against (LLD §8.1, §8.3, §4.11).
 *
 * <p>The order is pin, then challenger, then champion, and it is not arbitrary:
 *
 * <ol>
 *   <li><b>A pin wins</b> when it is allowed and servable. A caller pins because they are
 *       reproducing a decision or running a controlled comparison, and silently giving them today's
 *       champion instead would make both meaningless.
 *   <li><b>A pinned request ignores challengers</b> (§4.11). Pinning asks for one specific policy;
 *       routing it into an experiment would be the opposite of what was asked.
 *   <li><b>Otherwise the bucket decides</b>, from a hash of {@code (tenantId, applicationRef,
 *       experimentId)} — so the same application lands in the same bucket on every retry and on
 *       every pod. A random split would give one applicant two different policies on a retry.
 * </ol>
 *
 * <p>Every failure refuses the request. There is no fallback policy, and there must not be: a
 * decision taken against a policy nobody selected is worse than no decision, because the caller
 * acts on it.
 */
public final class PolicyResolver {

  private final PointerSource pointers;
  private final BundleCache bundles;
  private final int grandfatherWindowDays;

  /**
   * Creates a resolver.
   *
   * @param pointers where the manifest comes from
   * @param bundles the verified policy cache
   * @param grandfatherWindowDays the tenant's pinning window (§8.1)
   */
  public PolicyResolver(PointerSource pointers, BundleCache bundles, int grandfatherWindowDays) {
    this.pointers = pointers;
    this.bundles = bundles;
    this.grandfatherWindowDays = grandfatherWindowDays;
  }

  /**
   * Resolves the policy for one request.
   *
   * @param tenantId the tenant
   * @param environment the environment code
   * @param policyCode the policy asked for
   * @param applicationRef the application, which fixes the challenger bucket
   * @param pinnedBundleHash the bundle the caller pinned, if any
   * @param receivedAt when the request arrived, by the server's clock
   * @return which policy to decide against
   * @throws PolicyResolutionException when nothing may be served
   */
  public PolicyResolution resolve(
      String tenantId,
      String environment,
      String policyCode,
      String applicationRef,
      Optional<String> pinnedBundleHash,
      Instant receivedAt) {

    PointerManifest manifest =
        pointers
            .current()
            .orElseThrow(
                () ->
                    new PolicyResolutionException(
                        PolicyResolutionException.Reason.NO_MANIFEST,
                        "this pod has no Active Pointer Manifest"));

    PointerManifest.Entry entry =
        manifest
            .entry(tenantId, environment, policyCode)
            .orElseThrow(
                () ->
                    new PolicyResolutionException(
                        PolicyResolutionException.Reason.NOT_SERVED,
                        tenantId
                            + "/"
                            + environment
                            + "/"
                            + policyCode
                            + " is not served by this cell"));

    if (pinnedBundleHash.isPresent()) {
      String pinned = pinnedBundleHash.get();
      // receivedAt, never the caller's asOf — a caller who could pin against a field they supply
      // would back-date their way into a window the lender has closed (§8.1).
      if (!PointerManifest.mayServePinned(entry, pinned, receivedAt, grandfatherWindowDays)) {
        throw new PolicyResolutionException(
            PolicyResolutionException.Reason.PIN_REFUSED,
            pinned + " is not servable for " + tenantId + "/" + environment + "/" + policyCode);
      }
      String deploymentId =
          entry.servable().stream()
              .filter(s -> s.bundleHash().equals(pinned))
              .findFirst()
              .map(PointerManifest.Servable::deploymentId)
              .orElse(entry.champion().deploymentId());
      BundleCache.Compiled served = bundles.compiled(pinned);
      return new PolicyResolution(
          served.policy(),
          served.parameters(),
          served.lists(),
          pinned,
          deploymentId,
          PolicyResolution.Kind.PINNED,
          Optional.empty());
    }

    for (PointerManifest.Challenger challenger : entry.challengers()) {
      int bucket = TrafficSplitter.bucketOf(tenantId, applicationRef, challenger.experimentId());
      if (bucket >= challenger.bucketFrom() && bucket < challenger.bucketTo()) {
        BundleCache.Compiled served = bundles.compiled(challenger.bundleHash());
        return new PolicyResolution(
            served.policy(),
            served.parameters(),
            served.lists(),
            challenger.bundleHash(),
            challenger.deploymentId(),
            PolicyResolution.Kind.CHALLENGER,
            Optional.of(challenger.experimentId()));
      }
    }

    BundleCache.Compiled champion = bundles.compiled(entry.champion().bundleHash());
    return new PolicyResolution(
        champion.policy(),
        champion.parameters(),
        champion.lists(),
        entry.champion().bundleHash(),
        entry.champion().deploymentId(),
        PolicyResolution.Kind.CHAMPION,
        Optional.empty());
  }
}
