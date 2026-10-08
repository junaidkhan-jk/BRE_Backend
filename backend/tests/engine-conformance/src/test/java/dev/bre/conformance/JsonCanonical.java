package dev.bre.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Normalises a JSON tree so that two trees meaning the same thing compare equal.
 *
 * <p>Two Jackson details would otherwise make the conformance suite lie:
 *
 * <ul>
 *   <li>{@code IntNode(0)} and {@code LongNode(0)} are <b>not</b> equal, so a vector written as
 *       {@code 0} never matched an engine result carried as a {@code long}. Every number is
 *       rewritten to a {@link DecimalNode} here.
 *   <li>Object field order is significant to {@code equals}. Fields are sorted.
 * </ul>
 *
 * <p>Normalising is safe for this suite because a vector's {@code expect} is a small typed
 * envelope, never free-form JSON whose key order carries meaning.
 */
final class JsonCanonical {

  private JsonCanonical() {}

  /**
   * Returns an equivalent tree in canonical form.
   *
   * @param node the tree to normalise; may be null
   * @return the normalised tree
   */
  static JsonNode normalize(JsonNode node) {
    if (node == null) {
      return JsonNodeFactory.instance.nullNode();
    }
    if (node.isNumber()) {
      return DecimalNode.valueOf(node.decimalValue().stripTrailingZeros());
    }
    if (node.isArray()) {
      ArrayNode array = JsonNodeFactory.instance.arrayNode(node.size());
      node.forEach(element -> array.add(normalize(element)));
      return array;
    }
    if (node.isObject()) {
      List<String> names = new ArrayList<>();
      node.fieldNames().forEachRemaining(names::add);
      names.sort(String::compareTo);
      ObjectNode object = JsonNodeFactory.instance.objectNode();
      names.forEach(name -> object.set(name, normalize(node.get(name))));
      return object;
    }
    return node;
  }
}
