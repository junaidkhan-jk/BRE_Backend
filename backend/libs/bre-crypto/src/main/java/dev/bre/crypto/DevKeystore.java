package dev.bre.crypto;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;

/**
 * A file on disk holding a {@link LocalDevKms}'s key-encryption keys, so they survive a restart.
 *
 * <p><b>For local development and drills. Not a key-management service.</b> A real KMS exists so
 * that the KEK is somewhere a compromised application cannot read it; this is a file the
 * application reads, and its password is wherever the operator put it — usually the same script.
 * None of the protection is real.
 *
 * <p><b>Why it exists at all.</b> A disaster-recovery drill has to answer one question: after the
 * database is restored, can the sealed fact snapshots still be read? With KEKs in process memory
 * that question cannot even be asked — every restart loses them, so a restore is always unreadable
 * and the drill proves nothing about the backup. The keys have to outlive the process for the test
 * to be about the backup rather than about the fake KMS. See {@code devstack/dr-drill.sh}.
 *
 * <p>And it makes the drill's real lesson concrete: the key material is a <em>separate</em>
 * artefact from the database dump, with a separate restore step and a separate secret. A backup
 * that captures one and not the other restores a database full of ciphertext nobody can open.
 *
 * <p>JCEKS rather than a file of base64, because the JDK already implements it and {@code keytool
 * -list} can inspect it without this code. Aliases are the key identifier in <b>hex</b>, and the
 * encoding is load-bearing rather than decorative: a JKS-family keystore <em>lowercases every
 * alias</em>. Storing the identifier directly would make {@code kek/Acme/1} and {@code kek/acme/1}
 * one entry, so one tenant's KEK would silently overwrite another's — and base64, the obvious first
 * choice, is itself case-sensitive, so the alias comes back case-folded and decodes to rubbish. Hex
 * is unaffected by case-folding. This was a real failure, caught by the test that stores two
 * tenants differing only in case.
 */
public final class DevKeystore {

  private static final String TYPE = "JCEKS";

  // Lowercase, because the keystore lowercases aliases on the way back out regardless.
  private static final HexFormat HEX = HexFormat.of();

  private DevKeystore() {}

  /**
   * Reads the KEKs a previous process wrote.
   *
   * @param file the keystore file; a file that does not exist yields an empty map, because the
   *     first run of a drill has nothing to load and that is not an error
   * @param password the keystore password
   * @return the KEKs by identifier, in the form {@link LocalDevKms#load} takes
   * @throws CryptoException if the file exists but cannot be read or does not verify
   */
  public static Map<String, byte[]> read(Path file, char[] password) {
    if (!Files.exists(file)) {
      return Map.of();
    }
    Map<String, byte[]> keks = new HashMap<>();
    try (InputStream in = Files.newInputStream(file)) {
      KeyStore store = KeyStore.getInstance(TYPE);
      store.load(in, password);
      KeyStore.PasswordProtection protection = new KeyStore.PasswordProtection(password);
      for (String alias : java.util.Collections.list(store.aliases())) {
        KeyStore.Entry entry = store.getEntry(alias, protection);
        if (entry instanceof KeyStore.SecretKeyEntry secret) {
          keks.put(decodeAlias(alias), secret.getSecretKey().getEncoded());
        }
      }
    } catch (IOException | GeneralSecurityException e) {
      // Deliberately not "start with no keys". An unreadable keystore during a restore means the
      // password is wrong or the file is damaged, and continuing would seal the next decisions
      // under fresh keys while leaving every restored row unopenable — the failure would look like
      // a successful recovery until somebody asked for an old decision.
      throw new CryptoException(
          CryptoException.Reason.UNAVAILABLE, "could not read the dev keystore at " + file, e);
    }
    return keks;
  }

  /**
   * Writes the KEKs, replacing whatever the file held.
   *
   * <p>Written to a temporary file and moved into place, so an interrupted write cannot leave a
   * truncated keystore where the only copy of a KEK used to be.
   *
   * @param file the keystore file
   * @param password the keystore password
   * @param keks the KEKs by identifier, as {@link LocalDevKms#persistable} returned them
   * @throws CryptoException if the file cannot be written
   */
  public static void write(Path file, char[] password, Map<String, byte[]> keks) {
    Path temporary = file.resolveSibling(file.getFileName() + ".new");
    try {
      KeyStore store = KeyStore.getInstance(TYPE);
      store.load(null, password);
      KeyStore.PasswordProtection protection = new KeyStore.PasswordProtection(password);
      for (Map.Entry<String, byte[]> kek : keks.entrySet()) {
        store.setEntry(
            encodeAlias(kek.getKey()),
            new KeyStore.SecretKeyEntry(new SecretKeySpec(kek.getValue(), "AES")),
            protection);
      }
      if (file.getParent() != null) {
        Files.createDirectories(file.getParent());
      }
      try (OutputStream out = Files.newOutputStream(temporary)) {
        store.store(out, password);
      }
      Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      restrictToOwner(file);
    } catch (IOException | GeneralSecurityException e) {
      throw new CryptoException(
          CryptoException.Reason.UNAVAILABLE, "could not write the dev keystore at " + file, e);
    }
  }

  private static void restrictToOwner(Path file) throws IOException {
    try {
      Files.setPosixFilePermissions(
          file,
          java.util.Set.of(
              java.nio.file.attribute.PosixFilePermission.OWNER_READ,
              java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException notPosix) {
      // Windows. The password is the only protection there, which is the same amount of protection
      // this class claims to offer anywhere.
    }
  }

  private static String encodeAlias(String kid) {
    return HEX.formatHex(kid.getBytes(StandardCharsets.UTF_8));
  }

  private static String decodeAlias(String alias) {
    return new String(HEX.parseHex(alias), StandardCharsets.UTF_8);
  }
}
