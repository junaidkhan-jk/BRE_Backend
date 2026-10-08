package dev.bre.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** UUIDv7 (ADR-008, RFC 9562 §5.7). */
class UuidV7Test {

  @Test
  @DisplayName("the identifier reports version 7 and the RFC variant")
  void versionAndVariant() {
    UUID id = UuidV7.generate();
    assertThat(id.version()).isEqualTo(7);
    assertThat(id.variant()).isEqualTo(2);
  }

  @Test
  @DisplayName("the creation instant round-trips, to the millisecond")
  void timestampRoundTrips() {
    Instant when = Instant.parse("2026-10-08T09:15:30.123Z");
    assertThat(UuidV7.instantOf(UuidV7.generate(when)))
        .as("the hot store derives decision_date from this, so it must be the real instant")
        .isEqualTo(when);
  }

  @Test
  @DisplayName("ids minted in time order sort in time order")
  void sortsByTime() {
    UUID earlier = UuidV7.generate(Instant.parse("2026-10-08T00:00:00Z"));
    UUID later = UuidV7.generate(Instant.parse("2026-10-09T00:00:00Z"));
    // Compared as unsigned: UUID.compareTo treats the halves as signed, so it does not order v7
    // identifiers by time. Index locality depends on the BYTE order, which is what this checks.
    assertThat(
            Long.compareUnsigned(earlier.getMostSignificantBits(), later.getMostSignificantBits()))
        .isNegative();
  }

  @Test
  @DisplayName("a v4 identifier is rejected rather than read as a nonsense date")
  void rejectsV4() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> UuidV7.instantOf(UUID.randomUUID()))
        .withMessageContaining("version 4");
  }

  @Test
  @DisplayName("ids minted within the same millisecond are still distinct")
  void distinctWithinOneMillisecond() {
    Instant fixed = Instant.parse("2026-10-08T09:15:30.123Z");
    Set<UUID> ids = new HashSet<>();
    for (int i = 0; i < 10_000; i++) {
      ids.add(UuidV7.generate(fixed));
    }
    assertThat(ids)
        .as("74 random bits per id; a collision at this scale would mean the randomness is broken")
        .hasSize(10_000);
  }
}
