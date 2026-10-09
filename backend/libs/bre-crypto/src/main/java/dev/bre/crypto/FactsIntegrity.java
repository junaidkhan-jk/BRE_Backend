package dev.bre.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The facts integrity HMAC, and the key derivation behind it (LLD §9.2).
 *
 * <pre>
 *   facts_hmac = HMAC-SHA256(K_int[tenant], BRE-JCS(facts))
 *   K_int      = HKDF-SHA256(tenant secret, info = "bre/facts-hmac/1")
 * </pre>
 *
 * <p><b>What this is for, and why a signature would not do instead.</b> Replay verifies the HMAC
 * before evaluating, and a mismatch makes the explanation report {@code matched = false} with
 * reason {@code INTEGRITY}. The threat is not an outsider forging a fact snapshot — the snapshot is
 * already inside an authenticated envelope. It is that the stored bytes, years later, are not the
 * bytes the decision was taken on: a restore from the wrong backup, a migration that re-serialised
 * them, a storage-layer bug. Against that, the useful property is that the check is cheap and local
 * and the answer is unambiguous, which is what an HMAC gives.
 *
 * <p><b>Why HKDF rather than using the tenant secret directly.</b> One secret, several purposes.
 * Using the same bytes as both an HMAC key and anything else means a weakness in one use is a
 * weakness in the other, and there is no way to retire one purpose without retiring all of them.
 * The {@code info} string makes each purpose a distinct key from the same secret, so {@code
 * "bre/facts-hmac/1"} can become {@code "/2"} without touching the secret.
 *
 * <p><b>HKDF is implemented here rather than taken from the JDK's KDF API.</b> That API landed
 * recently enough that pinning to it would make this library refuse to build on anything earlier,
 * for twenty lines of RFC 5869 that have not changed since 2010 and are covered by the RFC's own
 * published test vectors — which this is tested against.
 */
public final class FactsIntegrity {

  /** The §9.2 derivation label. A new purpose gets a new label, never a new secret. */
  public static final String FACTS_HMAC_INFO = "bre/facts-hmac/1";

  private static final String HMAC = "HmacSHA256";
  private static final int SHA256_BYTES = 32;

  private FactsIntegrity() {}

  /**
   * Derives a purpose-specific key from a tenant secret (RFC 5869).
   *
   * @param secret the tenant's KEK-wrapped secret, already unwrapped
   * @param salt the salt; may be null or empty, which RFC 5869 treats as 32 zero bytes
   * @param info the purpose label, e.g. {@link #FACTS_HMAC_INFO}
   * @param length how many bytes to derive, at most 255 × 32
   * @return the derived key
   * @throws CryptoException if the platform has no HMAC-SHA256, or the length is out of range
   */
  public static byte[] hkdf(byte[] secret, byte[] salt, String info, int length) {
    return hkdf(secret, salt, info.getBytes(StandardCharsets.UTF_8), length);
  }

  /**
   * Derives a purpose-specific key, taking {@code info} as raw bytes (RFC 5869).
   *
   * <p>The byte form exists because RFC 5869's own test vectors give {@code info} as bytes that are
   * not valid UTF-8, and a derivation checked only against itself is a derivation that is
   * consistently wrong in a way nothing can detect — everything round-trips, and the keys are
   * simply weaker than believed. The vectors are the only thing that catches that, so the API has
   * to be able to express them.
   *
   * @param secret the input keying material
   * @param salt the salt; may be null or empty, which RFC 5869 treats as 32 zero bytes
   * @param info the purpose bytes
   * @param length how many bytes to derive, at most 255 × 32
   * @return the derived key
   * @throws CryptoException if the platform has no HMAC-SHA256, or the length is out of range
   */
  public static byte[] hkdf(byte[] secret, byte[] salt, byte[] info, int length) {
    if (length <= 0 || length > 255 * SHA256_BYTES) {
      throw new CryptoException(
          CryptoException.Reason.UNAVAILABLE,
          "HKDF-SHA256 derives 1 to " + 255 * SHA256_BYTES + " bytes; asked for " + length);
    }
    try {
      // Extract. An absent salt is all zeros, per RFC 5869 §2.2 — not "skip this step".
      Mac extract = Mac.getInstance(HMAC);
      byte[] saltOrZeros = salt == null || salt.length == 0 ? new byte[SHA256_BYTES] : salt;
      extract.init(new SecretKeySpec(saltOrZeros, HMAC));
      byte[] pseudoRandomKey = extract.doFinal(secret);

      // Expand.
      Mac expand = Mac.getInstance(HMAC);
      expand.init(new SecretKeySpec(pseudoRandomKey, HMAC));
      byte[] out = new byte[length];
      byte[] block = new byte[0];
      int written = 0;
      for (int counter = 1; written < length; counter++) {
        expand.update(block);
        expand.update(info);
        expand.update((byte) counter);
        block = expand.doFinal();
        int take = Math.min(block.length, length - written);
        System.arraycopy(block, 0, out, written, take);
        written += take;
      }
      Arrays.fill(pseudoRandomKey, (byte) 0);
      return out;
    } catch (GeneralSecurityException e) {
      throw new CryptoException(
          CryptoException.Reason.UNAVAILABLE, "HKDF-SHA256 is unavailable on this platform", e);
    }
  }

  /**
   * Derives a tenant's facts-HMAC key.
   *
   * @param tenantSecret the tenant's unwrapped secret
   * @param tenantId the tenant, used as the salt so two tenants with the same secret — which should
   *     not happen, and would be invisible if it did — still get different keys
   * @return a 32-byte key
   */
  public static byte[] factsHmacKey(byte[] tenantSecret, String tenantId) {
    return hkdf(
        tenantSecret, tenantId.getBytes(StandardCharsets.UTF_8), FACTS_HMAC_INFO, SHA256_BYTES);
  }

  /**
   * Computes the facts HMAC over already-canonicalised bytes.
   *
   * <p>Takes bytes, not a facts document: BRE-JCS lives in {@code bre-ir-json} and §1.2 keeps this
   * library free of that dependency. More usefully, it means the caller is the one who had to think
   * about canonicalisation — and canonicalisation is where the subtlety is, since {@code 212500.00}
   * and {@code 212500} must hash alike (§6.3.3 rule 3).
   *
   * @param key the tenant's facts-HMAC key
   * @param canonicalFacts {@code BRE-JCS(facts)} as UTF-8 bytes
   * @return the HMAC, lowercase hex
   */
  public static String hmac(byte[] key, byte[] canonicalFacts) {
    try {
      Mac mac = Mac.getInstance(HMAC);
      mac.init(new SecretKeySpec(key, HMAC));
      return HexFormat.of().formatHex(mac.doFinal(canonicalFacts));
    } catch (GeneralSecurityException e) {
      throw new CryptoException(
          CryptoException.Reason.UNAVAILABLE, "HMAC-SHA256 is unavailable on this platform", e);
    }
  }

  /**
   * Whether a facts HMAC matches.
   *
   * @param key the tenant's facts-HMAC key
   * @param canonicalFacts {@code BRE-JCS(facts)} as UTF-8 bytes
   * @param expected the recorded HMAC, lowercase hex
   * @return true when they match
   */
  public static boolean matches(byte[] key, byte[] canonicalFacts, String expected) {
    // Constant-time, and not as a reflex: the comparison is against a value an attacker who can
    // submit facts may be able to influence, and a short-circuiting compare leaks how many leading
    // bytes were right. MessageDigest.isEqual is the JDK's constant-time comparison.
    return MessageDigest.isEqual(
        hmac(key, canonicalFacts).getBytes(StandardCharsets.UTF_8),
        expected == null ? new byte[0] : expected.getBytes(StandardCharsets.UTF_8));
  }
}
