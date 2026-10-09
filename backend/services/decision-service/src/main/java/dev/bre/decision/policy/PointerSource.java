package dev.bre.decision.policy;

import dev.bre.bundle.PointerManifest;
import java.util.Optional;

/**
 * Where the Active Pointer Manifest comes from (LLD §8.2, §8.3).
 *
 * <p>A port because the manifest reaches a pod differently in different deployments: the registry's
 * long-poll in production (§8.2), a file on disk for local work, a fixture in a test. What the data
 * plane needs from all of them is identical — the current manifest, or nothing.
 *
 * <p><b>Returning empty is meaningful and must stay possible.</b> A pod that cannot reach the
 * registry keeps serving what it already had (§8.5) rather than falling back to some default, and a
 * pod that has never reached it serves nothing at all. Fail-closed applies to policy distribution
 * as much as to evaluation.
 */
public interface PointerSource {

  /**
   * The manifest currently in force, if this pod has one.
   *
   * @return the manifest, or empty when none has ever been loaded
   */
  Optional<PointerManifest> current();
}
