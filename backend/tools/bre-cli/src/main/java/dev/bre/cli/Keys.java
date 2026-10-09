package dev.bre.cli;

import dev.bre.bundle.BundleSigner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Key and flag handling shared by the signing commands.
 *
 * <p>Extracted when {@code manifest sign} became the second command that needed to read a private
 * key: two copies of "decode base64, build an Ed25519 key, say which file was wrong" is two places
 * for the error message to drift, and the error message is the whole value of this code — a
 * developer who has handed the tool a public key where a private one belongs needs to be told
 * exactly that.
 */
final class Keys {

  private Keys() {}

  /**
   * Returns the value following a flag, or null when the flag is absent.
   *
   * <p>Deliberately not a flag-parsing library. The CLI has four flags.
   */
  static String flag(String[] args, String name) {
    for (int i = 0; i < args.length - 1; i++) {
      if (args[i].equals(name)) {
        return args[i + 1];
      }
    }
    return null;
  }

  /** The {@code -o} target, or null to write to stdout. */
  static Path outputPath(String[] args) {
    String value = flag(args, "-o");
    return value == null ? null : Path.of(value);
  }

  static PrivateKey privateKey(Path file) throws IOException {
    try {
      return KeyFactory.getInstance(BundleSigner.ALGORITHM)
          .generatePrivate(
              new PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(file).trim())));
    } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
      throw new IOException(file + " is not a base64 PKCS#8 Ed25519 private key");
    }
  }

  static PublicKey publicKey(Path file) throws IOException {
    try {
      return KeyFactory.getInstance(BundleSigner.ALGORITHM)
          .generatePublic(
              new X509EncodedKeySpec(Base64.getDecoder().decode(Files.readString(file).trim())));
    } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
      throw new IOException(file + " is not a base64 X.509 Ed25519 public key");
    }
  }

  /**
   * Writes a fresh Ed25519 key pair, for local work only.
   *
   * @param privateFile where the private key goes
   * @param publicFile where the public key goes
   * @throws IOException if either cannot be written
   */
  static void writeKeyPair(Path privateFile, Path publicFile) throws IOException {
    var pair = BundleSigner.generateKeyPair();
    Files.writeString(
        privateFile, Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
    Files.writeString(
        publicFile, Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
  }
}
