package dev.bre.engine.eval;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A BEL runtime value (LLD §3.4, M1-01).
 *
 * <p><b>Missing is not a type.</b> Any value of any type may be missing at runtime — the fact was
 * absent, an operand was missing, or a function returned missing. It is modelled as the singleton
 * {@link Missing} rather than as {@code null}, so that a missing value survives being put in a
 * list, compared, or passed to a function without a single null check anywhere in the evaluator.
 * {@code U} (unknown) in the specification is simply {@link Missing} at Boolean type (§3.5).
 *
 * <p>Nothing here uses {@code double}. Money and ratios are {@link BigDecimal} throughout (D8), and
 * {@code DecimalV} never exposes a binary floating-point path.
 */
public sealed interface Value
    permits Value.Missing,
        Value.IntV,
        Value.DecimalV,
        Value.BoolV,
        Value.TextV,
        Value.DateV,
        Value.DateTimeV,
        Value.PeriodV,
        Value.ListV,
        Value.RecordV {

  /** Returns whether this value is missing. */
  default boolean isMissing() {
    return this instanceof Missing;
  }

  /** The name of this value's type, for diagnostics. Never includes the value itself. */
  String typeName();

  /**
   * The absent value. Also the runtime representation of the specification's {@code U} (unknown),
   * because §3.5 defines unknown as a missing Boolean.
   */
  record Missing() implements Value {
    /** The single instance; missing carries no information, so there is only ever one. */
    public static final Missing INSTANCE = new Missing();

    @Override
    public String typeName() {
      return "Missing";
    }

    @Override
    public String toString() {
      return "missing";
    }
  }

  /** {@code Integer}, carried as a {@code long}. Overflow in {@code + - *} is EVAL_OVERFLOW. */
  record IntV(long value) implements Value {
    @Override
    public String typeName() {
      return "Integer";
    }
  }

  /** {@code Decimal}, carried as {@link BigDecimal}. Never {@code double} (§3.6). */
  record DecimalV(BigDecimal value) implements Value {

    /** Creates a decimal value. */
    public DecimalV {
      Objects.requireNonNull(value, "decimal value");
    }

    /**
     * Parses a decimal from its exact string form.
     *
     * @param plain the value in plain notation; exponent form is rejected upstream by the fact
     *     reader (§3.6)
     * @return the decimal value
     */
    public static DecimalV of(String plain) {
      return new DecimalV(new BigDecimal(plain));
    }

    /**
     * Renders this value the way a decision record serialises it: {@code
     * stripTrailingZeros().toPlainString()} (§3.6), so {@code 212500.0000000000} becomes {@code
     * "212500"} and the result never uses exponent form.
     *
     * @return the canonical plain-notation form
     */
    public String toCanonicalString() {
      BigDecimal stripped = value.stripTrailingZeros();
      // stripTrailingZeros() on an integral value can yield a negative scale (1E+3); toPlainString
      // expands it back out, which is what §3.6 requires.
      return stripped.toPlainString();
    }

    @Override
    public String typeName() {
      return "Decimal";
    }
  }

  /** {@code Boolean}. Three-valued at runtime: TRUE, FALSE, or {@link Missing} (§3.5). */
  record BoolV(boolean value) implements Value {
    /** TRUE. */
    public static final BoolV TRUE = new BoolV(true);

    /** FALSE. */
    public static final BoolV FALSE = new BoolV(false);

    /**
     * Returns the canonical instance for a boolean.
     *
     * @param value the boolean
     * @return {@link #TRUE} or {@link #FALSE}
     */
    public static BoolV of(boolean value) {
      return value ? TRUE : FALSE;
    }

    @Override
    public String typeName() {
      return "Boolean";
    }
  }

  /** {@code Text}, normalised to NFC. Equality only; ordering is a compile error (BEL-010). */
  record TextV(String value) implements Value {

    /** Creates a text value, normalising to NFC per §3.4. */
    public TextV {
      Objects.requireNonNull(value, "text value");
      value = Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    @Override
    public String typeName() {
      return "Text";
    }
  }

  /** {@code Date}, a calendar date. {@code asOf} has this type (§3.7). */
  record DateV(LocalDate value) implements Value {

    /** Creates a date value. */
    public DateV {
      Objects.requireNonNull(value, "date value");
    }

    @Override
    public String typeName() {
      return "Date";
    }
  }

  /** {@code DateTime}, compared on the instant. Not comparable with {@code Date} (BEL-021). */
  record DateTimeV(OffsetDateTime value) implements Value {

    /** Creates a date-time value. */
    public DateTimeV {
      Objects.requireNonNull(value, "dateTime value");
    }

    @Override
    public String typeName() {
      return "DateTime";
    }
  }

  /** {@code Period}, built only by {@code period("P…")} (§3.7). */
  record PeriodV(Period value) implements Value {

    /** Creates a period value. */
    public PeriodV {
      Objects.requireNonNull(value, "period value");
    }

    @Override
    public String typeName() {
      return "Period";
    }
  }

  /** {@code List<T>}, immutable. Elements may be missing (§3.4). */
  record ListV(List<Value> elements) implements Value {

    /** The empty list. {@code sum([])} is 0 and {@code all([], …)} is TRUE (S2-01). */
    public static final ListV EMPTY = new ListV(List.of());

    /** Creates a list value, copying defensively so the value is genuinely immutable. */
    public ListV {
      Objects.requireNonNull(elements, "list elements");
      elements = List.copyOf(elements);
    }

    /**
     * Creates a list of the given elements.
     *
     * @param elements the elements, which may include {@link Missing}
     * @return the list value
     */
    public static ListV of(Value... elements) {
      return new ListV(List.of(elements));
    }

    @Override
    public String typeName() {
      return "List";
    }
  }

  /**
   * {@code Record<R>}, immutable. A structural type taken from the fact schema ({@code Party},
   * {@code Tradeline}, {@code Household}, …).
   *
   * <p>An absent field reads as {@link Missing}, not as a lookup failure: that is what makes the
   * fail-closed semantics of §3.5 fall out of field access rather than needing a check at every
   * call site.
   */
  record RecordV(Map<String, Value> fields) implements Value {

    /** The empty record. */
    public static final RecordV EMPTY = new RecordV(Map.of());

    /** Creates a record value, copying defensively and preserving field order. */
    public RecordV {
      Objects.requireNonNull(fields, "record fields");
      fields = Map.copyOf(new LinkedHashMap<>(fields));
    }

    /**
     * Reads a field.
     *
     * @param name the field name
     * @return the field's value, or {@link Missing#INSTANCE} when the field is absent
     */
    public Value field(String name) {
      return fields.getOrDefault(name, Missing.INSTANCE);
    }

    @Override
    public String typeName() {
      return "Record";
    }
  }
}
