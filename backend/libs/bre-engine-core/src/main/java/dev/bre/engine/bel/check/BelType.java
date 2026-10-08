package dev.bre.engine.bel.check;

import java.util.Locale;
import java.util.Optional;

/**
 * A BEL static type (LLD §3.4).
 *
 * <p>A type rather than a string, so the checker cannot compare {@code "Decimal"} with {@code
 * "decimal"} and conclude they differ. {@code Missing} is not among them: missing is a
 * <em>value</em> every type admits (§3.5's three-valued logic), not a type of its own — modelling
 * it as a type would make every expression's type a union and the checker useless.
 */
public enum BelType {
  /** An exact decimal. All money (00 §3.9). */
  DECIMAL("Decimal"),
  /** A 64-bit integer. */
  INTEGER("Integer"),
  /** A three-valued boolean. */
  BOOLEAN("Boolean"),
  /** NFC text. Equality only; ordering is BEL-010. */
  TEXT("Text"),
  /** A calendar date. */
  DATE("Date"),
  /** An instant with an offset. Not comparable with a Date (BEL-021). */
  DATE_TIME("DateTime"),
  /** A year-month-day period. */
  PERIOD("Period"),
  /** A record, reachable only by field access. */
  RECORD("Record"),
  /** A list of decimals. */
  LIST_DECIMAL("List<Decimal>"),
  /** A list of integers. */
  LIST_INTEGER("List<Integer>"),
  /** A list of text. */
  LIST_TEXT("List<Text>"),
  /** A list of dates. */
  LIST_DATE("List<Date>"),
  /** A list of records. */
  LIST_RECORD("List<Record>");

  private final String wire;

  BelType(String wire) {
    this.wire = wire;
  }

  /**
   * The type's name as the IR and the schema's {@code belType} spell it.
   *
   * @return e.g. {@code "List<Decimal>"}
   */
  public String wire() {
    return wire;
  }

  @Override
  public String toString() {
    return wire;
  }

  /**
   * Parses a type name.
   *
   * @param name the name as the schema spells it
   * @return the type, empty when the name is not a BEL type
   */
  public static Optional<BelType> of(String name) {
    if (name == null) {
      return Optional.empty();
    }
    for (BelType type : values()) {
      if (type.wire.equalsIgnoreCase(name.trim())) {
        return Optional.of(type);
      }
    }
    return Optional.empty();
  }

  /** Whether this is a list type. */
  public boolean isList() {
    return wire.startsWith("List<");
  }

  /**
   * The element type of a list.
   *
   * @return the element type, empty when this is not a list
   */
  public Optional<BelType> elementType() {
    if (!isList()) {
      return Optional.empty();
    }
    return of(wire.substring("List<".length(), wire.length() - 1));
  }

  /**
   * The list type whose elements are this type.
   *
   * @return the list type, empty when no list of this element type exists
   */
  public Optional<BelType> listOf() {
    return of("List<" + wire + ">");
  }

  /** Whether arithmetic and ordering apply (§3.6). */
  public boolean isNumeric() {
    return this == DECIMAL || this == INTEGER;
  }

  /**
   * Whether ordering comparisons are allowed (§3.4).
   *
   * <p>Text is ordered by no BEL operator: {@code "B" > "A"} is BEL-010. Collation is
   * locale-dependent, and a credit rule whose outcome depends on the server's locale is not
   * reproducible — which D8 forbids outright.
   */
  public boolean isOrdered() {
    return isNumeric() || this == DATE || this == DATE_TIME || this == PERIOD;
  }

  /** Whether two types may be compared for equality. */
  public boolean isComparableWith(BelType other) {
    if (this == other) {
      return true;
    }
    // Integer and Decimal interoperate: §3.6 widens an Integer to a Decimal for arithmetic and
    // comparison, so `tenureMonths > 12.5` is meaningful and must not be a type error.
    return isNumeric() && other.isNumeric();
  }

  /** The type arithmetic on these two operands produces (§3.6). */
  public static Optional<BelType> arithmeticResult(BelType left, BelType right) {
    if (left.isNumeric() && right.isNumeric()) {
      // Any mixed arithmetic is exact-decimal, never a narrowing back to Integer.
      return Optional.of(left == INTEGER && right == INTEGER ? INTEGER : DECIMAL);
    }
    // Date arithmetic is through functions (addMonths, monthsBetween), not operators, so there is
    // deliberately no Date + Period here (§3.7).
    return Optional.empty();
  }

  /**
   * Parses a type name case-sensitively, as the schema writes it.
   *
   * @param name the name
   * @return the type, empty when unknown
   */
  public static Optional<BelType> ofExact(String name) {
    if (name == null) {
      return Optional.empty();
    }
    for (BelType type : values()) {
      if (type.wire.equals(name)) {
        return Optional.of(type);
      }
    }
    return Optional.empty();
  }

  /** A lower-case spelling, for a diagnostic. */
  String lower() {
    return wire.toLowerCase(Locale.ROOT);
  }
}
