package dev.bre.decision.policy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Bundles from a directory, one file per content address.
 *
 * <p>For local development and for the conformance of the resolution path itself — the registry's
 * HTTP fetch is M2-03's surface, and the data plane's contract is identical either way: ask by
 * hash, verify what comes back.
 *
 * <p>The file name is derived from the hash, which means a bundle cannot be filed under the wrong
 * name without {@link BundleCache} noticing: it compares the address it asked for with the address
 * the document claims, and then recomputes that from the content.
 */
public final class FileBundleStore implements BundleStore {

  private final Path directory;

  /**
   * Creates a store over a directory.
   *
   * @param directory where bundle files live
   */
  public FileBundleStore(Path directory) {
    this.directory = directory;
  }

  @Override
  public Optional<String> fetch(String bundleHash) {
    Path file = directory.resolve(fileNameOf(bundleHash));
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Files.readString(file));
    } catch (IOException e) {
      // Unreadable is indistinguishable from absent to the caller, and both fail closed. The
      // alternative — propagating an IO error into the decision path — would turn a disk problem
      // into a different failure mode from a missing bundle, for no benefit.
      return Optional.empty();
    }
  }

  /**
   * The file name a bundle is stored under.
   *
   * @param bundleHash the content address, {@code sha256:…}
   * @return a file name safe on every platform
   */
  public static String fileNameOf(String bundleHash) {
    // The colon in "sha256:…" is not a legal path character on Windows and is awkward in shells.
    return bundleHash.toLowerCase(Locale.ROOT).replace(':', '-') + ".json";
  }

  /**
   * Writes a bundle into the store.
   *
   * @param bundleHash the content address
   * @param document the bundle JSON
   * @throws IOException if it cannot be written
   */
  public void put(String bundleHash, String document) throws IOException {
    Files.createDirectories(directory);
    Files.writeString(directory.resolve(fileNameOf(bundleHash)), document);
  }
}
