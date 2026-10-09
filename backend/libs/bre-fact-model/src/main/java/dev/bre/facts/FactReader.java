package dev.bre.facts;

import dev.bre.engine.eval.Value;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads a facts document into engine values, <b>through the fact schema</b> (M1-14, §3.4).
 *
 * <p><b>Why through the schema and not by looking at the JSON.</b> The decision service previously
 * inferred each value's type from its JSON shape, and that inference cannot be made correct:
 *
 * <ul>
 *   <li>A date arrives as a JSON string and became {@code Text}, so {@code
 *       ageInYears(member.dateOfBirth, asOf)} was a type error on every real applicant. The failure
 *       was invisible — strict missing propagation turned it into a REFER for missing data (§3.9),
 *       so a policy that could never work looked like a data-quality problem.
 *   <li>A numeric-looking string became {@code Decimal}, so an account number, a branch code or a
 *       document reference made of digits became a number — and {@code "007"} and {@code "7"}
 *       compared equal.
 * </ul>
 *
 * <p>The schema says what each path is, independently of what any one document happens to contain,
 * so the same field is the same type for every applicant. §3.4's types are the contract and this is
 * where the contract is applied.
 *
 * <p><b>An ill-formed value reads as missing.</b> A {@code "2026-13-45"} in a Date field does not
 * throw: it becomes Missing, which propagates strictly and fails closed (D9, §3.9), so the worst
 * outcome is a REFER rather than a decision taken on a value nobody could parse. Reporting it is
 * the validator's job — {@code bre facts validate} and the request-time schema check name the path
 * — because a reader that threw would turn one bad field into a 500 for the whole application.
 *
 * <p><b>A bound entity types as the collection it came from.</b> §3.8 defines {@code member} as a
 * derived view of {@code parties} — the parties with role MEMBER, in group order — so {@code
 * member.dateOfBirth} is the same field, with the same declared type, as {@code
 * parties[].dateOfBirth}. The same holds for {@code party}, {@code household} and {@code
 * collateral}. Without this mapping a facts document that binds one entity directly, which is how
 * the §13 vectors and every single-member test are written, would have no declared type for any
 * field under it.
 *
 * <p><b>An undeclared path keeps its JSON shape and is never re-typed.</b> {@code ext} objects are
 * explicitly open (§3.4), so their contents have no declared type; a string there stays Text. That
 * is the one place the old sniffing had any justification, and it is wrong even here: an {@code
 * ext} field that needs to be a number should be declared.
 */
public final class FactReader {

  /** The plain form §3.6 accepts for a Decimal carried as a JSON string; no exponent notation. */
  private static final Pattern PLAIN_DECIMAL = Pattern.compile("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$");

  /** Bound-entity roots and the schema collections they are views of (§3.8). */
  private static final Map<String, String> ENTITY_ROOTS =
      Map.of(
          "member", "parties[]",
          "party", "parties[]",
          "household", "households[]",
          "collateral", "collaterals[]");

  private final FactSchema schema;

  /**
   * Creates a reader over a schema.
   *
   * @param schema the fact schema the document is declared against
   */
  public FactReader(FactSchema schema) {
    this.schema = schema;
  }

  /**
   * Reads a decoded JSON object into a fact record.
   *
   * @param facts the facts as decoded by a JSON parser: maps, lists, strings, numbers, booleans
   * @return the fact record; an absent or unparseable field reads as missing, never as null
   */
  public Value.RecordV toRecord(Map<String, ?> facts) {
    Value value = read(facts, "");
    return value instanceof Value.RecordV record ? record : Value.RecordV.EMPTY;
  }

  private Value read(Object raw, String path) {
    if (raw == null) {
      // An explicit null is treated as missing rather than rejected, so a caller that serialises
      // empty fields does not get a 400 for it.
      return Value.Missing.INSTANCE;
    }
    if (raw instanceof Map<?, ?> map) {
      Map<String, Value> fields = new LinkedHashMap<>();
      map.forEach(
          (key, element) -> fields.put(String.valueOf(key), read(element, child(path, key))));
      return new Value.RecordV(fields);
    }
    if (raw instanceof List<?> list) {
      List<Value> elements = new ArrayList<>(list.size());
      String elementPath = path + "[]";
      list.forEach(element -> elements.add(read(element, elementPath)));
      return new Value.ListV(elements);
    }
    return scalar(raw, schema.field(path).map(FactSchema.Field::belType));
  }

  private static String child(String path, Object key) {
    if (!path.isEmpty()) {
      return path + "." + key;
    }
    String root = String.valueOf(key);
    return ENTITY_ROOTS.getOrDefault(root, root);
  }

  private static Value scalar(Object raw, Optional<String> declared) {
    if (declared.isEmpty()) {
      return undeclared(raw);
    }
    return switch (declared.get()) {
      case "Integer" -> integer(raw);
      case "Decimal" -> decimal(raw);
      case "Boolean" -> raw instanceof Boolean bool ? Value.BoolV.of(bool) : missing();
      case "Date" -> date(raw);
      case "DateTime" -> dateTime(raw);
      case "Period" -> period(raw);
      // Text, and anything the schema maps to Text. A number in a Text field is read as its text
      // rather than refused: JSON gives no way to distinguish 7 from "7" after the fact, and
      // refusing would fail a request over a caller's serialiser choice.
      default -> new Value.TextV(String.valueOf(raw));
    };
  }

  private static Value undeclared(Object raw) {
    if (raw instanceof Boolean bool) {
      return Value.BoolV.of(bool);
    }
    if (raw instanceof Integer || raw instanceof Long || raw instanceof Short) {
      return new Value.IntV(((Number) raw).longValue());
    }
    if (raw instanceof BigDecimal value) {
      return new Value.DecimalV(value);
    }
    if (raw instanceof Number number) {
      // Via the string form: a double's binary value is not the decimal the caller sent.
      return Value.DecimalV.of(number.toString());
    }
    return new Value.TextV(String.valueOf(raw));
  }

  private static Value integer(Object raw) {
    if (raw instanceof Integer || raw instanceof Long || raw instanceof Short) {
      return new Value.IntV(((Number) raw).longValue());
    }
    if (raw instanceof BigDecimal value && value.stripTrailingZeros().scale() <= 0) {
      try {
        return new Value.IntV(value.longValueExact());
      } catch (ArithmeticException tooLarge) {
        return missing();
      }
    }
    if (raw instanceof String text) {
      try {
        return new Value.IntV(Long.parseLong(text));
      } catch (NumberFormatException notAnInteger) {
        return missing();
      }
    }
    return missing();
  }

  private static Value decimal(Object raw) {
    if (raw instanceof BigDecimal value) {
      return new Value.DecimalV(value);
    }
    if (raw instanceof Integer || raw instanceof Long || raw instanceof Short) {
      return new Value.DecimalV(BigDecimal.valueOf(((Number) raw).longValue()));
    }
    if (raw instanceof Number number) {
      return Value.DecimalV.of(number.toString());
    }
    if (raw instanceof String text && PLAIN_DECIMAL.matcher(text).matches()) {
      return Value.DecimalV.of(text);
    }
    // Exponent notation included: §3.6 accepts only the plain form, and 1.5e3 in a money field is
    // a schema violation rather than a value to be guessed at.
    return missing();
  }

  private static Value date(Object raw) {
    if (!(raw instanceof String text)) {
      return missing();
    }
    try {
      return new Value.DateV(LocalDate.parse(text));
    } catch (RuntimeException notADate) {
      return missing();
    }
  }

  private static Value dateTime(Object raw) {
    if (!(raw instanceof String text)) {
      return missing();
    }
    try {
      return new Value.DateTimeV(OffsetDateTime.parse(text));
    } catch (RuntimeException notADateTime) {
      return missing();
    }
  }

  private static Value period(Object raw) {
    if (!(raw instanceof String text)) {
      return missing();
    }
    try {
      return new Value.PeriodV(Period.parse(text));
    } catch (RuntimeException notAPeriod) {
      return missing();
    }
  }

  private static Value missing() {
    return Value.Missing.INSTANCE;
  }
}
