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
 * <p><b>Three kinds of identifying field live outside a party, and they are not one problem</b>
 * (Q10). §9.2 groups identifying fields by {@code principalRef} falling back to {@code partyRef},
 * and four fields in the published schema have neither. Treating them alike is why there appeared
 * to be no good answer:
 *
 * <ul>
 *   <li><b>A pseudonymous reference</b> — {@code application.applicationRef}. It identifies an
 *       application, not a person, and the decision record already carries the same value
 *       TDK-sealed as its own reference. Sealing it under a DPK would break the join between the
 *       halves while leaving the value exactly as exposed, which is the identical argument that
 *       keeps {@code partyRef} in the non-identifying half (Q9). It is a <b>join key</b>.
 *   <li><b>Data about a household</b> — {@code annualIncome} and {@code
 *       monthlyRepaymentObligations}. This is financial data about a set of natural persons, and a
 *       household is not an individual, so there is no single DPK. The resolution is that <b>a
 *       household is a data principal in its own right</b>, keyed by {@code householdRef} — which
 *       the schema already leaves non-identifying, so it survives in the other half and can serve
 *       as the join key, exactly as {@code partyRef} does for a party. Mechanically it is a party:
 *       its own DPK, its own envelope, its own erasure. The erasure <em>policy</em> is the
 *       operator's: a household's key is destroyed when every member has been shredded, because the
 *       aggregate is joint data and one member's withdrawal must not destroy four others' decision
 *       record.
 *   <li><b>The lender's own employee</b> — {@code deviationApprovals[].approvedBy}. Tagging this
 *       {@code identifying} is a <b>schema error</b>. It is the maker-checker trail: the person who
 *       authorised a deviation. Sealing it under a borrower's DPK would mean a borrower's erasure
 *       request destroys the lender's approval record, which a regulator would read as destruction
 *       of evidence — and it is not the borrower's personal data in any case. It is the employee's,
 *       held on the lender's own basis and retained for audit. It stays in the non-identifying half
 *       and keeps its {@code pii} tag, so §9.4 still masks it in a customer-facing explanation.
 * </ul>
 *
 * <p>The right long-term home for all three is the schema itself, tagging them distinctly — but
 * {@code lending-facts@1.0.0} is published and registered per tenant with a content hash, so
 * changing its tags in place would make two installations disagree about what version 1.0.0 says.
 * That is a schema version bump, and it belongs with fact-schema management (M3-08). Until then
 * this class is the single place the rule lives, which is where Q9's deviation already is.
 *
 * <p><b>Anything still unassignable is a defect, not a case to handle.</b> If the schema tags
 * something {@code identifying} at a path with no enclosing party and no enclosing household, there
 * is no principal to key it to, and silently putting it in the non-identifying half would mean
 * personal data sealed under a key that is never destroyed. {@link #split} reports those paths in
 * {@link Split#unassignable} so the caller can refuse rather than guess; the schema should then be
 * corrected.
 */
public final class FieldGroupSplitter {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The fact roots whose elements are data principals (§3.8). */
  private static final String PARTIES = "parties";

  private static final String HOUSEHOLDS = "households";

  /**
   * The prefix a household's principal id carries.
   *
   * <p>A household ref and a party ref are drawn from different namespaces in a caller's system and
   * could collide — {@code H-1} is a perfectly possible party ref. Without the prefix, one
   * household's income could be sealed under a party's key, and shredding that party would destroy
   * the household's data or, worse, leave it readable under a key that was meant to be gone.
   */
  public static final String HOUSEHOLD_PRINCIPAL_PREFIX = "household:";

  /**
   * Identifying paths that are pseudonymous REFERENCES rather than personal attributes.
   *
   * <p>Each stays in the non-identifying half as a join key. The value is already TDK-sealed
   * elsewhere in the decision record, so removing it here would break the join while leaving the
   * value exactly as exposed. Crypto-shredding destroys the attributes a reference points at, which
   * is what makes the reference meaningless — that is what pseudonymisation is, and §9.5 already
   * relies on it for every {@code entityRef}.
   */
  private static final java.util.Set<String> JOIN_KEYS =
      java.util.Set.of(
          "application.applicationRef",
          "parties[].partyRef",
          // The MEMBERSHIP lists, and leaving them out was a defect with a large blast radius.
          //
          // Each element is a partyRef — the same value the line above keeps, for the same reason:
          // it is already TDK-sealed as an entityRef elsewhere in the record, so dropping it
          // protects nothing and destroys the join. Tagging one partyRef as a join key and a LIST
          // of the same partyRefs as a personal attribute cannot both be right.
          //
          // What it actually cost: group lending decides on the GROUP. With these dropped, a
          // replayed five-member JLG had an empty membership, GRP_JLG_SIZE failed closed, and the
          // replay returned DECLINE against a recorded APPROVE — so `matched` was false and the
          // explanation endpoint could not explain a single decision whose facts had been stored.
          // An explanation that contradicts the decision is worse than no explanation: §9.4 exists
          // because a borrower and a regulator are entitled to know why, and this answered "we
          // would decline you today" about an approval.
          "group.memberPartyRefs[]",
          "households[].memberPartyRefs[]");

  /**
   * Identifying paths that belong to the LENDER rather than to a data principal.
   *
   * <p>Retained in the non-identifying half, and retained deliberately: this is the maker-checker
   * trail, and a borrower's erasure request must not destroy the record of who authorised a
   * deviation on their application. The {@code pii} tag still applies, so §9.4 masks it.
   */
  private static final java.util.Set<String> LENDER_RECORDS =
      java.util.Set.of("deviationApprovals[].approvedBy");

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

              if (HOUSEHOLDS.equals(root.getKey()) && root.getValue().isArray()) {
                // A household is a data principal of its own (Q10): householdRef is its identity,
                // it is already non-identifying so it survives as the join key, and its income
                // gets its own envelope under its own key — mechanically a party.
                ArrayNode keptHouseholds = nonIdentifying.putArray(HOUSEHOLDS);
                for (JsonNode household : root.getValue()) {
                  String householdRef = textOf(household, "householdRef");
                  ObjectNode kept = MAPPER.createObjectNode();
                  ObjectNode taken = MAPPER.createObjectNode();
                  partition(
                      household, "households[]", kept, taken, unassignable, householdRef != null);
                  if (householdRef != null) {
                    kept.put("householdRef", householdRef);
                  }
                  keptHouseholds.add(kept);
                  if (householdRef != null && !taken.isEmpty()) {
                    String principal = HOUSEHOLD_PRINCIPAL_PREFIX + householdRef;
                    identifying.put(principal, taken);
                    principalOf.put(principal, principal);
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
              if (isPrincipalData(root.getKey())) {
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
    rejoinEach(out.get(PARTIES), identifying, "partyRef", "");
    // Households too, and forgetting this is how household income came back absent from a replay
    // even after the split started sealing it: the inverse has to know about every root the
    // split treats as a principal, or the data is sealed correctly and never read again.
    rejoinEach(out.get(HOUSEHOLDS), identifying, "householdRef", HOUSEHOLD_PRINCIPAL_PREFIX);
    return out;
  }

  /** Merges each element's group back, keyed by {@code refField} under an optional prefix. */
  private static void rejoinEach(
      JsonNode elements, Map<String, JsonNode> identifying, String refField, String prefix) {
    if (elements == null || !elements.isArray()) {
      return;
    }
    for (JsonNode element : elements) {
      String ref = textOf(element, refField);
      JsonNode group = ref == null ? null : identifying.get(prefix + ref);
      if (group != null && element.isObject() && group.isObject()) {
        // Deep, not setAll. The split takes `income.monthlyNet` and leaves `income.verified`, so
        // each half holds a PARTIAL `income` object — and a shallow put would replace the kept
        // half with the taken half and silently lose every non-identifying sibling. Found by
        // round-tripping a document rather than by inspecting the split, which looked correct.
        merge((ObjectNode) element, (ObjectNode) group);
      }
    }
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
              if (isPrincipalData(childPath)) {
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
        if (isPrincipalData(elementPath)) {
          unassignable.add(elementPath);
          continue;
        }
        out.add(element);
      }
    }
    return out;
  }

  /**
   * Whether a path's identifying tag means "seal under a data principal's key".
   *
   * <p>Three kinds of path are tagged identifying and must NOT be: a join key, whose value is
   * already TDK-sealed elsewhere in the record; and the lender's own maker-checker trail, which a
   * borrower's erasure must not destroy. See the class comment for why each (Q10).
   */
  private boolean isPrincipalData(String path) {
    return isIdentifying(path) && !JOIN_KEYS.contains(path) && !LENDER_RECORDS.contains(path);
  }

  /**
   * Whether a field inside a data principal's record identifies them.
   *
   * <p><b>An UNKNOWN field counts as identifying, and that is the whole point of this method.</b>
   * The schema sets {@code additionalProperties: true}, so a caller may send a field nobody
   * declared — and the obvious way to do it is a near miss on a real one. The schema's PAN field is
   * {@code panToken}; a caller sending {@code pan} passes validation.
   *
   * <p>With the previous {@code orElse(false)} that field was NON_IDENTIFYING, so it was sealed
   * under the TENANT key — outside the erasure mechanism entirely. Destroying the principal's key
   * then left the PAN perfectly readable, while the party was reported as shredded and the lender
   * believed the person had been erased. Fail-open, in a system whose entire posture is D9
   * fail-closed, on the one operation that cannot be repeated once it is believed done.
   *
   * <p>Unknown now means identifying. The cost of being wrong in this direction is that a field
   * which did not need a principal key gets one and becomes unreadable after an erasure that did
   * not have to touch it. The cost of being wrong in the other direction is telling a data
   * principal and a regulator that their data is gone when it is not.
   *
   * <p>Only inside a principal's record. An unknown field at the top level, or under {@code
   * application}, has no principal to belong to and is handled by the caller.
   *
   * @param path the field path
   * @return true when it must be sealed under the data principal's key
   */
  private boolean isIdentifying(String path) {
    return schema
        .field(path)
        .map(field -> field.group() == FactSchema.Group.IDENTIFYING)
        .orElse(true);
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
