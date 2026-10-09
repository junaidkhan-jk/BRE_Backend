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
      return false;
    }
    current.set(candidate);
    return true;
  }
}
