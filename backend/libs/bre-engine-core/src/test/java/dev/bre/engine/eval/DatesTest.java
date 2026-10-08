package dev.bre.engine.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bre.engine.eval.Value.DateV;
import dev.bre.engine.eval.Value.IntV;
import dev.bre.engine.eval.Value.Missing;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Date semantics, LLD §3.7 — the {@code S10-} conformance vectors as direct assertions. */
class DatesTest {

  private static DateV date(String iso) {
    return new DateV(LocalDate.parse(iso));
  }

  private static long asLong(Value value) {
    return ((IntV) value).value();
  }

  @ParameterizedTest(name = "ageInYears({0}, {1}) = {2}")
  @CsvSource({
    "2004-02-29, 2026-02-28, 21", // S10-01: the leap-day anniversary has not arrived
    "2004-02-29, 2026-03-01, 22", // S10-01: by March 1 it has
    "2008-10-02, 2026-10-01, 17", // §3.7 worked example
    "2008-10-02, 2026-10-02, 18",
  })
  @DisplayName("S10-01: ageInYears counts whole years, and a leap-day birthday is not rounded up")
  void ageInYears(String dateOfBirth, String on, long expected) {
    assertThat(asLong(Dates.ageInYears(date(dateOfBirth), date(on)))).isEqualTo(expected);
  }

  @ParameterizedTest(name = "monthsBetween({0}, {1}) = {2}")
  @CsvSource({
    "2026-01-31, 2026-02-28, 0", // S10-02: February 28 has not completed a month from January 31
    "2026-01-31, 2026-03-01, 1",
    "2026-03-01, 2026-01-31, -1", // negative when b < a
    "2026-01-01, 2027-01-01, 12",
  })
  @DisplayName("S10-02: monthsBetween counts completed months, and goes negative")
  void monthsBetween(String from, String to, long expected) {
    assertThat(asLong(Dates.monthsBetween(date(from), date(to)))).isEqualTo(expected);
  }

  @ParameterizedTest(name = "addMonths({0}, {1}) = {2}")
  @CsvSource({
    "2026-01-31, 1, 2026-02-28", // S10-02: clamped to the end of February
    "2024-01-31, 1, 2024-02-29", // clamped to a leap February
    "2026-01-15, 1, 2026-02-15",
    "2026-03-31, -1, 2026-02-28",
  })
  @DisplayName("S10-02: addMonths clamps to the end of the target month")
  void addMonths(String from, long months, String expected) {
    Value shifted = Dates.addMonths(date(from), new IntV(months));
    assertThat(((DateV) shifted).value()).isEqualTo(LocalDate.parse(expected));
  }

  @Test
  @DisplayName("daysBetween counts calendar days")
  void daysBetween() {
    assertThat(asLong(Dates.daysBetween(date("2026-01-01"), date("2026-01-31")))).isEqualTo(30);
    assertThat(asLong(Dates.daysBetween(date("2026-01-31"), date("2026-01-01")))).isEqualTo(-30);
  }

  @Test
  @DisplayName("every date function propagates missing strictly (§3.5)")
  void missingPropagates() {
    Value missing = Missing.INSTANCE;
    assertThat(Dates.ageInYears(missing, date("2026-10-01"))).isEqualTo(missing);
    assertThat(Dates.ageInYears(date("2004-02-29"), missing)).isEqualTo(missing);
    assertThat(Dates.monthsBetween(missing, date("2026-10-01"))).isEqualTo(missing);
    assertThat(Dates.addMonths(date("2026-01-31"), missing)).isEqualTo(missing);
    assertThat(Dates.daysBetween(missing, missing)).isEqualTo(missing);
  }
}
