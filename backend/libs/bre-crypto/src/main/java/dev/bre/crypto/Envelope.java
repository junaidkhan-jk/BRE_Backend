package dev.bre.crypto;

import com.github.luben.zstd.Zstd;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The sealed-bytes format every encrypted artifact in this system uses (LLD §9.2).
 *
 * <pre>
 *   0x01 ‖ kidLen(1) ‖ kid ‖ nonce(12) ‖ ciphertext‖tag
 * </pre>
 *
 * <p>AES-256-GCM, with the plaintext zstd-compressed first.
 *
 * <p><b>Why the kid is inside the envelope and in the clear.</b> Decryption has to begin by knowing
 * which key to ask for, and the alternative — keeping the kid in a database column beside the bytes
 * — means a row and its ciphertext can be separated. An archive object restored without its row, or
 * a backup read years later by a tool nobody has written yet, is then undecryptable although the
 * key still exists. The kid is not secret: it names a key, and naming a key you cannot use buys an
 * attacker nothing. It is also covered by the authentication tag through the AAD, so it cannot be
 * swapped to point at a different key without the open failing.
 *
 * <p><b>Why the AAD is mandatory and not merely available.</b> Without it, a correctly encrypted
 * envelope is valid wherever it is put: a {@code purpose=facts-ni} envelope would open as a {@code
 * purpose=results} envelope, and one decision's facts would open as another's. §9.2's AAD binds
 * every envelope to its tenant, its purpose, its decision and — for a principal envelope — its data
 * principal, so moving sealed bytes between any of those makes the tag fail. {@link #seal} has no
 * overload that omits it.
 *
 * <p><b>Why compress before encrypting.</b> Ciphertext is incompressible, so the order is forced if
 * compression is to happen at all; §5.4 sizes the hot store on it (2–8 KB per decision). The
 * standard objection — that compression before encryption leaks plaintext length, and with it
 * information in an adaptive attack — does not apply to this threat model: an attacker cannot
 * choose part of a fact snapshot and observe the resulting size, because the snapshot is the
 * lender's own submitted application and is sealed once.
 */
public final class Envelope {

  /** The format byte. A second format would be {@code 0x02} and would still start here. */
  public static final byte VERSION = 0x01;

  /** AES-GCM's standard nonce length. */
  public static final int NONCE_BYTES = 12;

  /** AES-GCM's tag length, in bits. */
  public static final int TAG_BITS = 128;

  /**
   * The largest plaintext this will decompress to.
   *
   * <p>64 MiB. A facts document is kilobytes and §5.4 sizes a results envelope at 2–8 KB, so this
   * is three orders of magnitude of headroom — it exists to stop a decompression bomb exhausting
   * the heap of whatever reads an archive, not to constrain anything real.
   */
  public static final int MAX_PLAINTEXT_BYTES = 64 * 1024 * 1024;

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int MAX_KID_BYTES = 255;

  private Envelope() {}

  /**
   * Seals bytes under a data key.
   *
   * @param kid the data key's identifier, recorded in the envelope (§9.3)
   * @param key the data key; AES-256, so 32 bytes
   * @param aad the §9.2 additional authenticated data, e.g. {@code
   *     bre/1;tenant=t_acme;purpose=facts-ni;decision=019...}
   * @param plaintext the bytes to seal
   * @return the envelope
   * @throws CryptoException if the kid is too long or the platform refuses
   */
  public static byte[] seal(String kid, byte[] key, String aad, byte[] plaintext) {
    byte[] kidBytes = kid.getBytes(StandardCharsets.UTF_8);
    if (kidBytes.length == 0 || kidBytes.length > MAX_KID_BYTES) {
      throw new CryptoException(
          CryptoException.Reason.MALFORMED_ENVELOPE,
          "a kid must be 1 to " + MAX_KID_BYTES + " bytes; this one is " + kidBytes.length);
    }
    byte[] nonce = new byte[NONCE_BYTES];
    RANDOM.nextBytes(nonce);

    byte[] compressed = Zstd.compress(plaintext);
    byte[] ciphertext;
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, aesKey(key), new GCMParameterSpec(TAG_BITS, nonce));
      // The kid is authenticated along with the caller's AAD, so an envelope cannot be made to
      // point at a different key without the tag failing.
      cipher.updateAAD(aadBytes(aad, kidBytes));
      ciphertext = cipher.doFinal(compressed);
    } catch (GeneralSecurityException e) {
      throw new CryptoException(
          CryptoException.Reason.UNAVAILABLE, "could not seal under " + kid, e);
    } finally {
      Arrays.fill(compressed, (byte) 0);
    }

    byte[] out = new byte[2 + kidBytes.length + NONCE_BYTES + ciphertext.length];
    int at = 0;
    out[at++] = VERSION;
    out[at++] = (byte) kidBytes.length;
    System.arraycopy(kidBytes, 0, out, at, kidBytes.length);
    at += kidBytes.length;
    System.arraycopy(nonce, 0, out, at, NONCE_BYTES);
    at += NONCE_BYTES;
    System.arraycopy(ciphertext, 0, out, at, ciphertext.length);
    return out;
  }

  /**
   * Reads the key identifier out of an envelope, without opening it.
   *
   * <p>This is the first call a reader makes: it has bytes and needs to know which key to fetch.
   *
   * @param envelope the sealed bytes
   * @return the kid
   * @throws CryptoException if the envelope is not a {@code 0x01} envelope or is truncated
   */
  public static String kidOf(byte[] envelope) {
    if (envelope == null || envelope.length < 2) {
      throw new CryptoException(CryptoException.Reason.MALFORMED_ENVELOPE, "fewer than two bytes");
    }
    if (envelope[0] != VERSION) {
      throw new CryptoException(
          CryptoException.Reason.MALFORMED_ENVELOPE,
          "format byte is 0x"
              + String.format("%02x", envelope[0])
              + ", expected 0x01. This build reads one envelope format.");
    }
    int kidLen = envelope[1] & 0xFF;
    if (envelope.length < 2 + kidLen + NONCE_BYTES) {
      throw new CryptoException(
          CryptoException.Reason.MALFORMED_ENVELOPE, "truncated before the nonce");
    }
    return new String(envelope, 2, kidLen, StandardCharsets.UTF_8);
  }

  /**
   * Opens an envelope.
   *
   * @param key the data key the envelope's kid names
   * @param aad the same AAD the envelope was sealed with
   * @param envelope the sealed bytes
   * @return the plaintext
   * @throws CryptoException {@code NOT_AUTHENTIC} when the key or the AAD is wrong, or the bytes
   *     were altered — these are indistinguishable by design, and all three mean "do not use this"
   */
  public static byte[] open(byte[] key, String aad, byte[] envelope) {
    String kid = kidOf(envelope);
    byte[] kidBytes = kid.getBytes(StandardCharsets.UTF_8);
    int offset = 2 + kidBytes.length;
    byte[] nonce = Arrays.copyOfRange(envelope, offset, offset + NONCE_BYTES);
    byte[] ciphertext = Arrays.copyOfRange(envelope, offset + NONCE_BYTES, envelope.length);

    byte[] compressed;
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, aesKey(key), new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aadBytes(aad, kidBytes));
      compressed = cipher.doFinal(ciphertext);
    } catch (javax.crypto.AEADBadTagException tag) {
      throw new CryptoException(
          CryptoException.Reason.NOT_AUTHENTIC,
          "the tag did not verify for "
              + kid
              + " with that AAD. Wrong key, wrong AAD, or altered bytes — which of the three it"
              + " is cannot be told apart, and must not be guessed at.",
          tag);
    } catch (GeneralSecurityException e) {
      throw new CryptoException(CryptoException.Reason.UNAVAILABLE, "could not open " + kid, e);
    }

    // Streamed rather than sized from the frame header, and bounded. The frame header's declared
    // size is attacker-controlled in the general case, and these bytes come back from storage: a
    // decompression bomb would be a heap exhaustion in whichever process reads an archive. §2.2
    // caps a facts document well below this; MAX_PLAINTEXT_BYTES is a ceiling, not a target.
    try (var in =
        new com.github.luben.zstd.ZstdInputStream(new java.io.ByteArrayInputStream(compressed))) {
      byte[] plaintext = in.readNBytes(MAX_PLAINTEXT_BYTES + 1);
      if (plaintext.length > MAX_PLAINTEXT_BYTES) {
        throw new CryptoException(
            CryptoException.Reason.MALFORMED_ENVELOPE,
            "the plaintext of " + kid + " exceeds " + MAX_PLAINTEXT_BYTES + " bytes");
      }
      return plaintext;
    } catch (java.io.IOException e) {
      throw new CryptoException(
          CryptoException.Reason.MALFORMED_ENVELOPE,
          "the plaintext of " + kid + " is not a zstd frame",
          e);
    } finally {
      Arrays.fill(compressed, (byte) 0);
    }
  }

  private static SecretKey aesKey(byte[] key) {
    if (key.length != 32) {
      throw new CryptoException(
          CryptoException.Reason.UNKNOWN_KEY,
          "a data key must be 32 bytes for AES-256; this one is " + key.length);
    }
    return new SecretKeySpec(key, "AES");
  }

  private static byte[] aadBytes(String aad, byte[] kidBytes) {
    if (aad == null || aad.isBlank()) {
      // Not a convenience that was omitted: see the class documentation. An envelope with no AAD
      // is an envelope that is valid in the wrong place.
      throw new CryptoException(
          CryptoException.Reason.MALFORMED_ENVELOPE,
          "§9.2 requires AAD on every envelope; there is no unbound form");
    }
    byte[] aadBytes = aad.getBytes(StandardCharsets.UTF_8);
    byte[] combined = new byte[aadBytes.length + 1 + kidBytes.length];
    System.arraycopy(aadBytes, 0, combined, 0, aadBytes.length);
    combined[aadBytes.length] = ';';
    System.arraycopy(kidBytes, 0, combined, aadBytes.length + 1, kidBytes.length);
    return combined;
  }

  /**
   * Generates a fresh AES-256 data key.
   *
   * @return 32 random bytes
   */
  public static byte[] newDataKey() {
    byte[] key = new byte[32];
    RANDOM.nextBytes(key);
    return key;
  }

  /**
   * The §9.2 AAD for an artifact.
   *
   * @param tenantId the tenant
   * @param purpose {@code facts}, {@code results}, {@code facts-ni} or {@code facts-id}
   * @param decisionId the decision
   * @return the AAD string
   */
  public static String aad(String tenantId, String purpose, String decisionId) {
    return "bre/1;tenant=" + tenantId + ";purpose=" + purpose + ";decision=" + decisionId;
  }

  /**
   * The §9.2 AAD for a principal envelope, which also binds the data principal.
   *
   * @param tenantId the tenant
   * @param principalRef the data principal
   * @param decisionId the decision
   * @return the AAD string
   */
  public static String principalAad(String tenantId, String principalRef, String decisionId) {
    return "bre/1;tenant="
        + tenantId
        + ";purpose=facts-id;principal="
        + principalRef
        + ";decision="
        + decisionId;
  }
}
