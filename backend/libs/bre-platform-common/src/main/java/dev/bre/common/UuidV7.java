package dev.bre.common;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * UUIDv7 generation (ADR-008, RFC 9562 §5.7).
 *
 * <p>A v7 identifier carries its creation time in the leading 48 bits, which is what makes it the
 * right key for the decision hot store: {@code decision_date} is derived from that timestamp, so a
 * lookup by id prunes straight to one day partition and one hash sub-partition (LLD §5.3). A {@code
 * UUID.randomUUID()} v4 would carry no date, and every lookup would scan every partition.
 *
 * <p>Sorting by id also sorts roughly by time, so the index on a monotonically-growing table stays
 * dense instead of scattering inserts across the whole B-tree the way v4 does.
 *
 * <p>This is the one place in the product where randomness is correct, and it is deliberately
 * <b>outside</b> {@code bre-engine-core}: the engine must never read a clock or draw a random
 * number (D8), and {@code EngineArchRules.noClockOrRandomness} fails the build if it does. An
 * identifier is minted by the service layer, before evaluation, and is an input to the decision —
 * never part of computing it.
 */
public final class UuidV7 {

  private static final SecureRandom RANDOM = new SecureRandom();

  private UuidV7() {}

  /**
   * Mints a UUIDv7 from the current time.
   *
   * @return the identifier
   */
  public static UUID generate() {
    return generate(Instant.now());
  }

  /**
   * Mints a UUIDv7 for a given instant, so a caller that already resolved "now" uses that instant
   * rather than reading the clock a second time.
   *
   * @param instant the creation time, whose millisecond is embedded
   * @return the identifier
   */
  public static UUID generate(Instant instant) {
    byte[] random = new byte[10];
    RANDOM.nextBytes(random);

    long millis = instant.toEpochMilli();

    // 48 bits of big-endian milliseconds, then version 7, then 12 bits of random.
    long high = (millis & 0xFFFFFFFFFFFFL) << 16;
    high |= 0x7000L; // version
    high |= ((random[0] & 0x0FL) << 8) | (random[1] & 0xFFL);

    // Variant 10, then 62 bits of random.
    long low = 0x8000000000000000L;
    low |= (random[2] & 0x3FL) << 56;
    for (int index = 3; index < 10; index++) {
      low |= (random[index] & 0xFFL) << (8 * (9 - index));
    }

    return new UUID(high, low);
  }

  /**
   * Reads the creation instant back out of a UUIDv7.
   *
   * @param uuid a version 7 identifier
   * @return the embedded millisecond
   * @throws IllegalArgumentException if the identifier is not version 7, because deriving a
   *     partition date from a v4's random bits would silently file the row under a date that has
   *     nothing to do with when it was created
   */
  public static Instant instantOf(UUID uuid) {
    if (uuid.version() != 7) {
      throw new IllegalArgumentException(
          "expected a UUIDv7 but got version " + uuid.version() + "; its timestamp is not a time");
    }
    return Instant.ofEpochMilli(uuid.getMostSignificantBits() >>> 16);
  }
}
