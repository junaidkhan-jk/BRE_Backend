package dev.bre.registry.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password hashing and verification.
 *
 * <p>PBKDF2-HMAC-SHA256, 600,000 iterations, a 16-byte random salt per password, a 32-byte output.
 * Stored as {@code pbkdf2-sha256$<iterations>$<salt>$<hash>}.
 *
 * <p><b>Why PBKDF2 and not Argon2 or bcrypt.</b> Argon2id is the better algorithm and bcrypt is the
 * more common one, and neither is in the JDK. Taking either means a new third-party dependency on
 * the one code path that must not be compromised, in a build whose containment rule (D11) is
 * deliberately narrow — and a native-library dependency, in Argon2's case, on every platform this
 * has to run on. PBKDF2 at a high iteration count is weaker against purpose-built hardware and is
 * what NIST SP 800-63B and the OWASP storage guidance both still accept. The parameters travel with
 * the hash, so moving to Argon2 later is a new prefix and a re-hash on next login, not a forced
 * password reset for everybody.
 *
 * <p><b>600,000 iterations</b> is OWASP's current PBKDF2-HMAC-SHA256 figure. It costs roughly a
 * quarter of a second per verification on this hardware, which is the intended cost: a login is a
 * rare operation and a brute-force attempt is not, so the asymmetry is the defence. It is also why
 * the cost is a stored parameter rather than a constant — raising it must not invalidate every
 * existing password.
 *
 * <p><b>Verification is constant-time</b> and a wrong-format hash fails closed. Both matter more
 * than they look: a short-circuiting comparison on a digest leaks how many leading bytes matched,
 * and a hash this code cannot parse must not be treated as "no password set".
 */
public final class Passwords {

  /** The current cost. Raising it re-hashes on next login rather than resetting anybody. */
  public static final int ITERATIONS = 600_000;

  private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
  private static final String PREFIX = "pbkdf2-sha256";
  private static final int SALT_BYTES = 16;
  private static final int HASH_BITS = 256;
  private static final SecureRandom RANDOM = new SecureRandom();

  private Passwords() {}

  /** A password did not meet the minimum policy. */
  public static final class WeakPasswordException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    WeakPasswordException(String message) {
      super(message);
    }
  }

  /**
   * Hashes a password.
   *
   * @param password the plaintext; cleared from the char array before returning
   * @return the storable string, parameters included
   * @throws WeakPasswordException if it is shorter than 12 characters
   */
  public static String hash(char[] password) {
    requireStrongEnough(password);
    byte[] salt = new byte[SALT_BYTES];
    RANDOM.nextBytes(salt);
    byte[] hash = derive(password, salt, ITERATIONS);
    try {
      return PREFIX
          + "$"
          + ITERATIONS
          + "$"
          + Base64.getEncoder().encodeToString(salt)
          + "$"
          + Base64.getEncoder().encodeToString(hash);
    } finally {
      java.util.Arrays.fill(password, '\0');
    }
  }

  /**
   * Whether a password matches a stored hash.
   *
   * @param password the plaintext; cleared before returning
   * @param stored the stored hash string
   * @return true when it matches; false for any malformed stored value
   */
  public static boolean verify(char[] password, String stored) {
    try {
      if (stored == null) {
        return false;
      }
      String[] parts = stored.split("\\$", -1);
      if (parts.length != 4 || !PREFIX.equals(parts[0])) {
        // A stored value this code cannot parse is not a password that matches anything. Failing
        // closed rather than throwing keeps a corrupt row from being a 500 on the login endpoint.
        return false;
      }
      int iterations = Integer.parseInt(parts[1]);
      byte[] salt = Base64.getDecoder().decode(parts[2]);
      byte[] expected = Base64.getDecoder().decode(parts[3]);
      return MessageDigest.isEqual(derive(password, salt, iterations), expected);
    } catch (RuntimeException malformed) {
      return false;
    } finally {
      java.util.Arrays.fill(password, '\0');
    }
  }

  /**
   * Whether a stored hash was made with an older cost and should be re-hashed on next login.
   *
   * @param stored the stored hash string
   * @return true when the iteration count is below the current one
   */
  public static boolean needsRehash(String stored) {
    try {
      String[] parts = stored.split("\\$", -1);
      return parts.length == 4 && Integer.parseInt(parts[1]) < ITERATIONS;
    } catch (RuntimeException malformed) {
      return true;
    }
  }

  /**
   * Generates a random password, for seeding an administrator or resetting an account.
   *
   * <p>Base64url of 18 random bytes — 144 bits, far past anything a policy needs, and no
   * ambiguous-character substitution because this is pasted rather than transcribed.
   *
   * @return the plaintext
   */
  public static String generate() {
    byte[] bytes = new byte[18];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * The minimum policy: twelve characters.
   *
   * <p>Length only, with no composition rules. NIST SP 800-63B withdrew the uppercase-digit-symbol
   * requirements because they measurably push people toward predictable substitutions — {@code
   * Password1!} satisfies every classic rule and is in every wordlist — while length is what
   * actually costs an attacker. Twelve is the floor; the hashing cost above is what defends the
   * rest.
   *
   * @param password the candidate
   */
  private static void requireStrongEnough(char[] password) {
    if (password == null || password.length < 12) {
      throw new WeakPasswordException(
          "a password must be at least 12 characters. No composition rules — NIST SP 800-63B"
              + " withdrew them because they push people toward predictable substitutions; length"
              + " is what costs an attacker.");
    }
  }

  private static byte[] derive(char[] password, byte[] salt, int iterations) {
    try {
      return SecretKeyFactory.getInstance(ALGORITHM)
          .generateSecret(new PBEKeySpec(password, salt, iterations, HASH_BITS))
          .getEncoded();
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new IllegalStateException(ALGORITHM + " is unavailable on this platform", e);
    }
  }

  /**
   * The SHA-256 of a session token, which is all that is ever stored.
   *
   * @param token the bearer token
   * @return the digest
   */
  public static byte[] tokenDigest(String token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable on this platform", e);
    }
  }

  /**
   * A fresh session token: 256 bits of randomness, base64url.
   *
   * @return the token, which is returned to the client once and never stored
   */
  public static String newSessionToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
