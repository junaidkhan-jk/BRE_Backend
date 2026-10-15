package dev.bre.decision.policy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Adopts a new Active Pointer Manifest while the pod is running (§8.2, §8.5).
 *
 * <p><b>Why this exists.</b> {@link FilePointerSource#refresh()} was called exactly once, at
 * startup. Everything downstream of it worked — the registry published a signed manifest, the
 * bundle was on disk, the signature verified — and a policy deployed through the registry still did
 * not reach a running pod, because nothing ever looked at the file again. The only way to serve a
 * newly approved policy was to restart the service, which is not a deployment mechanism; it is a
 * restart somebody has to remember.
 *
 * <p><b>This is the local stand-in for the long-poll, not a replacement for it.</b> §8.2 specifies
 * the registry holding a request open and answering the moment the manifest version changes, which
 * gives a propagation time measured in milliseconds and no polling load. Polling a file gives a
 * propagation time of up to one interval and is correct for a single machine with a shared
 * directory. The two are interchangeable precisely because {@link PointerSource} is the seam:
 * swapping in an HTTP source changes this class and nothing else.
 *
 * <p><b>Adoption is still the source's decision, not this class's.</b> {@code refresh()} verifies
 * the signature and refuses a version that goes backwards; a watcher that read the file itself
 * would be a second place those two rules have to hold, and the second place is where they get
 * forgotten. All this does is ask, on a timer, and say what changed.
 */
@Component
public class ManifestWatcher {

  private static final Logger log = LoggerFactory.getLogger(ManifestWatcher.class);

  private final FilePointerSource pointers;

  /**
   * Creates the watcher.
   *
   * @param pointers the manifest source, which owns verification and the monotonic-version rule
   */
  public ManifestWatcher(FilePointerSource pointers) {
    this.pointers = pointers;
  }

  /**
   * Re-reads the manifest and logs an adoption.
   *
   * <p>Two seconds by default. A policy change is a governed, deliberate act — nobody deploys fifty
   * an hour — so the interval is chosen for how long a person is willing to wait after pressing
   * Deploy, not for load.
   */
  @Scheduled(fixedDelayString = "${bre.policy.manifest-poll-ms:2000}")
  public void poll() {
    try {
      if (pointers.refresh()) {
        pointers
            .current()
            .ifPresent(
                manifest ->
                    // At INFO and on one line: "which policy was this pod serving at 14:30" is a
                    // question that gets asked, and the answer should be in the log rather than
                    // reconstructed from the registry's audit trail.
                    log.info(
                        "adopted manifest version {} for cell {} with {} active pointer(s)",
                        manifest.manifestVersion(),
                        manifest.cellId(),
                        manifest.entries().size()));
      }
    } catch (RuntimeException unreadable) {
      // A pod that cannot read the manifest keeps the one it has (§8.5) and must not stop serving.
      // Logged at WARN with the exception, because the message alone — "bad signature", say —
      // does not say which file or which key.
      log.warn(
          "could not refresh the Active Pointer Manifest; keeping the one in force", unreadable);
    }
  }
}
