package dev.bre.engine.decide;

import dev.bre.engine.eval.EvalErrorCode;
import dev.bre.engine.eval.EvalException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Champion/challenger bucket assignment (LLD §4.11, D8).
 *
 * <p>{@code hash(tenantId, applicationRef, experimentId) mod 10000} — <b>not</b> a random draw.
 * That is the whole point: §4.11's assignment has to be reproducible, because a decision replayed
 * next March must land in the same experiment arm it landed in today. A {@code Random} here would
 * make replay impossible and the champion/challenger comparison meaningless, which is why {@code
 * EngineArchRules.noClockOrRandomness} forbids one anywhere in the engine.
 *
 * <p>The input is {@code UTF8(tenantId) 0x00 UTF8(applicationRef) 0x00 UTF8(experimentId)}. The
 * zero bytes are separators, and they matter: without them {@code ("ab", "c")} and {@code ("a",
 * "bc")} would hash identically, so two different applications could share a bucket by accident.
 */
public final class TrafficSplitter {

  /** The number of buckets (§4.11). */
  public static final int BUCKETS = 10_000;

  private TrafficSplitter() {}

  /**
   * Returns the bucket an application falls in for one experiment.
   *
   * @param tenantId the tenant
   * @param applicationRef the application reference
   * @param experimentId the experiment
   * @return a bucket in {@code [0, 10000)}
   */
  public static int bucketOf(String tenantId, String applicationRef, String experimentId) {
    byte[] digest = sha256(tenantId, applicationRef, experimentId);

    // The first eight bytes, big-endian, read as UNSIGNED. A signed read would send every digest
    // whose top bit is set to a negative remainder, so roughly half the applications would fall
    // outside the bucket range entirely.
    long value = 0;
    for (int index = 0; index < 8; index++) {
      value = (value << 8) | (digest[index] & 0xFFL);
    }
    return (int) Long.remainderUnsigned(value, BUCKETS);
  }

  private static byte[] sha256(String tenantId, String applicationRef, String experimentId) {
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      sha256.update(tenantId.getBytes(StandardCharsets.UTF_8));
      sha256.update((byte) 0);
      sha256.update(applicationRef.getBytes(StandardCharsets.UTF_8));
      sha256.update((byte) 0);
      sha256.update(experimentId.getBytes(StandardCharsets.UTF_8));
      return sha256.digest();
    } catch (NoSuchAlgorithmException unavailable) {
      // SHA-256 is mandated by the JDK; its absence is an engine defect, not a policy error.
      throw new EvalException(EvalErrorCode.EVAL_INTERNAL, "SHA-256", "MessageDigest");
    }
  }
}
