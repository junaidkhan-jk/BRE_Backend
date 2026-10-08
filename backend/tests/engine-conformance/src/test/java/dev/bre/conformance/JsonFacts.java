package dev.bre.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import dev.bre.engine.eval.Value;
import dev.bre.engine.eval.Value.BoolV;
import dev.bre.engine.eval.Value.DecimalV;
import dev.bre.engine.eval.Value.IntV;
import dev.bre.engine.eval.Value.ListV;
import dev.bre.engine.eval.Value.Missing;
import dev.bre.engine.eval.Value.RecordV;
import dev.bre.engine.eval.Value.TextV;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Converts a vector's JSON facts into engine values, and engine values back into the vector's
 * {@code expect} shape.
 *
 * <p>This lives in the test module, not in {@code bre-engine-core}, because the engine must not
 * depend on Jackson (LLD §1.2) — the ArchUnit dependency rule fails the build if it does. The real
 * fact reader is {@code bre-fact-model}'s single-pass {@code FactReader}, driven by the fact
 * schema, which arrives in M1-14.
 *
 * <p>ponytail: typing here is inferred from JSON shape rather than from the fact schema, because
 * there is no schema yet. A JSON string that looks numeric becomes a Decimal, which is right for
 * every vector written so far (§3.6 accepts numeric strings for Decimal fields) but would
 * misclassify a genuinely textual fact whose value happens to be all digits. Upgrade path: once
 * {@code bre-fact-model} exists, delete this and read facts through the schema.
 */
final class JsonFacts {

  /** The form §3.6 accepts for a Decimal in a JSON string. Exponent notation is rejected. */
  private static final Pattern PLAIN_DECIMAL = Pattern.compile("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$");

  private JsonFacts() {}

  /** Reads a vector's {@code facts} object into a record. */
  static RecordV toFacts(JsonNode node) {
    Value value = toValue(node);
    return value instanceof RecordV record ? record : RecordV.EMPTY;
  }

  /** Reads a vector's {@code params} or {@code lists} object into a name-to-value map. */
  static Map<String, Value> toBindings(JsonNode node) {
    Map<String, Value> bindings = new LinkedHashMap<>();
    if (node == null || !node.isObject()) {
      return bindings;
    }
    node.properties()
        .forEach(
            entry -> {
              // Params and lists carry the same typed envelope as expect.value, so that a bare
              // JSON 20 cannot be mistaken between Integer and Decimal.
              JsonNode v = entry.getValue();
              bindings.put(entry.getKey(), isTypedEnvelope(v) ? fromEnvelope(v) : toValue(v));
            });
    return bindings;
  }

  private static boolean isTypedEnvelope(JsonNode node) {
    if (node == null || !node.isObject() || node.size() != 1) {
      return false;
    }
    String key = node.fieldNames().next();
    return switch (key) {
      case "missing", "bool", "decimal", "int", "text", "date", "list" -> true;
      default -> false;
    };
  }

  private static Value fromEnvelope(JsonNode node) {
    String key = node.fieldNames().next();
    JsonNode v = node.get(key);
    return switch (key) {
      case "missing" -> Missing.INSTANCE;
      case "bool" -> BoolV.of(v.asBoolean());
      case "decimal" -> DecimalV.of(v.asText());
      case "int" -> new IntV(v.asLong());
      case "text" -> new TextV(v.asText());
      case "date" -> new Value.DateV(java.time.LocalDate.parse(v.asText()));
      case "list" -> {
        List<Value> elements = new ArrayList<>();
        v.forEach(element -> elements.add(fromEnvelope(element)));
        yield new ListV(elements);
      }
      default -> throw new IllegalArgumentException("unknown typed-value key: " + key);
    };
  }

  private static Value toValue(JsonNode node) {
    if (node == null || node.isNull()) {
      // A missing fact is an ABSENT field, never an explicit null, so a null here is a vector
      // authoring mistake rather than a value.
      return Missing.INSTANCE;
    }
    if (node.isObject()) {
      Map<String, Value> fields = new LinkedHashMap<>();
      node.properties().forEach(entry -> fields.put(entry.getKey(), toValue(entry.getValue())));
      return new RecordV(fields);
    }
    if (node.isArray()) {
      List<Value> elements = new ArrayList<>();
      node.forEach(element -> elements.add(toValue(element)));
      return new ListV(elements);
    }
    if (node.isBoolean()) {
      return BoolV.of(node.asBoolean());
    }
    if (node.isIntegralNumber()) {
      return new IntV(node.asLong());
    }
    if (node.isNumber()) {
      return new DecimalV(node.decimalValue());
    }
    String text = node.asText();
    return PLAIN_DECIMAL.matcher(text).matches() ? DecimalV.of(text) : new TextV(text);
  }

  /** Renders an engine value as the vector format's typed envelope, for comparison. */
  static Map<String, Object> toEnvelope(Value value) {
    Map<String, Object> envelope = new LinkedHashMap<>(1);
    switch (value) {
      case Missing ignored -> envelope.put("missing", true);
      case BoolV b -> envelope.put("bool", b.value());
      case IntV i -> envelope.put("int", i.value());
      case DecimalV d -> envelope.put("decimal", d.toCanonicalString());
      case TextV t -> envelope.put("text", t.value());
      case Value.DateV d -> envelope.put("date", d.value().toString());
      case ListV l -> {
        List<Object> elements = new ArrayList<>();
        l.elements().forEach(element -> elements.add(toEnvelope(element)));
        envelope.put("list", elements);
      }
      default ->
          envelope.put(value.typeName().toLowerCase(java.util.Locale.ROOT), value.toString());
    }
    return envelope;
  }
}
