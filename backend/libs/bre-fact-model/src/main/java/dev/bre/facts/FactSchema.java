package dev.bre.facts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The canonical fact schema, read for the things the compiler and the data plane need from it (LLD
 * §9.1, 00 §3.9).
 *
 * <p>A JSON Schema 2020-12 document carrying BRE vendor keywords on every leaf. Validation of a
 * facts document against the schema is JSON Schema's job; this class answers the questions the rest
 * of the platform asks about a <em>field</em>:
 *
 * <ul>
 *   <li><b>What type is it?</b> — so the type checker can reject {@code application.purpose >= 650}
 *       at authoring time instead of producing a runtime ERROR on a real application.
 *   <li><b>Is it {@code protected}?</b> — {@code gender}, {@code religion}, {@code caste}. Reading
 *       one from rule logic is BEL-017 without an approved governance override. These are the
 *       fields that make a lending decision discriminatory, and the override exists so that using
 *       one is a recorded, approved act rather than a line of code nobody reviewed.
 *   <li><b>Is it {@code aadhaar}?</b> — BEL-026, and there is no override. A rule may not read it
 *       at all.
 *   <li><b>Which encryption envelope and which mask?</b> — {@code x-bre-group} and {@code
 *       x-bre-mask} (§9.2, §9.4).
 * </ul>
 *
 * <p><b>Paths address fields, not positions.</b> A list element is {@code []}, so {@code
 * parties[].bureauReports[].tradelines[].currentBalance} is one path with one type and one set of
 * tags however many parties, reports and tradelines a particular application has. That is what lets
 * {@code BreJcs.canonicalizeFacts} take a path predicate, and it is the only addressing scheme
 * under which a schema can describe a document it has not seen.
 *
 * <p><b>An unknown field has no tags.</b> Deliberately, and worth being explicit about: a path the
 * schema does not describe is not treated as protected, because the schema is also what says a path
 * exists at all (BEL-019). An unknown path is an error about the path, not a silent permission —
 * the caller asks {@link #declares} first.
 */
public final class FactSchema {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** A classification tag from {@code x-bre-tags} (§9.1). */
  public enum Tag {
    /** Masked in every view and log (FR-AUD-008). */
    PII,
    /** Cannot be read by rule logic without an approved override (BEL-017). */
    PROTECTED,
    /** Bureau or KYC-derived: masked, and subject to purpose checks against the consent. */
    REGULATED,
    /** Cannot be read by rule logic at all (BEL-026). */
    AADHAAR
  }

  /** Which encryption envelope a field goes into (§9.2). */
  public enum Group {
    /** Sealed per data principal, under that person's key — so erasure can target it. */
    IDENTIFYING,
    /** Sealed under the tenant key. The default. */
    NON_IDENTIFYING
  }

  /** How a field is masked in a view (§9.4). */
  public enum Mask {
    /** Not shown at all. */
    HIDE,
    /** Shown as an age band rather than a date of birth. */
    AGE_BAND,
    /** Shown as the last four characters of a token. */
    TOKEN_TAIL4,
    /** Rounded to the nearest thousand. */
    ROUND_1000,
    /** Shown to the month, not the day. */
    MONTH_ONLY,
    /** Shown as it is. The default. */
    NONE
  }

  /**
   * One leaf field.
   *
   * @param path the dotted path, with {@code []} for a list element
   * @param belType the BEL type the type checker sees
   * @param tags the classification tags
   * @param group the encryption envelope
   * @param mask the masking rule
   * @param enumValues the permitted values when the field is an enum, for BEL-011
   */
  public record Field(
      String path, String belType, Set<Tag> tags, Group group, Mask mask, Set<String> enumValues) {

    /** Creates a field, copying the sets. */
    public Field {
      tags = Set.copyOf(tags);
      enumValues = Set.copyOf(enumValues);
    }

    /** Whether rule logic needs an approved override to read this field (BEL-017). */
    public boolean isProtected() {
      return tags.contains(Tag.PROTECTED);
    }

    /** Whether rule logic may never read this field (BEL-026). */
    public boolean isAadhaar() {
      return tags.contains(Tag.AADHAAR);
    }

    /** Whether this field must be masked in views and logs. */
    public boolean isMasked() {
      return tags.contains(Tag.PII) || tags.contains(Tag.REGULATED);
    }
  }

  private final String code;
  private final String version;
  private final Map<String, Field> fields;

  private FactSchema(String code, String version, Map<String, Field> fields) {
    this.code = code;
    this.version = version;
    this.fields = fields;
  }

  /**
   * Reads a fact schema from its JSON Schema document.
   *
   * @param code the schema's code, e.g. {@code lending-facts}
   * @param version its semantic version
   * @param json the JSON Schema document
   * @return the schema
   */
  public static FactSchema parse(String code, String version, String json) {
    try {
      return parse(code, version, MAPPER.readTree(json));
    } catch (IOException e) {
      throw new UncheckedIOException("the fact schema is not valid JSON", e);
    }
  }

  /**
   * Reads a fact schema from a parsed JSON Schema document.
   *
   * @param code the schema's code
   * @param version its semantic version
   * @param root the JSON Schema document
   * @return the schema
   */
  public static FactSchema parse(String code, String version, JsonNode root) {
    Map<String, Field> fields = new LinkedHashMap<>();
    walk(root, "", fields);
    return new FactSchema(code, version, Map.copyOf(fields));
  }

  private static void walk(JsonNode node, String path, Map<String, Field> out) {
    if (node == null || !node.isObject()) {
      return;
    }

    JsonNode properties = node.get("properties");
    if (properties != null && properties.isObject()) {
      properties
          .properties()
          .forEach(
              entry ->
                  walk(
                      entry.getValue(),
                      path.isEmpty() ? entry.getKey() : path + "." + entry.getKey(),
                      out));
      return;
    }

    JsonNode items = node.get("items");
    if (items != null) {
      // A list element shares its parent's path plus "[]": the schema types a field, not a
      // position, so every element of every list has the same type and the same tags.
      walk(items, path + "[]", out);

      // The CONTAINER also needs a type, and forgetting it was a real defect: without this,
      // `count(group.memberPartyRefs)` reported BEL-019 "unknown fact path" even though the schema
      // plainly declares the field — only its element had an entry. An author writes the container
      // path, so the container has to have a type.
      if (!path.isEmpty()) {
        listTypeOf(items, out.get(path + "[]"))
            .ifPresent(
                listType ->
                    out.put(
                        path,
                        new Field(
                            path,
                            listType,
                            tagsOf(items),
                            groupOf(items),
                            maskOf(items),
                            Set.of())));
      }
      return;
    }

    if (path.isEmpty()) {
      return;
    }
    out.put(path, leaf(node, path));
  }

  /**
   * The BEL list type whose elements are the given element schema.
   *
   * <p>A list of records types as {@code List<Record>}; a list of scalars as the list of that
   * scalar. A list of lists has no BEL type — the language flattens nested projections at runtime
   * rather than giving them a nested type — so this returns empty and the container simply has no
   * declared type, which is honest rather than wrong.
   */
  private static Optional<String> listTypeOf(JsonNode items, Field element) {
    if (items.has("properties")) {
      return Optional.of("List<Record>");
    }
    if (element == null) {
      return Optional.empty();
    }
    return switch (element.belType()) {
      case "Decimal" -> Optional.of("List<Decimal>");
      case "Integer" -> Optional.of("List<Integer>");
      case "Text" -> Optional.of("List<Text>");
      case "Date" -> Optional.of("List<Date>");
      case "Record" -> Optional.of("List<Record>");
      default -> Optional.empty();
    };
  }

  private static Set<Tag> tagsOf(JsonNode node) {
    Set<Tag> tags = new LinkedHashSet<>();
    JsonNode tagNode = node.get("x-bre-tags");
    if (tagNode != null && tagNode.isArray()) {
      tagNode.forEach(
          t -> {
            Tag parsed = tagOf(t.asText());
            if (parsed != null) {
              tags.add(parsed);
            }
          });
    }
    return tags;
  }

  private static Field leaf(JsonNode node, String path) {
    Set<Tag> tags = new LinkedHashSet<>();
    JsonNode tagNode = node.get("x-bre-tags");
    if (tagNode != null && tagNode.isArray()) {
      tagNode.forEach(
          t -> {
            Tag parsed = tagOf(t.asText());
            if (parsed != null) {
              tags.add(parsed);
            }
            // An unrecognised tag is ignored rather than fatal: the keyword is an extension point,
            // and a schema written against a newer platform must still load on an older one. A tag
            // that GRANTS nothing cannot be unsafe to ignore — and every tag here restricts.
          });
    }

    Set<String> enumValues = new LinkedHashSet<>();
    JsonNode enumNode = node.get("enum");
    if (enumNode != null && enumNode.isArray()) {
      enumNode.forEach(value -> enumValues.add(value.asText()));
    }

    return new Field(
        path,
        belTypeOf(node, !enumValues.isEmpty()),
        tags,
        groupOf(node),
        maskOf(node),
        enumValues);
  }

  private static Tag tagOf(String raw) {
    return switch (raw.toLowerCase(Locale.ROOT)) {
      case "pii" -> Tag.PII;
      case "protected" -> Tag.PROTECTED;
      case "regulated" -> Tag.REGULATED;
      case "aadhaar" -> Tag.AADHAAR;
      default -> null;
    };
  }

  private static Group groupOf(JsonNode node) {
    JsonNode group = node.get("x-bre-group");
    if (group != null && "identifying".equalsIgnoreCase(group.asText())) {
      return Group.IDENTIFYING;
    }
    return Group.NON_IDENTIFYING;
  }

  private static Mask maskOf(JsonNode node) {
    JsonNode mask = node.get("x-bre-mask");
    if (mask == null) {
      return Mask.NONE;
    }
    try {
      return Mask.valueOf(mask.asText().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      // An unknown mask is the one case where the safe default is the RESTRICTIVE one: a field the
      // schema wanted masked somehow must not be shown in full because this build does not know
      // the rule yet.
      return Mask.HIDE;
    }
  }

  /**
   * Maps a JSON Schema type to its BEL type (§3.4).
   *
   * <p>Money is the case that matters: 00 §3.9 says all money is "a decimal in INR with scale 2",
   * and §2.1 rule 4 carries decimals as strings. So a string with {@code x-bre-decimal} or a {@code
   * number} both become {@code Decimal} — never a JSON number read as a double, which is the
   * mistake BRE-JCS exists to prevent on the serialisation side.
   */
  private static String belTypeOf(JsonNode node, boolean isEnum) {
    String type = node.path("type").asText("");
    String format = node.path("format").asText("");

    if (isEnum) {
      return "Text";
    }
    return switch (type) {
      case "boolean" -> "Boolean";
      case "integer" -> "Integer";
      case "number" -> "Decimal";
      case "string" ->
          switch (format) {
            case "date" -> "Date";
            case "date-time" -> "DateTime";
            case "duration" -> "Period";
            default -> node.has("x-bre-decimal") ? "Decimal" : "Text";
          };
      case "object" -> "Record";
      default -> "Text";
    };
  }

  /**
   * Returns the schema's code.
   *
   * @return the code
   */
  public String code() {
    return code;
  }

  /**
   * Returns the schema's version.
   *
   * @return the version
   */
  public String version() {
    return version;
  }

  /**
   * Whether the schema describes a path.
   *
   * @param path the dotted path, with {@code []} for list elements
   * @return true when the path is a declared leaf field
   */
  public boolean declares(String path) {
    return fields.containsKey(path);
  }

  /**
   * Looks a field up by path.
   *
   * @param path the dotted path
   * @return the field, empty when the schema does not describe it (BEL-019)
   */
  public Optional<Field> field(String path) {
    return Optional.ofNullable(fields.get(path));
  }

  /**
   * Every declared leaf, in document order.
   *
   * @return the fields by path
   */
  public Map<String, Field> fields() {
    return fields;
  }

  /**
   * Whether a path is a schema-typed Decimal, for {@code BreJcs.canonicalizeFacts}.
   *
   * @param path the dotted path
   * @return true when the field exists and is a Decimal
   */
  public boolean isDecimal(String path) {
    return field(path).filter(f -> "Decimal".equals(f.belType())).isPresent();
  }
}
