package dev.bre.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A {@link KmsPort} that holds its key-encryption keys in this process's memory.
 *
 * <p><b>FOR LOCAL DEVELOPMENT AND TESTS. NOT FOR ANY ENVIRONMENT HOLDING REAL APPLICANTS.</b> The
 * point of a KMS is that the KEK is somewhere a compromised application cannot read it; here the
 * KEK is a field on this object. Everything built on top is exercised correctly — wrapping,
 * rotation, unwrapping an old version, the shape of every call — and none of the protection is
 * real.
 *
 * <p>It is deliberately <em>not</em> a pass-through. A fake that returned the key unchanged would
 * let a bug that forgets to wrap pass every test, and would hide the one error everything above
 * depends on catching: unwrapping under the wrong KEK must fail, not silently return the wrong
 * bytes. So this performs genuine AES-256-GCM key wrapping, with the KEK identifier as AAD.
 *
 * <p>Keys are generated on demand and lost on restart, which is correct for a devstack: a restarted
 * devstack cannot read the decisions the previous one wrote, and discovering that locally is better
 * than discovering the equivalent in production.
 */
public final class LocalDevKms implements KmsPort {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int TAG_BITS = 128;
  private static final int NONCE_BYTES = 12;

  private final Map<String, byte[]> keks = new ConcurrentHashMap<>();
  private final Map<String, Integer> versions = new ConcurrentHashMap<>();

  @Override
  public String currentKek(String tenantId) {
    int version = versions.computeIfAbsent(tenantId, t -> 1);
    String kid = "kek/" + tenantId + "/" + version;
    keks.computeIfAbsent(
        kid,
        k -> {
          byte[] kek = new byte[32];
          RANDOM.nextBytes(kek);
          return kek;
        });
    return kid;
  }

  /**
   * Rotates a tenant's KEK, keeping the old version unwrappable (§9.3).
   *
   * <p>Here so a test can prove the asymmetry the design depends on: after a rotation, new data
   * keys go under the new KEK and keys sealed last year still open under the old one. A rotation
   * that broke the second property would make a lender's historic decisions unreadable, and nothing
   * would notice until an audit asked for one.
   *
   * @param tenantId the tenant
   * @return the new KEK identifier
   */
  public String rotate(String tenantId) {
    versions.merge(tenantId, 1, Integer::sum);
    return currentKek(tenantId);
  }

  @Override
  public byte[] wrap(String kekKid, byte[] plaintextDataKey) {
    byte[] kek = kekOrThrow(kekKid);
    byte[] nonce = new byte[NONCE_BYTES];
    RANDOM.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(kek, "AES"),
          new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(kekKid.getBytes(StandardCharsets.UTF_8));
      byte[] sealed = cipher.doFinal(plaintextDataKey);
      byte[] out = new byte[NONCE_BYTES + sealed.length];
      System.arraycopy(nonce, 0, out, 0, NONCE_BYTES);
      System.arraycopy(sealed, 0, out, NONCE_BYTES, sealed.length);
      return out;
    } catch (GeneralSecurityException e) {
      throw new CryptoException(
          CryptoException.Reason.UNAVAILABLE, "could not wrap under " + kekKid, e);
    }
  }

  @Override
  public byte[] unwrap(String kekKid, byte[] wrappedDataKey) {
    byte[] kek = kekOrThrow(kekKid);
    if (wrappedDataKey.length <= NONCE_BYTES) {
      throw new CryptoException(
          CryptoException.Reason.MALFORMED_ENVELOPE,
          "a wrapped key under " + kekKid + " is truncated");
    }
    byte[] nonce = Arrays.copyOf(wrappedDataKey, NONCE_BYTES);
    byte[] sealed = Arrays.copyOfRange(wrappedDataKey, NONCE_BYTES, wrappedDataKey.length);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(kek, "AES"),
          new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(kekKid.getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(sealed);
    } catch (AEADBadTagException tag) {
      throw new CryptoException(
          CryptoException.Reason.NOT_AUTHENTIC,
          "a key presented as wrapped under " + kekKid + " does not verify under it",
          tag);
    } catch (GeneralSecurityException e) {
      throw new CryptoException(
          CryptoException.Reason.UNAVAILABLE, "could not unwrap under " + kekKid, e);
    }
  }

  /**
   * The KEKs this instance holds, so a local tool can keep them across a restart.
   *
   * <p>Named to be uncomfortable to call. Exporting key material is the operation a real KMS does
   * not offer at all, and if this method is ever reached from anything but a local tool or a test,
   * that is the bug.
   *
   * @return the KEKs by identifier
   */
  public Map<String, byte[]> persistable() {
    return Map.copyOf(keks);
  }

  /**
   * Loads previously exported KEKs.
   *
   * @param exported KEKs by identifier, as {@link #persistable} returned them
   */
  public void load(Map<String, byte[]> exported) {
    keks.putAll(exported);
    for (String kid : exported.keySet()) {
      // kek/<tenantId>/<version>. Parsed by index rather than by split: the only purpose is to
      // stop currentKek handing out a version number already in use, so a kid that does not parse
      // is left out of that calculation rather than failing the whole load.
      int firstSlash = kid.indexOf('/');
      int lastSlash = kid.lastIndexOf('/');
      if (firstSlash <= 0 || lastSlash <= firstSlash) {
        continue;
      }
      try {
        versions.merge(
            kid.substring(firstSlash + 1, lastSlash),
            Integer.parseInt(kid.substring(lastSlash + 1)),
            Math::max);
      } catch (NumberFormatException notAVersion) {
        // Not a kid this class wrote. Its key is still loaded; only the version bookkeeping skips.
      }
    }
  }

  private byte[] kekOrThrow(String kekKid) {
    byte[] kek = keks.get(kekKid);
    if (kek == null) {
      throw new CryptoException(
          CryptoException.Reason.UNKNOWN_KEY,
          kekKid + " is not held by this KMS. A restarted dev KMS has none of its previous keys.");
    }
    return kek;
  }
}
