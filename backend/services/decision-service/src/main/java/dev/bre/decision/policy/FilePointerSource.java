package dev.bre.decision.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bre.bundle.PointerManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Active Pointer Manifest from a file, re-read when it changes (LLD §8.2, §8.5).
 *
 * <p>The local stand-in for the registry's long-poll. It keeps the same two properties the real
 * watcher must have, because both are correctness rather than convenience:
 *
 * <ul>
 *   <li><b>The signature is verified before the manifest is adopted.</b> The manifest says which
 *       policy every pod in the cell will serve, so an unsigned or badly signed one is at least as
 *       dangerous as a tampered bundle.
 *   <li><b>A manifest version never goes backwards.</b> Versions are monotonic (§8.1), so an older
 *       document is either a stale replay or a rollback someone is attempting out of band. Either
 *       way the pod keeps what it has — accepting it would silently re-point live traffic at a
 *       retired policy.
 * </ul>
 *
 * <p>If the file is missing or unreadable the pod keeps the manifest it already had (§8.5). It does
 * not fall back to anything.
 */
public final class FilePointerSource implements PointerSource {

  private static final Logger log = LoggerFactory.getLogger(FilePointerSource.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Path file;
  private final PublicKey manifestKey;
  private final AtomicReference<PointerManifest> current = new AtomicReference<>();

  /**
   * Creates a source over a file.
   *
   * @param file the manifest document
   * @param manifestKey the cell's manifest public key (§8.6)
   */
  public FilePointerSource(Path file, PublicKey manifestKey) {
    this.file = file;
    this.manifestKey = manifestKey;
  }

  @Override
  public Optional<PointerManifest> current() {
    return Optional.ofNullable(current.get());
  }

  /**
   * Re-reads the file and adopts it if it verifies and is newer.
   *
   * @return true when the manifest in force changed
   */
  public boolean refresh() {
    JsonNode document;
    try {
      document = MAPPER.readTree(Files.readString(file));
    } catch (IOException unreadable) {
      return false; // §8.5: keep serving what we have.
    }

    if (!PointerManifest.verify(document, manifestKey)) {
      return false;
    }

    PointerManifest candidate;
    try {
      candidate = PointerManifest.of(document);
    } catch (RuntimeException malformed) {
      return false;
    }

    PointerManifest inForce = current.get();
    if (inForce != null && candidate.manifestVersion() <= inForce.manifestVersion()) {
      // LOUDLY. Refusing is correct — §8.1 versions are monotonic and an older manifest is either
      // a rollback replay or a tampered file — but refusing SILENTLY leaves a pod serving a policy
      // nobody deployed while the registry reports the new one ACTIVE, and nothing anywhere says
      // so. That exact state happened in the devstack: the registry's database is recreated on
      // every run so manifest versions restart at 1, a pod holding version 1 from the previous run
      // refused an unrelated version 1, and it answered four applications from the PREVIOUS run's
      // policy. One of them approved an application the current policy declines.
      //
      // At WARN, and only when the refusal would CHANGE what is served. The watcher polls every
      // two seconds, so the overwhelmingly common case is the same manifest re-read — warning on
      // that would bury the real one at 1,800 lines an hour. Comparing the CHAMPIONS rather than
      // the documents is also the more useful comparison: a manifest that differs only in its
      // issuedAt serves exactly the same policy, and the question an operator has is "am I serving
      // something different from what was deployed".
      if (!champions(candidate).equals(champions(inForce))) {
        log.warn(
            "REFUSED an Active Pointer Manifest: version {} is not newer than the version {} in"
                + " force, and it names DIFFERENT champion bundles ({} vs {}). This pod keeps"
                + " serving the older one. §8.1 versions are monotonic, so either a manifest was"
                + " replayed or the registry's state was reset and is re-issuing versions it has"
                + " already used.",
            candidate.manifestVersion(),
            inForce.manifestVersion(),
            champions(candidate),
            champions(inForce));
      }
      return false;
    }
    current.set(candidate);
    return true;
  }

  /** What a manifest would have this pod serve: one champion bundle per tenant/env/policy. */
  private static java.util.Map<String, String> champions(PointerManifest manifest) {
    java.util.Map<String, String> byKey = new java.util.TreeMap<>();
    for (PointerManifest.Entry entry : manifest.entries()) {
      byKey.put(
          entry.tenantId() + "/" + entry.environment() + "/" + entry.policyCode(),
          entry.champion() == null ? "-" : entry.champion().bundleHash());
    }
    return byKey;
  }
}
