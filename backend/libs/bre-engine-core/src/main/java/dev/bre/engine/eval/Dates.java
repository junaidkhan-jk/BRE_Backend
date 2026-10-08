package dev.bre.engine.eval;

import dev.bre.engine.eval.Value.DateV;
import dev.bre.engine.eval.Value.IntV;
import dev.bre.engine.eval.Value.Missing;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;

/**
 * Date arithmetic (LLD §3.7, M1-01).
 *
 * <p><b>Nothing here reads a clock.</b> There is no {@code now()}, no {@code Clock}, no {@code
 * Instant.now()} — every function takes the dates it works on, and the only "today" the engine
 * knows is the request's {@code asOf} (D8). That is what makes "replay this decision from March"
 * exact, and {@code EngineArchRules.noClockOrRandomness} fails the build if anyone adds one.
 *
 * <p>The month-arithmetic rules are the ones people get wrong, so they are pinned by vectors:
 * {@code monthsBetween} counts <em>completed</em> months, and {@code addMonths} clamps to the end
 * of the target month.
 */
public final class Dates {

  private Dates() {}

  /**
   * {@code monthsBetween(a, b)} — completed months between two dates, negative when {@code b < a}.
   *
   * <p>{@code monthsBetween(2026-01-31, 2026-02-28)} is {@code 0}: February 28 has not completed a
   * month from January 31 (S10-02).
   *
   * @param from the earlier date
   * @param to the later date
   * @return the completed months as an Integer, or {@link Missing} if either date is missing
   */
  public static Value monthsBetween(Value from, Value to) {
    if (from.isMissing() || to.isMissing()) {
      return Missing.INSTANCE;
    }
    return new IntV(Period.between(date(from), date(to)).toTotalMonths());
  }

  /**
   * {@code ageInYears(dob, d)} — {@code Period.between(dob, d).getYears()} (S10).
   *
   * <p>{@code ageInYears(2004-02-29, 2026-02-28)} is {@code 21}, and on {@code 2026-03-01} it is
   * {@code 22}: a leap-day birthday has not had its anniversary on February 28 of a common year
   * (S10-01).
   *
   * @param dateOfBirth the date of birth
   * @param on the date to measure at — in practice {@code asOf}, never a clock reading
   * @return the age in whole years as an Integer, or {@link Missing} if either date is missing
   */
  public static Value ageInYears(Value dateOfBirth, Value on) {
    if (dateOfBirth.isMissing() || on.isMissing()) {
      return Missing.INSTANCE;
    }
    return new IntV(Period.between(date(dateOfBirth), date(on)).getYears());
  }

  /**
   * {@code addMonths(d, n)} — {@code d.plusMonths(n)}, clamped to the end of the month.
   *
   * <p>{@code addMonths(2026-01-31, 1)} is {@code 2026-02-28}, not an error and not March 3
   * (S10-02).
   *
   * @param date the starting date
   * @param months the months to add; may be negative
   * @return the shifted Date, or {@link Missing} if either argument is missing
   */
  public static Value addMonths(Value date, Value months) {
    if (date.isMissing() || months.isMissing()) {
      return Missing.INSTANCE;
    }
    long count = ((IntV) months).value();
    return new DateV(date(date).plusMonths(count));
  }

  /**
   * {@code daysBetween(a, b)} — {@code ChronoUnit.DAYS.between(a, b)}.
   *
   * @param from the earlier date
   * @param to the later date
   * @return the day count as an Integer, or {@link Missing} if either date is missing
   */
  public static Value daysBetween(Value from, Value to) {
    if (from.isMissing() || to.isMissing()) {
      return Missing.INSTANCE;
    }
    return new IntV(ChronoUnit.DAYS.between(date(from), date(to)));
  }

  private static LocalDate date(Value value) {
    if (value instanceof DateV d) {
      return d.value();
    }
    throw new EvalException(EvalErrorCode.EVAL_INTERNAL, null, value.typeName());
  }
}
