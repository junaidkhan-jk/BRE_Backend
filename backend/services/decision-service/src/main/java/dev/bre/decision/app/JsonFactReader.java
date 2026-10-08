package dev.bre.decision.app;

import dev.bre.engine.eval.Value;
import dev.bre.engine.eval.Value.BoolV;
import dev.bre.engine.eval.Value.DecimalV;
import dev.bre.engine.eval.Value.IntV;
import dev.bre.engine.eval.Value.ListV;
import dev.bre.engine.eval.Value.Missing;
import dev.bre.engine.eval.Value.RecordV;
import dev.bre.engine.eval.Value.TextV;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads request facts into engine values.
 *
 * <p>Two rules from §3.6 are enforced here rather than left to Jackson, because both affect the
 * numbers a credit decision is made from:
 *
 * <ul>
 *   <li><b>Decimals are read exactly.</b> A JSON number lands as {@link BigDecimal} via its string
 *       form, never through a {@code double}. A {@code double} round trip is how {@code 0.1 + 0.2}
 *       stops being {@code 0.3}.
 *   <li><b>Exponent notation is rejected.</b> A numeric string must match the plain form; {@code
 *       1.5e3} in a Decimal field is a schema violation, not a value to be guessed at.
 * </ul>
 *
 * <p>ponytail: typing is inferred from the JSON shape because the fact schema does not exist yet
 * (M1-14 brings {@code bre-fact-model}'s single-pass {@code FactReader} and the canonical schema).
 * A numeric-looking string becomes a Decimal, which is right for amounts but would misclassify a
 * genuinely textual field whose value happens to be all digits — an account number, say. Upgrade
 * path: read through the schema and delete the sniffing.
 */
final class JsonFactReader {

  /** The plain form §3.6 accepts for a Decimal carried as a JSON string. */
  private static final Pattern PLAIN_DECIMAL = Pattern.compile("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$");

  private JsonFactReader() {}

  /**
   * Converts a decoded JSON object into a fact record.
   *
   * @param facts the facts as decoded by Jackson
   * @return the fact record; an absent field reads as missing, never as null
   */
  static RecordV toRecord(Map<String, Object> facts) {
    Value value = toValue(facts);
    return value instanceof RecordV record ? record : RecordV.EMPTY;
  }

  private static Value toValue(Object raw) {
    if (raw == null) {
      // A missing fact is an absent field. An explicit null is treated as missing rather than
      // rejected, so a caller that serialises empty fields does not get a 400 for it.
      return Missing.INSTANCE;
    }
    if (raw instanceof Map<?, ?> map) {
      Map<String, Value> fields = new LinkedHashMap<>();
      map.forEach((key, element) -> fields.put(String.valueOf(key), toValue(element)));
      return new RecordV(fields);
    }
    if (raw instanceof List<?> list) {
      List<Value> elements = new ArrayList<>(list.size());
      list.forEach(element -> elements.add(toValue(element)));
      return new ListV(elements);
    }
    if (raw instanceof Boolean bool) {
      return BoolV.of(bool);
    }
    if (raw instanceof Integer || raw instanceof Long || raw instanceof Short) {
      return new IntV(((Number) raw).longValue());
    }
    if (raw instanceof BigDecimal decimal) {
      return new DecimalV(decimal);
    }
    if (raw instanceof Number number) {
      // Via the string form: a double's binary value is not the decimal the caller sent.
      return DecimalV.of(number.toString());
    }
    String text = String.valueOf(raw);
    return PLAIN_DECIMAL.matcher(text).matches() ? DecimalV.of(text) : new TextV(text);
  }
}
