package dev.bre.irjson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * BRE-JCS: canonical JSON for hashing and signing (LLD §2.5).
 *
 * <p>RFC 8785 (JCS) with three changes, and the first one is the whole reason this class exists.
 * RFC 8785 serialises numbers through ECMAScript double formatting, which would turn {@code
 * 123456789012345.67} into a different number — a double holds about 15–17 significant digits and
 * that literal needs 17. A money platform cannot have its canonical form silently round a balance,
 * so BRE-JCS permits <b>integers only</b>; every decimal travels as a string (§2.1 rule 4). A
 * non-integer number reaching here is {@link PsfException.Code#CANON_001}, not a best-effort
 * conversion.
 *
 * <p>Changes two and three: strings and keys follow RFC 8785 exactly (UTF-16 code-unit key order,
 * minimal escaping, UTF-8 output), and facts canonicalisation first rewrites schema-typed decimal
 * fields to their canonical decimal string so that {@code 212500.00} and {@code 212500} hash alike
 * (see {@link #canonicalizeFacts}).
 *
 * <p>Determinism is the point: the same document must produce the same bytes on every machine,
 * every JVM and every release, because {@code bundleHash} and the facts HMAC are computed from them
 * and compared across time. Nothing here consults a locale, a clock or a hash-map iteration order.
 */
public final class BreJcs {

  private BreJcs() {}

  /**
   * Canonicalises a JSON document to its BRE-JCS text.
   *
   * @param node the document
   * @return the canonical text
   * @throws PsfException CANON-001 if any number is not an integer
   */
  public static String canonicalize(JsonNode node) {
    StringBuilder out = new StringBuilder(256);
    write(node, out, "");
    return out.toString();
  }

  /**
   * Canonicalises a JSON document to its BRE-JCS bytes.
   *
   * @param node the document
   * @return the canonical form, UTF-8 encoded
   * @throws PsfException CANON-001 if any number is not an integer
   */
  public static byte[] bytes(JsonNode node) {
    return canonicalize(node).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Hashes a JSON document the way §2.5 defines {@code bundleHash}.
   *
   * @param node the document
   * @return {@code "sha256:"} followed by lowercase hex
   * @throws PsfException CANON-001 if any number is not an integer
   */
  public static String sha256(JsonNode node) {
    return "sha256:" + hex(digest(bytes(node)));
  }

  /**
   * Canonicalises facts for the integrity HMAC (§2.5 rule 3, §9.2).
   *
   * <p>A fact that the schema types as {@code Decimal} is rewritten to {@code
   * stripTrailingZeros().toPlainString()} before rules 1 and 2 apply, so {@code "212500.00"} and
   * {@code "212500"} canonicalise identically. Without that step the same amount, submitted with a
   * different trailing zero by a different Loan Origination System, would produce a different HMAC
   * and look like tampering.
   *
   * <p>The schema itself lives in the fact model, not here, so the caller passes a predicate over
   * the dotted fact path. Keeping this module ignorant of the fact schema is deliberate: BRE-JCS is
   * a serialisation rule, and a serialisation rule that needed a schema registry could not be used
   * to hash a bundle.
   *
   * @param facts the facts document
   * @param isDecimalField true when the dotted path names a schema-typed Decimal field; list
   *     elements are addressed with {@code []}, e.g. {@code member.bureauReports[].score}
   * @return the canonical text
   * @throws PsfException CANON-001 if a non-decimal field holds a non-integer number
   */
  public static String canonicalizeFacts(JsonNode facts, Predicate<String> isDecimalField) {
    StringBuilder out = new StringBuilder(256);
    writeFacts(facts, out, "", isDecimalField);
    return out.toString();
  }

  // ---------------------------------------------------------------------------------------------

  private static void write(JsonNode node, StringBuilder out, String path) {
    switch (node.getNodeType()) {
      case OBJECT -> writeObject((ObjectNode) node, out, path, null);
      case ARRAY -> writeArray((ArrayNode) node, out, path, null);
      case STRING -> writeString(node.textValue(), out);
      case NUMBER -> writeInteger(node, out, path);
      case BOOLEAN -> out.append(node.booleanValue() ? "true" : "false");
      case NULL -> out.append("null");
      default ->
          throw new PsfException(
              PsfException.Code.CANON_001, path, "unsupported node type " + node.getNodeType());
    }
  }

  private static void writeFacts(
      JsonNode node, StringBuilder out, String path, Predicate<String> isDecimalField) {
    switch (node.getNodeType()) {
      case OBJECT -> writeObject((ObjectNode) node, out, path, isDecimalField);
      case ARRAY -> writeArray((ArrayNode) node, out, path, isDecimalField);
      case STRING -> {
        // A decimal field already carrying a string still gets normalised, so that "212500.00" and
        // 212500 agree whichever way the caller sent it.
        String text = node.textValue();
        if (isDecimalField.test(path)) {
          writeString(normalizeDecimal(text, path), out);
        } else {
          writeString(text, out);
        }
      }
      case NUMBER -> {
        if (isDecimalField.test(path)) {
          writeString(normalizeDecimal(node.asText(), path), out);
        } else {
          writeInteger(node, out, path);
        }
      }
      case BOOLEAN -> out.append(node.booleanValue() ? "true" : "false");
      case NULL -> out.append("null");
      default ->
          throw new PsfException(
              PsfException.Code.CANON_001, path, "unsupported node type " + node.getNodeType());
    }
  }

  private static String normalizeDecimal(String text, String path) {
    try {
      BigDecimal stripped = new BigDecimal(text).stripTrailingZeros();
      // stripTrailingZeros leaves BigDecimal.ZERO as 0E-N on some inputs ("0.00" -> 0E-2), whose
      // toPlainString is "0.00" again. Normalise zero explicitly.
      return stripped.signum() == 0 ? "0" : stripped.toPlainString();
    } catch (NumberFormatException e) {
      // The offending text is a fact value and never appears in the message (§9.6). The path
      // names the field, which is what anyone diagnosing this needs.
      throw new PsfException(PsfException.Code.CANON_001, path, "not a decimal value");
    }
  }

  private static void writeObject(
      ObjectNode node, StringBuilder out, String path, Predicate<String> isDecimalField) {
    // RFC 8785 orders members by the UTF-16 code units of their names. String.compareTo compares
    // char by char, and a Java char *is* a UTF-16 code unit, so this is the specified order and
    // not an approximation of it.
    List<Map.Entry<String, JsonNode>> members = new ArrayList<>(node.properties());
    members.sort(Map.Entry.comparingByKey());

    out.append('{');
    boolean first = true;
    for (Map.Entry<String, JsonNode> member : members) {
      if (!first) {
        out.append(',');
      }
      first = false;
      writeString(member.getKey(), out);
      out.append(':');
      String child = path.isEmpty() ? member.getKey() : path + "." + member.getKey();
      if (isDecimalField == null) {
        write(member.getValue(), out, child);
      } else {
        writeFacts(member.getValue(), out, child, isDecimalField);
      }
    }
    out.append('}');
  }

  private static void writeArray(
      ArrayNode node, StringBuilder out, String path, Predicate<String> isDecimalField) {
    out.append('[');
    for (int i = 0; i < node.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      // Fact paths address list elements with "[]" rather than an index: the fact schema types a
      // field, not a position, so member.bureauReports[0].score and [1].score share one type.
      String child = isDecimalField == null ? path + "[" + i + "]" : path + "[]";
      if (isDecimalField == null) {
        write(node.get(i), out, child);
      } else {
        writeFacts(node.get(i), out, child, isDecimalField);
      }
    }
    out.append(']');
  }

  private static void writeInteger(JsonNode node, StringBuilder out, String path) {
    if (!node.canConvertToExactIntegral()) {
      throw new PsfException(
          PsfException.Code.CANON_001,
          path,
          "JSON numbers in a canonical document must be integers; decimals travel as strings");
    }
    // bigIntegerValue() rather than longValue(): a value past Long.MAX_VALUE must not wrap round
    // into a different, valid-looking integer. toString gives base 10, no exponent, no leading
    // zeros, and a '-' only where the value is negative (scorecard points go to -100000).
    out.append(node.bigIntegerValue().toString());
  }

  private static void writeString(String text, StringBuilder out) {
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            // RFC 8785: the remaining control characters use a 6-character escape, lowercase hex.
            // (Spelling it out rather than writing the escape itself: javac decodes unicode
            // escapes before it lexes, comments included, so the literal form would not compile.)
            out.append("\\u00");
            out.append(Character.forDigit((c >> 4) & 0xF, 16));
            out.append(Character.forDigit(c & 0xF, 16));
          } else {
            // Everything else, including every non-ASCII character and lone surrogates, is emitted
            // literally and carried by the UTF-8 encoding. RFC 8785 escapes nothing further.
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  private static byte[] digest(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required of every Java platform", e);
    }
  }

  private static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      out.append(Character.forDigit((b >> 4) & 0xF, 16));
      out.append(Character.forDigit(b & 0xF, 16));
    }
    return out.toString();
  }
}
