package dev.bre.engine.eval;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A policy's parameters, each with its effective-dated values (§8.4, FR-MODEL-007).
 *
 * <p><b>Why a parameter is not one value.</b> A lender's indebtedness cap changes on the date a
 * regulator says it changes, and both figures stay true — the old one for every application decided
 * before that date, the new one after. A flat {@code name -> value} map cannot express that, so a
 * replay of a decision from March would silently use April's cap and reach a different answer. §8.4
 * is explicit that {@code param.X} is <b>not folded</b> into the bundle as a single value: the
 * bundle carries the whole table and the engine picks the row covering {@code asOf}.
 *
 * <p>That is also what keeps D2 intact. A decision stays a pure function of {@code (facts, bundle,
 * asOf)} — the parameter table is in the bundle, the date is in the request, and nothing is read
 * from a clock or a database at decision time.
 *
 * <p><b>{@code effectiveTo} is exclusive</b> (LLD §8.4), so a value ending 2026-04-01 and one
 * starting 2026-04-01 meet without overlapping and without a gap. The control plane enforces
 * non-overlap with a PostgreSQL exclusion constraint over a {@code daterange}; this class does not
 * re-check it, because a bundle is signed and a table that overlapped would have failed to build.
 *
 * <p><b>A declared parameter with no row covering {@code asOf} is an ERROR, not Missing.</b>
 * Missing means "we do not know this applicant's income", which fails closed to a REFER and is the
 * right answer for absent data. A threshold with no value on the date being decided is a defect in
 * the policy's own configuration, and {@link EvalErrorCode#EVAL_PARAM_NOT_EFFECTIVE} says so
 * precisely rather than leaving an author to wonder which fact was absent.
 */
public final class ParameterTable {

  /** The table for a policy that declares no parameters. */
  public static final ParameterTable EMPTY = new ParameterTable(Map.of());

  private final Map<String, List<Window>> windows;

  /**
   * One value and the dates it applies to.
   *
   * @param effectiveFrom inclusive
   * @param effectiveTo exclusive, or empty for open-ended
   * @param value the value, already typed
   */
  public record Window(LocalDate effectiveFrom, Optional<LocalDate> effectiveTo, Value value) {

    /** Whether this window covers a date. */
    public boolean covers(LocalDate date) {
      return !date.isBefore(effectiveFrom) && effectiveTo.map(date::isBefore).orElse(true);
    }
  }

  private ParameterTable(Map<String, List<Window>> windows) {
    Map<String, List<Window>> copy = new LinkedHashMap<>();
    windows.forEach((name, list) -> copy.put(name, List.copyOf(list)));
    this.windows = Map.copyOf(copy);
  }

  /**
   * A table from dated windows.
   *
   * @param windows each parameter's windows, in any order
   * @return the table
   */
  public static ParameterTable dated(Map<String, List<Window>> windows) {
    return windows.isEmpty() ? EMPTY : new ParameterTable(windows);
  }

  /**
   * A table of undated values, each open-ended.
   *
   * <p>The compatibility path, and the honest reading of a parameter declared with a single value
   * and no dates: it has always applied and still does.
   *
   * @param values name to value
   * @return the table
   */
  public static ParameterTable undated(Map<String, Value> values) {
    if (values.isEmpty()) {
      return EMPTY;
    }
    Map<String, List<Window>> windows = new LinkedHashMap<>();
    values.forEach(
        (name, value) ->
            windows.put(name, List.of(new Window(LocalDate.MIN, Optional.empty(), value))));
    return new ParameterTable(windows);
  }

  /**
   * The value effective on a date.
   *
   * @param name the parameter
   * @param asOf the business date being decided
   * @return the value, or {@link Value.Missing} when the parameter is not declared at all
   * @throws EvalException {@code EVAL_PARAM_NOT_EFFECTIVE} when it is declared and no window covers
   *     the date
   */
  public Value valueAt(String name, LocalDate asOf) {
    List<Window> declared = windows.get(name);
    if (declared == null) {
      // Not declared is a different thing from declared-but-not-yet-effective. An expression
      // reading a parameter the policy never bound reads Missing and fails closed, which is how
      // every other unbound reference behaves.
      return Value.Missing.INSTANCE;
    }
    for (Window window : declared) {
      if (window.covers(asOf)) {
        return window.value();
      }
    }
    throw new EvalException(EvalErrorCode.EVAL_PARAM_NOT_EFFECTIVE, "param." + name, name);
  }

  /**
   * Every parameter name in the table.
   *
   * @return the names
   */
  public Set<String> names() {
    return windows.keySet();
  }

  /**
   * The windows declared for one parameter.
   *
   * @param name the parameter
   * @return its windows, empty when it is not declared
   */
  public List<Window> windowsOf(String name) {
    return windows.getOrDefault(name, List.of());
  }

  /** Whether the table declares nothing. */
  public boolean isEmpty() {
    return windows.isEmpty();
  }

  /**
   * Every date on which some parameter's value changes, within a range.
   *
   * <p>For CHK-013 and §4.13.1's "once per parameter epoch" static analysis: a decision table whose
   * cells reference parameters has to be checked against each distinct combination of effective
   * values, and these are the dates where a combination changes.
   *
   * @param from inclusive
   * @param to inclusive
   * @return the change dates in order, excluding any open-ended start
   */
  public List<LocalDate> changeDates(LocalDate from, LocalDate to) {
    List<LocalDate> dates = new ArrayList<>();
    for (List<Window> declared : windows.values()) {
      for (Window window : declared) {
        if (!window.effectiveFrom().equals(LocalDate.MIN)
            && !window.effectiveFrom().isBefore(from)
            && !window.effectiveFrom().isAfter(to)
            && !dates.contains(window.effectiveFrom())) {
          dates.add(window.effectiveFrom());
        }
        window
            .effectiveTo()
            .filter(end -> !end.isBefore(from) && !end.isAfter(to) && !dates.contains(end))
            .ifPresent(dates::add);
      }
    }
    dates.sort(null);
    return List.copyOf(dates);
  }
}
