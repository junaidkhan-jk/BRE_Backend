package dev.bre.facts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Splits a facts document by {@code x-bre-group}, so each half can be sealed under its own key (LLD
 * §9.2).
 *
 * <p>Two outputs:
 *
 * <ul>
 *   <li>the <b>non-identifying</b> document — amounts, scores, counts, dates of events — which is
 *       re-sealed under the tenant's TDK;
 *   <li>the <b>identifying</b> fields, grouped by data principal, each group sealed under that
 *       principal's own DPK.
 * </ul>
 *
 * <p><b>Why the split exists at all, rather than one envelope for everything.</b> Erasure. §9.5
 * destroys one person's key and expects every copy of <em>that person's</em> identifying data to
 * become unreadable — in the hot store, in the WORM archive, in backups — while everything else
 * about the decision stays intact and auditable. With one envelope per decision, erasing a person
 * would mean either rewriting immutable archive objects (impossible) or destroying the whole
 * decision record (unlawful: a lender must retain the lending decision). The split is what makes
 * both obligations satisfiable at once: the decision survives, the person's identity does not.
 *
 * <p><b>The key is {@code principalRef}, falling back to {@code partyRef}</b> (§9.2). {@code
 * principalRef} is the stable identity of a person across applications, which is what an erasure
 * request names; {@code partyRef} is this application's local reference. Falling back matters
 * because a snapshot without {@code principalRef} would otherwise have its identifying fields
 * grouped under one empty key — every applicant in one bucket, and erasing one erasing all of them.
 * Two parties that are the same person share a {@code principalRef} and therefore share a key,
 * which is the point: erasing that person erases both.
 *
 * <p><b>{@code partyRef} stays in the non-identifying half, and that is a deliberate deviation.</b>
 * The schema tags it {@code identifying}, so a literal reading would seal it under the DPK — and
 * then the two halves could not be rejoined at all, because nothing left in the non-identifying
 * half would say which party each group belonged to. Keeping it protects nothing that is not
 * already unprotected: every per-entity result in {@code results_envelope} carries the same {@code
 * partyRef} as its {@code entityRef} (§4.5), and that envelope is TDK-sealed, not DPK-sealed. So
 * excluding {@code partyRef} here would break the join while leaving the value exactly as exposed
 * as it was. {@code principalRef} — the <em>cross-application</em> identity, which is the one worth
 * protecting — goes into the DPK group as tagged. Recorded as an open question against §9.2, which
 * does not address the join.
 *
 * <p><b>An identifying field outside any party is a defect, not a case to handle.</b> If the schema
 * tags something {@code identifying} at a path with no enclosing party — a group's name, say —
 * there is no principal to key it to, and silently putting it in the non-identifying half would
 * mean personal data sealed under a key that is never destroyed. {@link #split} reports those paths
 * in {@link Split#unassignable} so the caller can refuse rather than guess; the schema should then
 * be corrected.
 */
public final class FieldGroupSplitter {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The fact roots whose elements are data principals (§3.8). */
  private static final String PARTIES = "parties";

  private final FactSchema schema;

  /**
   * The result of a split.
   *
   * @param nonIdentifying the document with every identifying field removed; sealed under the TDK
   * @param identifying the identifying fields, by {@code partyRef} — one entry per party that had
   *     any, and {@code partyRef} is the key because it is what survives in the other half to
   *     rejoin against
   * @param principalOf which data principal each party's group must be sealed under, by {@code
   *     partyRef}. Separate from the map above because the two answer different questions: that one
   *     is "what to seal", this one is "whose key to seal it with" — and two parties who are the
   *     same person map to one principal, which is how erasing that person erases both.
   * @param unassignable identifying paths that belonged to no party — a schema defect, and the
   *     caller should refuse the decision rather than seal them under the wrong key
   */
  public record Split(
      JsonNode nonIdentifying,
      Map<String, JsonNode> identifying,
      Map<String, String> principalOf,
      java.util.List<String> unassignable) {

    /** Creates a split, copying the collections. */
    public Split {
      identifying = Map.copyOf(identifying);
      principalOf = Map.copyOf(principalOf);
      unassignable = java.util.List.copyOf(unassignable);
    }
  }

  /**
   * Creates a splitter over a schema.
   *
   * @param schema the fact schema, which carries the {@code x-bre-group} tags
   */
  public FieldGroupSplitter(FactSchema schema) {
    this.schema = schema;
  }

  /**
   * Splits a facts document.
   *
   * @param facts the facts
   * @return the two halves, and any identifying path that could not be assigned to a principal
   */
  public Split split(JsonNode facts) {
    ObjectNode nonIdentifying = MAPPER.createObjectNode();
    Map<String, JsonNode> identifying = new LinkedHashMap<>();
    Map<String, String> principalOf = new LinkedHashMap<>();
    java.util.List<String> unassignable = new java.util.ArrayList<>();

    if (facts == null || !facts.isObject()) {
      return new Split(nonIdentifying, Map.of(), Map.of(), java.util.List.of());
    }

    facts
        .properties()
        .forEach(
            root -> {
              if (PARTIES.equals(root.getKey()) && root.getValue().isArray()) {
                // The one root whose elements carry their own identity, so it is the one root
                // where the split can produce per-principal groups.
                ArrayNode keptParties = nonIdentifying.putArray(PARTIES);
                for (JsonNode party : root.getValue()) {
                  String partyRef = textOf(party, "partyRef");
                  String principal = principalOf(party);
                  ObjectNode kept = MAPPER.createObjectNode();
                  ObjectNode taken = MAPPER.createObjectNode();
                  partition(party, "parties[]", kept, taken, unassignable, partyRef != null);
                  // The join key, put back after partitioning took it: see the class comment.
                  if (partyRef != null) {
                    kept.put("partyRef", partyRef);
                  }
                  keptParties.add(kept);
                  if (partyRef != null && !taken.isEmpty()) {
                    identifying.put(partyRef, taken);
                    principalOf.put(partyRef, principal);
                  }
                }
                return;
              }

              // Every other root. There is no principal to key an identifying field to here, so
              // partition reports any it finds as unassignable rather than placing it.
              JsonNode value = root.getValue();
              if (value != null && value.isObject()) {
                ObjectNode kept = MAPPER.createObjectNode();
                partition(value, root.getKey(), kept, null, unassignable, false);
                nonIdentifying.set(root.getKey(), kept);
                return;
              }
              if (value != null && value.isArray()) {
                nonIdentifying.set(root.getKey(), reassemble(value, root.getKey(), unassignable));
                return;
              }
              if (isIdentifying(root.getKey())) {
                unassignable.add(root.getKey());
                return;
              }
              nonIdentifying.set(root.getKey(), value);
            });

    return new Split(nonIdentifying, identifying, principalOf, unassignable);
  }

  /**
   * Puts a party's identifying fields back, given the per-principal groups.
   *
   * <p>The inverse of {@link #split}, for a reader that holds both envelopes. A principal whose key
   * has been erased simply has no group, and its fields stay absent — which is what makes an
   * explanation able to say the value was shredded rather than failing to render at all.
   *
   * @param nonIdentifying the TDK half
   * @param identifying the groups that could be opened, by {@code partyRef}; a party whose group is
   *     absent has been erased, and that is a normal outcome rather than an error
   * @return the rejoined document
   */
  public JsonNode rejoin(JsonNode nonIdentifying, Map<String, JsonNode> identifying) {
    ObjectNode out = nonIdentifying.deepCopy();
    JsonNode parties = out.get(PARTIES);
    if (parties == null || !parties.isArray()) {
      return out;
    }
    for (JsonNode party : parties) {
      String partyRef = textOf(party, "partyRef");
      JsonNode group = partyRef == null ? null : identifying.get(partyRef);
      if (group != null && party.isObject() && group.isObject()) {
        // Deep, not setAll. The split takes `income.monthlyNet` and leaves `income.verified`, so
        // each half holds a PARTIAL `income` object — and a shallow put would replace the kept
        // half with the taken half and silently lose every non-identifying sibling. Found by
        // round-tripping a document rather than by inspecting the split, which looked correct.
        merge((ObjectNode) party, (ObjectNode) group);
      }
    }
    return out;
  }

  /** Recursively copies {@code from} into {@code into}, descending into objects present in both. */
  private static void merge(ObjectNode into, ObjectNode from) {
    from.properties()
        .forEach(
            entry -> {
              JsonNode existing = into.get(entry.getKey());
              if (existing != null
                  && existing.isObject()
                  && entry.getValue() != null
                  && entry.getValue().isObject()) {
                merge((ObjectNode) existing, (ObjectNode) entry.getValue());
              } else {
                into.set(entry.getKey(), entry.getValue());
              }
            });
  }

  // ---------------------------------------------------------------------------------------------

  /**
   * Moves identifying leaves from {@code node} into {@code taken}; the rest go into {@code kept}.
   *
   * @param taken where identifying fields go; null when there is no principal in scope, in which
   *     case {@code hasPrincipal} is false and nothing is ever written to it
   */
  private void partition(
      JsonNode node,
      String path,
      ObjectNode kept,
      ObjectNode taken,
      java.util.List<String> unassignable,
      boolean hasPrincipal) {

    if (!node.isObject()) {
      return;
    }
    node.properties()
        .forEach(
            entry -> {
              String childPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
              JsonNode value = entry.getValue();

              if (value != null && value.isObject()) {
                ObjectNode nestedKept = MAPPER.createObjectNode();
                ObjectNode nestedTaken = MAPPER.createObjectNode();
                partition(value, childPath, nestedKept, nestedTaken, unassignable, hasPrincipal);
                kept.set(entry.getKey(), nestedKept);
                if (!nestedTaken.isEmpty()) {
                  taken.set(entry.getKey(), nestedTaken);
                }
                return;
              }
              if (value != null && value.isArray()) {
                kept.set(entry.getKey(), reassemble(value, childPath, unassignable));
                return;
              }
              if (isIdentifying(childPath)) {
                if (!hasPrincipal) {
                  unassignable.add(childPath);
                  return;
                }
                taken.set(entry.getKey(), value);
                return;
              }
              kept.set(entry.getKey(), value);
            });
  }

  /**
   * Walks an array, keeping non-identifying content.
   *
   * <p>ponytail: an identifying field nested inside an array <i>below</i> a party — a tradeline's
   * holder name, say — is reported as unassignable rather than grouped. The canonical schema has
   * none, so this is a guard rather than a gap; implementing it needs §9.2 to say which principal
   * such a field belongs to, which it does not.
   */
  private JsonNode reassemble(JsonNode array, String path, java.util.List<String> unassignable) {
    ArrayNode out = MAPPER.createArrayNode();
    String elementPath = path + "[]";
    for (JsonNode element : array) {
      if (element != null && element.isObject()) {
        ObjectNode kept = MAPPER.createObjectNode();
        partition(element, elementPath, kept, null, unassignable, false);
        out.add(kept);
      } else {
        if (isIdentifying(elementPath)) {
          unassignable.add(elementPath);
          continue;
        }
        out.add(element);
      }
    }
    return out;
  }

  private boolean isIdentifying(String path) {
    return schema
        .field(path)
        .map(field -> field.group() == FactSchema.Group.IDENTIFYING)
        .orElse(false);
  }

  /** The DPK a party's identifying fields belong under: principalRef, else partyRef (§9.2). */
  private static String principalOf(JsonNode party) {
    String principal = textOf(party, "principalRef");
    return principal != null ? principal : textOf(party, "partyRef");
  }

  private static String textOf(JsonNode node, String field) {
    if (node == null || !node.isObject()) {
      return null;
    }
    JsonNode value = node.get(field);
    return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
  }
}
