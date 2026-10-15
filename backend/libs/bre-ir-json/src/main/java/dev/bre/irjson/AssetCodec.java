package dev.bre.irjson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.bre.engine.eval.ParameterTable;
import dev.bre.engine.eval.Value;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolved assets: the {@code param.} and {@code list.} namespaces, in and out of a bundle (§8.4).
 *
 * <p><b>Why assets live inside the bundle.</b> A policy references its parameter set and reference
 * lists by name and version ({@code MFI_PARAMS@1}), and until this existed the decision service
 * passed {@code Map.of()} for both — so every {@code param.X} was Missing, every rule's {@code
 * onMissing} fired, and the reference policy returned REFER for every applicant. The policy was
 * being evaluated; it just had nothing to evaluate against.
 *
 * <p>They are <b>resolved at bundle time, not at decision time</b>, and that is the whole design.
 * D2 makes a decision a pure function of {@code (facts, policy, asOf)}: if a parameter were fetched
 * live, two replays of the same decision could differ because somebody edited a parameter in
 * between, and the bundle hash would no longer identify what was actually evaluated. Resolving them
 * into the signed, content-addressed bundle makes a parameter change a new bundle with a new hash —
 * visible, deployable, and revertable like any other policy change.
 *
 * <p><b>Every value carries its type explicitly.</b> A parameter written {@code 300000} in YAML is
 * ambiguous between Integer and Decimal, and §3.6's decimal semantics are not the same as integer
 * semantics — a threshold compared with the wrong one is a defect that only shows up at a boundary.
 * Decimals are carried as strings for the reason BRE-JCS carries them as strings: a double cannot
 * represent {@code 0.1} and a parameter that drifts in the seventh decimal place is a parameter
 * that produces a different decision from the one the author approved.
 */
public final class AssetCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private AssetCodec() {}

  /**
   * Reads the {@code param.} namespace from a bundle's resolved assets.
   *
   * @param assets the bundle's {@code assets} member; may be null or empty
   * @return the parameters, by name; empty when the bundle carries none
   * @throws PsfException if a declared value is not well-formed for its type
   */
  public static ParameterTable parameters(JsonNode assets) {
    if (assets == null) {
      return ParameterTable.EMPTY;
    }
    Map<String, List<ParameterTable.Window>> out = new LinkedHashMap<>();
    JsonNode parameters = assets.path("parameters");
    parameters
        .fieldNames()
        .forEachRemaining(
            name -> out.put(name, windows(parameters.get(name), "assets.parameters." + name)));
    return ParameterTable.dated(out);
  }

  /**
   * A parameter's effective windows, from either declared form.
   *
   * <p>Two forms are accepted and they mean different things:
   *
   * <ul>
   *   <li>{@code {type, value}} — one value, open-ended. The honest reading of a parameter declared
   *       without dates: it has always applied and still does.
   *   <li>{@code {type, values: [{effectiveFrom, effectiveTo, value}]}} — §8.4's form, which is
   *       what the control plane produces and what a bundle carries. {@code effectiveTo} is
   *       <b>exclusive</b> and null means open-ended.
   * </ul>
   *
   * <p>Both are validated here rather than at decision time, because a bundle is signed: a
   * malformed parameter caught at build time is a build failure, and caught later it is a signed
   * artefact nobody can serve.
   */
  private static List<ParameterTable.Window> windows(JsonNode declared, String where) {
    if (declared == null || !declared.isObject()) {
      throw new PsfException(
          PsfException.Code.ASSET_002,
          where,
          "must be an object of the form {type, value} or {type, values:[…]}");
    }
    String type = text(declared, "type", where);
    JsonNode values = declared.get("values");
    if (values == null || values.isNull()) {
      return List.of(
          new ParameterTable.Window(
              LocalDate.MIN, Optional.empty(), scalar(type, declared.path("value"), where)));
    }
    if (!values.isArray() || values.isEmpty()) {
      throw new PsfException(
          PsfException.Code.ASSET_002,
          where + ".values",
          "must be a non-empty array of {effectiveFrom, effectiveTo, value}. A parameter with no"
              + " effective value anywhere is a hole in the parameter set, not a missing fact.");
    }
    List<ParameterTable.Window> out = new ArrayList<>(values.size());
    for (int i = 0; i < values.size(); i++) {
      JsonNode window = values.get(i);
      String at = where + ".values[" + i + "]";
      out.add(
          new ParameterTable.Window(
              date(window, "effectiveFrom", at, LocalDate.MIN),
              Optional.ofNullable(window.get("effectiveTo"))
                  .filter(node -> !node.isNull())
                  .map(node -> date(window, "effectiveTo", at, null)),
              scalar(type, window.path("value"), at)));
    }
    // Sorted so valueAt scans in date order, which makes the first covering window the right one
    // even if the source listed them out of order.
    out.sort(java.util.Comparator.comparing(ParameterTable.Window::effectiveFrom));
    return List.copyOf(out);
  }

  private static LocalDate date(JsonNode node, String field, String where, LocalDate absent) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      if (absent != null) {
        return absent;
      }
      throw new PsfException(PsfException.Code.ASSET_002, where + "." + field, "is required");
    }
    if (!value.isTextual()) {
      throw new PsfException(
          PsfException.Code.ASSET_002, where + "." + field, "must be an ISO date string");
    }
    try {
      return LocalDate.parse(value.asText());
    } catch (java.time.format.DateTimeParseException notADate) {
      throw new PsfException(
          PsfException.Code.ASSET_002,
          where + "." + field,
          "is not an ISO date: " + value.asText());
    }
  }

  /**
   * Reads the {@code list.} namespace from a bundle's resolved assets.
   *
   * <p>Reference lists are {@code ListV}s of a single declared element type. A list whose elements
   * are not all that type is rejected here rather than at evaluation: a filter against a mixed list
   * fails closed (§3.9), which is safe but reports a data problem as a credit outcome.
   *
   * @param assets the bundle's {@code assets} member; may be null or empty
   * @return the lists, by name
   * @throws PsfException if a list is not well-formed
   */
  public static Map<String, Value> lists(JsonNode assets) {
    Map<String, Value> out = new LinkedHashMap<>();
    if (assets == null) {
      return out;
    }
    JsonNode lists = assets.path("lists");
    lists
        .fieldNames()
        .forEachRemaining(
            name -> {
              JsonNode declared = lists.get(name);
              String where = "assets.lists." + name;
              String type = text(declared, "type", where);
              JsonNode values = declared.path("values");
              if (!values.isArray()) {
                throw new PsfException(
                    PsfException.Code.ASSET_002, where + ".values", "must be an array");
              }
              List<Value> elements = new ArrayList<>(values.size());
              for (int i = 0; i < values.size(); i++) {
                elements.add(scalar(type, values.get(i), where + "[" + i + "]"));
              }
              out.put(name, new Value.ListV(elements));
            });
    return out;
  }

  /**
   * Resolves an asset source document into the {@code assets} member of a bundle.
   *
   * <p>Picks the one parameter set and the reference lists the policy's {@code assets} block names,
   * and drops everything else the source happens to define. A bundle carrying parameter sets the
   * policy does not use would make its hash depend on data that cannot affect any decision it
   * takes, so two bundles with identical behaviour would have different addresses.
   *
   * <p>The source is <b>JSON, not YAML</b>. Every value in it carries an explicit type and every
   * decimal is a quoted string, so YAML's scalar inference — the thing PSF-002 exists to defend
   * against — would buy nothing here and add a second parser to the signing path. In production
   * these come from the registry's parameter tables (§5.2); this file is the offline equivalent.
   *
   * @param source the asset source, keying its parameter sets and reference lists by {@code
   *     name@version}
   * @param parameterSetRef the set the policy names, e.g. {@code MFI_PARAMS@1}; may be null
   * @param referenceListRefs the lists the policy names
   * @return the resolved {@code assets} node
   * @throws PsfException if a named set or list is absent from the source
   */
  public static ObjectNode resolve(
      JsonNode source, String parameterSetRef, List<String> referenceListRefs) {
    ObjectNode assets = MAPPER.createObjectNode();
    ObjectNode parameters = assets.putObject("parameters");
    ObjectNode lists = assets.putObject("lists");

    if (parameterSetRef != null && !parameterSetRef.isBlank()) {
      JsonNode set = source.path("parameterSets").get(parameterSetRef);
      if (set == null || !set.isObject()) {
        throw new PsfException(
            PsfException.Code.ASSET_001,
            "parameterSets." + parameterSetRef,
            "the policy names this parameter set but the asset source does not define it; it"
                + " defines: "
                + names(source.path("parameterSets")));
      }
      set.fieldNames()
          .forEachRemaining(
              name -> {
                JsonNode declared = set.get(name);
                String where = "parameterSets." + parameterSetRef + "." + name;
                // Validated on the way in, not only on the way out: a bundle is signed, so a
                // malformed parameter caught here is a build failure and caught later is a
                // signed artefact nobody can serve. Accepts both the undated and the
                // effective-dated form (§8.4).
                windows(declared, where);
                parameters.set(name, declared.deepCopy());
              });
    }

    for (String ref : referenceListRefs) {
      JsonNode declared = source.path("referenceLists").get(ref);
      if (declared == null || !declared.isObject()) {
        throw new PsfException(
            PsfException.Code.ASSET_001,
            "referenceLists." + ref,
            "the policy names this reference list but the asset source does not define it; it"
                + " defines: "
                + names(source.path("referenceLists")));
      }
      String where = "referenceLists." + ref;
      String type = text(declared, "type", where);
      JsonNode values = declared.path("values");
      if (!values.isArray()) {
        throw new PsfException(PsfException.Code.ASSET_002, where + ".values", "must be an array");
      }
      for (int i = 0; i < values.size(); i++) {
        scalar(type, values.get(i), where + "[" + i + "]");
      }
      // Keyed WITHOUT the version. A policy writes `list.UNSECURED_ACCOUNT_TYPES`; the version is
      // part of what was resolved, not part of the name the expression uses. The full ref stays in
      // the policy IR's assets block, so the bundle still records which version went in.
      ObjectNode entry = lists.putObject(unversioned(ref));
      entry.put("type", type);
      entry.set("values", values.deepCopy());
    }
    return assets;
  }

  /**
   * Resolves the assets a policy IR declares, from an asset source document.
   *
   * <p>Reads the refs out of the IR's own {@code assets} block — {@code {code, version}} pairs, as
   * {@link PsfCodec} writes them — rather than taking them as arguments, so a bundle cannot be
   * built against a different parameter set from the one the policy names.
   *
   * @param source the asset source document
   * @param irAssets the IR's {@code assets} member
   * @return the resolved {@code assets} node for the bundle
   * @throws PsfException if a named asset is absent or ill-formed
   */
  public static ObjectNode resolveForPolicy(JsonNode source, JsonNode irAssets) {
    JsonNode parameterSet = irAssets == null ? null : irAssets.get("parameterSet");
    String parameterSetRef =
        parameterSet == null || parameterSet.isNull() ? null : ref(parameterSet);
    List<String> listRefs = new ArrayList<>();
    if (irAssets != null) {
      for (JsonNode list : irAssets.path("referenceLists")) {
        listRefs.add(ref(list));
      }
    }
    return resolve(source, parameterSetRef, listRefs);
  }

  /**
   * Returns the resolved Authority Matrix a policy names, if the source defines one.
   *
   * <p>Unlike parameters and lists, this is substituted back into the <b>IR</b> rather than carried
   * in the bundle's {@code assets}: {@link IrCodec} decodes {@code assets.authorityMatrix} into the
   * matrix the engine reasons over, and {@link PsfCodec} can only write the {@code {code, version}}
   * reference it found in the PSF source. Left unresolved, the engine sees a matrix with no levels
   * and no entries — which is not "no matrix", it is a matrix under which nobody can approve
   * anything, so every deviation becomes {@code DEV_NO_AUTHORITY} and the policy's deviation bands
   * never apply (§4.9).
   *
   * @param source the asset source document
   * @param irAssets the IR's {@code assets} member
   * @return the matrix with its levels and entries, or empty when the policy names none
   * @throws PsfException if the policy names one the source does not define
   */
  public static Optional<ObjectNode> resolveAuthorityMatrix(JsonNode source, JsonNode irAssets) {
    JsonNode declared = irAssets == null ? null : irAssets.get("authorityMatrix");
    if (declared == null || declared.isNull()) {
      return Optional.empty();
    }
    String matrixRef = ref(declared);
    JsonNode defined = source.path("authorityMatrices").get(matrixRef);
    if (defined == null || !defined.isObject()) {
      throw new PsfException(
          PsfException.Code.ASSET_001,
          "authorityMatrices." + matrixRef,
          "the policy names this Authority Matrix but the asset source does not define it; it"
              + " defines: "
              + names(source.path("authorityMatrices")));
    }
    ObjectNode resolved = defined.deepCopy();
    // The code and version come from the policy's own reference, not from the source, so a matrix
    // filed under the wrong key cannot rename itself on the way in.
    resolved.put("code", unversioned(matrixRef));
    resolved.put("version", declared.path("version").asInt());
    if (!resolved.path("levels").isArray() || resolved.path("levels").isEmpty()) {
      throw new PsfException(
          PsfException.Code.ASSET_002,
          "authorityMatrices." + matrixRef + ".levels",
          "must declare at least one level; a matrix with no levels approves nothing");
    }
    return Optional.of(resolved);
  }

  private static String ref(JsonNode assetRef) {
    return assetRef.path("code").asText() + "@" + assetRef.path("version").asInt();
  }

  /** The name part of a {@code name@version} reference. */
  public static String unversioned(String ref) {
    int at = ref.indexOf('@');
    return at < 0 ? ref : ref.substring(0, at);
  }

  // ---------------------------------------------------------------------------------------------

  private static Value scalar(String type, JsonNode node, String where) {
    if (node == null || node.isNull()) {
      throw new PsfException(
          PsfException.Code.ASSET_002,
          where,
          "has no value. A parameter with no value is not the same as a missing fact: a policy"
              + " cannot be deployed with a hole in its parameter set.");
    }
    return switch (type) {
      case "Integer" -> {
        // A quoted integer is accepted as well as a bare one. §8.4 is explicit that a parameter
        // value in a bundle is ALWAYS a string parsed by its type — which is also how §5.2 stores
        // it, as text — while the offline asset file and the PSF examples write small integers
        // bare. Accepting only the bare form made every parameter set read out of the database
        // fail to resolve with "must be an integer for type Integer", against a value that was a
        // perfectly good integer in a string.
        if (node.isIntegralNumber()) {
          yield new Value.IntV(node.asLong());
        }
        if (!node.isTextual()) {
          throw new PsfException(
              PsfException.Code.ASSET_002, where, "must be an integer for type Integer");
        }
        try {
          yield new Value.IntV(Long.parseLong(node.asText().strip()));
        } catch (NumberFormatException notAnInteger) {
          throw new PsfException(
              PsfException.Code.ASSET_002,
              where,
              "is not an integer: " + node.asText(),
              notAnInteger);
        }
      }
      case "Decimal" -> {
        // Required to be a string. A YAML or JSON number has already been through a double by the
        // time it reaches here in some parsers, and 0.1 is not 0.1 once it has.
        if (!node.isTextual()) {
          throw new PsfException(
              PsfException.Code.ASSET_002,
              where,
              "must be a QUOTED string for type Decimal, so the exact value survives parsing;"
                  + " write \"300000.00\", not 300000.00");
        }
        try {
          yield new Value.DecimalV(new BigDecimal(node.asText()));
        } catch (NumberFormatException e) {
          throw new PsfException(PsfException.Code.ASSET_002, where, "is not a decimal", e);
        }
      }
      case "Boolean" -> {
        if (node.isBoolean()) {
          yield Value.BoolV.of(node.asBoolean());
        }
        // Same reason as Integer: the stored form is text. Only the two exact words, though —
        // treating anything else as false is how a switched-off control looks switched on.
        String text = node.isTextual() ? node.asText().strip() : "";
        if ("true".equalsIgnoreCase(text)) {
          yield Value.BoolV.of(true);
        }
        if ("false".equalsIgnoreCase(text)) {
          yield Value.BoolV.of(false);
        }
        throw new PsfException(
            PsfException.Code.ASSET_002, where, "must be true or false for type Boolean");
      }
      case "Text" -> {
        if (!node.isTextual()) {
          throw new PsfException(
              PsfException.Code.ASSET_002, where, "must be a string for type Text");
        }
        yield new Value.TextV(node.asText());
      }
      case "Date" -> {
        if (!node.isTextual()) {
          throw new PsfException(
              PsfException.Code.ASSET_002, where, "must be an ISO-8601 date string for type Date");
        }
        try {
          yield new Value.DateV(LocalDate.parse(node.asText()));
        } catch (RuntimeException e) {
          throw new PsfException(PsfException.Code.ASSET_002, where, "is not an ISO-8601 date", e);
        }
      }
      default ->
          throw new PsfException(
              PsfException.Code.ASSET_002,
              where,
              "declares an unknown type; a parameter or list element is one of Integer, Decimal,"
                  + " Boolean, Text, Date");
    };
  }

  private static String text(JsonNode node, String field, String where) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new PsfException(PsfException.Code.ASSET_002, where, "must declare a '" + field + "'");
    }
    return value.asText();
  }

  private static String names(JsonNode container) {
    if (!container.isObject() || container.isEmpty()) {
      return "none";
    }
    List<String> all = new ArrayList<>();
    container.fieldNames().forEachRemaining(all::add);
    return String.join(", ", all);
  }
}
