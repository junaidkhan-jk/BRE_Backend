package dev.bre.decision.archive;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Where archived decisions are written, write-once (M4-03, §6.1.5).
 *
 * <p>A port, because the production target is object storage with a retention lock and the local
 * one is a directory — the same split {@code FileBundleStore} already makes. What the port
 * guarantees is the property that matters to a regulator rather than the storage technology: <b>a
 * file that exists is never replaced.</b>
 *
 * <p><b>Write-once is enforced here, not assumed from the bucket's configuration.</b> An object
 * lock is a setting somebody applies, and a setting can be absent on the day it matters — a
 * misconfigured bucket, a different region, a restored backup. A store that refuses to overwrite
 * fails loudly at the moment of the mistake, where the local adapter and the S3 one behave the same
 * way and a test can prove it.
 */
public interface ArchiveStore {

  /**
   * Writes a new object. Refuses if one already exists at that key.
   *
   * @param key the object key, {@code <tenant>/<date>/<hour>/<name>}
   * @param write writes the bytes
   * @throws AlreadyArchivedException when the key is taken — never an overwrite
   * @throws IOException on a storage failure
   */
  void putOnce(String key, ContentWriter write) throws IOException;

  /**
   * Lists the keys under a prefix, in order.
   *
   * @param prefix the prefix, e.g. {@code t_lender/2026-10-16}
   * @return the keys
   * @throws IOException on a storage failure
   */
  List<String> list(String prefix) throws IOException;

  /**
   * Opens an archived object for reading.
   *
   * @param key the object key
   * @return a local path the Parquet reader can open
   * @throws IOException on a storage failure
   */
  Path open(String key) throws IOException;

  /** Writes an object's bytes. */
  @FunctionalInterface
  interface ContentWriter {
    /**
     * Writes the content.
     *
     * @param out where to write
     * @throws IOException on failure
     */
    void writeTo(OutputStream out) throws IOException;
  }

  /** An attempt to replace an archived object. Never allowed. */
  class AlreadyArchivedException extends IOException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param key the key that already exists
     */
    public AlreadyArchivedException(String key) {
      super(
          "the archive already holds "
              + key
              + ", and an archived decision is never rewritten. A duplicate key means the roll"
              + " logic produced the same name twice, which would silently lose whichever file"
              + " lost the race.");
    }
  }

  /**
   * The local adapter: a directory.
   *
   * <p>Not a substitute for a retention lock and does not pretend to be — a process with write
   * access to this directory can delete a file, which is exactly what WORM storage prevents. What
   * it does give is the same REFUSAL TO OVERWRITE the real store gives, so the code above it is
   * identical in both and the behaviour is testable without a bucket.
   */
  final class Directory implements ArchiveStore {

    private final Path root;

    /**
     * Creates the adapter.
     *
     * @param root the directory decisions are archived under
     */
    public Directory(Path root) {
      this.root = root;
    }

    @Override
    public void putOnce(String key, ContentWriter write) throws IOException {
      Path target = resolve(key);
      Files.createDirectories(target.getParent());

      // CREATE_NEW, which fails if the file exists. Not exists()-then-write, which is a race: two
      // pods rolling the same partition in the same second would both see no file and both write,
      // and one of the two archives would be lost with nothing to show it ever existed.
      try (OutputStream out =
          Files.newOutputStream(
              target,
              java.nio.file.StandardOpenOption.CREATE_NEW,
              java.nio.file.StandardOpenOption.WRITE)) {
        write.writeTo(out);
      } catch (java.nio.file.FileAlreadyExistsException taken) {
        throw new AlreadyArchivedException(key);
      }
    }

    @Override
    public List<String> list(String prefix) throws IOException {
      Path start = resolve(prefix);
      if (!Files.exists(start)) {
        return List.of();
      }
      try (java.util.stream.Stream<Path> walk = Files.walk(start)) {
        return walk.filter(Files::isRegularFile)
            .map(path -> root.relativize(path).toString())
            .sorted()
            .toList();
      }
    }

    @Override
    public Path open(String key) throws IOException {
      Path target = resolve(key);
      if (!Files.isRegularFile(target)) {
        throw new java.nio.file.NoSuchFileException(key);
      }
      return target;
    }

    /** Resolves a key inside the root, refusing anything that escapes it. */
    private Path resolve(String key) {
      Path target = root.resolve(key).normalize();
      if (!target.startsWith(root.normalize())) {
        // A key is built from a tenant id, and a tenant id that reached here unchecked could walk
        // out of the archive and read or write another tenant's partition (D1).
        throw new IllegalArgumentException("an archive key must stay inside the archive root");
      }
      return target;
    }
  }
}
