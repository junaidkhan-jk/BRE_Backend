package dev.bre.irjson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.composer.Composer;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;
import org.snakeyaml.engine.v2.nodes.Tag;
import org.snakeyaml.engine.v2.parser.ParserImpl;
import org.snakeyaml.engine.v2.scanner.StreamReader;
import org.snakeyaml.engine.v2.schema.CoreSchema;

/**
 * PSF v1 (YAML) to IR JSON, per the mapping rules of LLD §2.4 (M1–M9).
 *
 * <p>PSF is what a credit analyst reads, reviews and diffs; the IR is what the engine loads. They
 * map one to one, so this class is a translation and not an interpretation — every default it
 * applies is named in §2.4, and anything it cannot translate is an error rather than a guess.
 *
 * <p>Three decisions here are load-bearing and easy to get wrong:
 *
 * <ul>
 *   <li><b>The document is composed, not loaded</b> (M1, M3). A normal YAML load hands back a
 *       {@code Map} of Java objects, by which point {@code 0.45} is already a binary {@code double}
 *       and the original text is gone. In a policy where {@code 0.45} may be a FOIR threshold, that
 *       is a corrupted rule nobody can see. Composing gives the node tree, so every scalar still
 *       carries its resolved tag <em>and</em> its quoting style, which is what lets an unquoted
 *       non-integer be rejected as {@link PsfException.Code#PSF_002} with its text intact.
 *   <li><b>The YAML 1.2 core schema is set explicitly</b> (M1). Under YAML 1.1 typing, {@code NO}
 *       is the boolean false — so a product line coded {@code NO} silently becomes {@code false}.
 *       This is the Norway problem, and a platform whose identifiers are short upper-case codes
 *       walks into it constantly.
 *   <li><b>Unknown keys are rejected</b> (PSF-003) against an explicit allow-list per position. A
 *       typo in {@code onMissing} would otherwise be a silently ignored key, and the rule would run
 *       with its fail-closed behaviour quietly defaulted.
 * </ul>
 *
 * <p>What this does not do: resolve {@code tests[].facts: {$ref: …}} against a fixture store, which
 * belongs to the studio (M9) and arrives as a resolver argument; and preserve comments, which M10
 * explicitly discards.
 */
public final class PsfCodec {

  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  /** §2.4 M8: the default when {@code settings.authorityAmount} is absent. */
  public static final String DEFAULT_AUTHORITY_AMOUNT =
      "coalesce(application.approvedAmount, application.recommendedAmount,"
          + " application.requestedAmount)";

  /** §2.4 M8: the default when {@code settings.customerReasonLimit} is absent. */
  public static final int DEFAULT_CUSTOMER_REASON_LIMIT = 4;

  private static final Pattern ASSET_REF =
      Pattern.compile("^([A-Z][A-Z0-9_]{1,63})@([1-9][0-9]*)$");
  private static final Pattern FACT_SCHEMA_REF =
      Pattern.compile(
          "^([a-z][a-z0-9-]{1,63})@((?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*))$");
  private static final Pattern USE_DEFAULT = Pattern.compile("^USE_DEFAULT\\((true|false)\\)$");
  private static final Pattern COLLECT = Pattern.compile("^COLLECT\\((SUM|MIN|MAX|COUNT|LIST)\\)$");
  private static final Pattern THRESHOLD =
      Pattern.compile(
          "^THRESHOLD\\(\\s*(count|percent)\\s*(>=|<=|>|<|=)\\s*([^,]+?)\\s*,\\s*([A-Z_|]+)\\s*\\)"
              + "\\s*->\\s*([A-Z_]+)$");

  private final Function<String, JsonNode> fixtures;

  private PsfCodec(Function<String, JsonNode> fixtures) {
    this.fixtures = fixtures;
  }

  /**
   * Translates a PSF document to IR JSON.
   *
   * @param yaml the PSF source
   * @param tenantId the importing tenant; PSF carries no tenant, which is what makes a policy
   *     portable between installations (§2.6 export/import)
   * @return the IR as JSON, ready for {@link IrSchemaValidator} and {@link IrCodec}
   * @throws PsfException on any PSF-001..003
   */
  public static ObjectNode toIrJson(String yaml, String tenantId) {
    return toIrJson(
        yaml,
        tenantId,
        path -> {
          throw new PsfException(
              PsfException.Code.PSF_003,
              path,
              "a facts $ref needs a fixture store; pass a resolver to translate this document");
        });
  }

  /**
   * Translates a PSF document to IR JSON, resolving test fixtures through a store.
   *
   * @param yaml the PSF source
   * @param tenantId the importing tenant
   * @param fixtureResolver maps a {@code $ref} path to the fixture's facts (§2.4 M9). Fixtures MUST
   *     be synthetic (§9.7) — this codec cannot check that, and the store must.
   * @return the IR as JSON
   * @throws PsfException on any PSF-001..003
   */
  public static ObjectNode toIrJson(
      String yaml, String tenantId, Function<String, JsonNode> fixtureResolver) {
    return new PsfCodec(fixtureResolver).translate(yaml, tenantId);
  }

  /**
   * Translates a PSF document and decodes it into the evaluable IR.
   *
   * <p>Validates against the §2.2 JSON Schema in between, so a document that translates but is not
   * a legal PolicyVersion fails here rather than at evaluation time.
   *
   * @param yaml the PSF source
   * @param tenantId the importing tenant
   * @return the policy
   * @throws PsfException on any PSF-001..003
   * @throws IrSchemaValidator.IrSchemaException if the translated IR is not schema-valid
   */
  public static dev.bre.engine.ir.Ir.PolicyIr read(String yaml, String tenantId) {
    ObjectNode ir = toIrJson(yaml, tenantId);
    IrSchemaValidator.validate(ir);
    return IrCodec.readPolicy(ir);
  }

  // ---------------------------------------------------------------------------------------------

  private ObjectNode translate(String yaml, String tenantId) {
    Node root = compose(yaml);
    rejectUnquotedDecimals(root, "");

    MappingNode doc = map(root, "");
    allow(
        doc,
        "",
        "policy",
        "version",
        "productLines",
        "stage",
        "segment",
        "description",
        "factSchema",
        "assets",
        "settings",
        "variables",
        "rules",
        "decisionTables",
        "scorecards",
        "flow",
        "tests");

    ObjectNode ir = JSON.objectNode();
    ir.put("irVersion", 1);

    ObjectNode policy = ir.putObject("policy");
    policy.put("tenantId", tenantId);
    policy.put("code", text(required(doc, "policy", ""), "policy"));
    policy.put("version", integer(required(doc, "version", ""), "version"));
    ArrayNode lines = policy.putArray("productLines");
    for (Indexed item : seq(required(doc, "productLines", ""), "productLines")) {
      lines.add(text(item.node(), item.path()));
    }
    policy.put("stage", text(required(doc, "stage", ""), "stage"));
    find(doc, "segment").ifPresent(n -> policy.put("segment", nullableText(n, "segment")));
    find(doc, "description").ifPresent(n -> policy.put("description", text(n, "description")));

    ir.set("factSchema", factSchema(required(doc, "factSchema", ""), "factSchema"));
    ir.set("assets", assets(doc));
    ir.set("settings", settings(doc));
    ir.set("variables", variables(doc));
    ir.set("rules", rules(doc));
    ir.set("decisionTables", decisionTables(doc));
    ir.set("scorecards", scorecards(doc));
    ir.set("flow", flow(doc));
    ir.set("tests", tests(doc));
    return ir;
  }

  private Node compose(String yaml) {
    // The YAML 1.2 core schema (M1) and no YAML 1.1 implicit typing: NO, yes and on stay strings.
    LoadSettings settings =
        LoadSettings.builder()
            .setSchema(new CoreSchema())
            // Set for completeness, though it governs snakeyaml's constructor rather than the
            // composer: duplicates are caught in allow() instead. See that method.
            .setAllowDuplicateKeys(false)
            .setLabel("PSF")
            .build();
    try {
      return new Composer(settings, new ParserImpl(settings, new StreamReader(settings, yaml)))
          .getSingleNode()
          .orElseThrow(
              () -> new PsfException(PsfException.Code.PSF_001, "", "the document is empty"));
    } catch (YamlEngineException e) {
      throw new PsfException(PsfException.Code.PSF_001, "", "YAML parse error", e);
    }
  }

  /**
   * §2.4 M3: an unquoted non-integer number anywhere in PSF is PSF-002.
   *
   * <p>Checked over the whole tree in one pass rather than at each use site, because M3 says
   * "anywhere" and a decimal in a position this codec does not read would otherwise slip through
   * into a future version of the format. A <em>quoted</em> decimal is correct and expected — the
   * rule exists to catch {@code threshold: 0.45}, which YAML would make a binary double, not {@code
   * threshold: "0.45"}, which stays exact.
   */
  private void rejectUnquotedDecimals(Node node, String path) {
    switch (node) {
      case ScalarNode scalar -> {
        if (Tag.FLOAT.equals(scalar.getTag()) && scalar.isPlain()) {
          throw new PsfException(
              PsfException.Code.PSF_002,
              path,
              "unquoted non-integer number; quote decimal literals so they stay exact");
        }
      }
      case MappingNode mapping -> {
        for (NodeTuple tuple : mapping.getValue()) {
          String key = keyText(tuple, path);
          rejectUnquotedDecimals(tuple.getValueNode(), child(path, key));
        }
      }
      case SequenceNode sequence -> {
        List<Node> items = sequence.getValue();
        for (int i = 0; i < items.size(); i++) {
          rejectUnquotedDecimals(items.get(i), path + "[" + i + "]");
        }
      }
      default -> {
        // An alias node resolves to its anchor's node, which the walk above already visited.
      }
    }
  }

  // --- sections --------------------------------------------------------------------------------

  private ObjectNode factSchema(Node node, String path) {
    // M4: code@x.y.z -> {code, version}
    String ref = text(node, path);
    Matcher m = FACT_SCHEMA_REF.matcher(ref);
    if (!m.matches()) {
      throw new PsfException(
          PsfException.Code.PSF_003,
          path,
          "expected a fact schema reference of the form code@x.y.z");
    }
    ObjectNode out = JSON.objectNode();
    out.put("code", m.group(1));
    out.put("version", m.group(2));
    return out;
  }

  private ObjectNode assets(MappingNode doc) {
    ObjectNode out = JSON.objectNode();
    Optional<Node> node = find(doc, "assets");
    if (node.isEmpty()) {
      // Every key is required by the schema, so an absent assets block means "no assets", written
      // out explicitly rather than left for the validator to reject with a less helpful message.
      out.putNull("parameterSet");
      out.putArray("referenceLists");
      out.putNull("authorityMatrix");
      return out;
    }
    MappingNode assets = map(node.get(), "assets");
    allow(assets, "assets", "parameterSet", "referenceLists", "reasonCodes", "authorityMatrix");

    Optional<Node> parameterSet = find(assets, "parameterSet");
    if (parameterSet.isPresent()) {
      out.set("parameterSet", assetRef(parameterSet.get(), "assets.parameterSet"));
    } else {
      out.putNull("parameterSet");
    }

    ArrayNode lists = out.putArray("referenceLists");
    for (Indexed item : optionalSeq(assets, "referenceLists", "assets.referenceLists")) {
      lists.add(assetRef(item.node(), item.path()));
    }

    find(assets, "reasonCodes")
        .ifPresent(n -> out.set("reasonCodes", assetRef(n, "assets.reasonCodes")));

    Optional<Node> matrix = find(assets, "authorityMatrix");
    if (matrix.isPresent()) {
      out.set("authorityMatrix", assetRef(matrix.get(), "assets.authorityMatrix"));
    } else {
      out.putNull("authorityMatrix");
    }
    return out;
  }

  private ObjectNode assetRef(Node node, String path) {
    // M4: CODE@n -> {code, version: n}
    String ref = text(node, path);
    Matcher m = ASSET_REF.matcher(ref);
    if (!m.matches()) {
      throw new PsfException(
          PsfException.Code.PSF_003, path, "expected an asset reference of the form CODE@version");
    }
    ObjectNode out = JSON.objectNode();
    out.put("code", m.group(1));
    out.put("version", Integer.parseInt(m.group(2)));
    return out;
  }

  private ObjectNode settings(MappingNode doc) {
    ObjectNode out = JSON.objectNode();
    Optional<Node> node = find(doc, "settings");
    if (node.isEmpty()) {
      out.put("authorityAmount", DEFAULT_AUTHORITY_AMOUNT);
      out.put("customerReasonLimit", DEFAULT_CUSTOMER_REASON_LIMIT);
      return out;
    }
    MappingNode settings = map(node.get(), "settings");
    allow(settings, "settings", "authorityAmount", "customerReasonLimit", "fuelLimit");
    out.put(
        "authorityAmount",
        find(settings, "authorityAmount")
            .map(n -> expr(n, "settings.authorityAmount"))
            .orElse(DEFAULT_AUTHORITY_AMOUNT));
    out.put(
        "customerReasonLimit",
        find(settings, "customerReasonLimit")
            .map(n -> integer(n, "settings.customerReasonLimit"))
            .orElse(DEFAULT_CUSTOMER_REASON_LIMIT));
    find(settings, "fuelLimit")
        .ifPresent(n -> out.put("fuelLimit", integer(n, "settings.fuelLimit")));
    return out;
  }

  private ArrayNode variables(MappingNode doc) {
    ArrayNode out = JSON.arrayNode();
    for (Indexed item : optionalSeq(doc, "variables", "variables")) {
      MappingNode v = map(item.node(), item.path());
      allow(v, item.path(), "name", "type", "scope", "expr", "description");
      ObjectNode variable = out.addObject();
      variable.put("name", text(required(v, "name", item.path()), item.path() + ".name"));
      variable.put("type", text(required(v, "type", item.path()), item.path() + ".type"));
      // M8: a variable with no scope is an application-level variable.
      variable.put("scope", scope(v, item.path()));
      variable.put("expr", expr(required(v, "expr", item.path()), item.path() + ".expr"));
      copyDescription(v, variable, item.path());
    }
    return out;
  }

  private ArrayNode rules(MappingNode doc) {
    ArrayNode out = JSON.arrayNode();
    for (Indexed item : optionalSeq(doc, "rules", "rules")) {
      MappingNode r = map(item.node(), item.path());
      allow(
          r,
          item.path(),
          "code",
          "name",
          "scope",
          "when",
          "check",
          "onFail",
          "onMissing",
          "outputs",
          "priority",
          "governance",
          "description");
      ObjectNode rule = out.addObject();
      rule.put("code", text(required(r, "code", item.path()), item.path() + ".code"));
      rule.put("name", text(required(r, "name", item.path()), item.path() + ".name"));
      rule.put("scope", scope(r, item.path()));
      // M8: an absent `when` means always applicable, and the key stays absent — it does not
      // become `"true"`, so that a diff shows an added precondition as an addition.
      find(r, "when").ifPresent(n -> rule.put("when", expr(n, item.path() + ".when")));
      rule.put("check", expr(required(r, "check", item.path()), item.path() + ".check"));
      rule.set("onFail", outcome(required(r, "onFail", item.path()), item.path() + ".onFail"));
      rule.set("onMissing", onMissing(r, item.path()));
      rule.set("outputs", outputs(r, item.path()));
      find(r, "priority")
          .ifPresent(n -> rule.put("priority", integer(n, item.path() + ".priority")));
      find(r, "governance").ifPresent(n -> rule.set("governance", governance(n, item.path())));
      copyDescription(r, rule, item.path());
    }
    return out;
  }

  private ArrayNode decisionTables(MappingNode doc) {
    ArrayNode out = JSON.arrayNode();
    for (Indexed item : optionalSeq(doc, "decisionTables", "decisionTables")) {
      MappingNode t = map(item.node(), item.path());
      allow(
          t,
          item.path(),
          "code",
          "name",
          "scope",
          "when",
          "hitPolicy",
          "collectOutput",
          "inputs",
          "outputs",
          "rows",
          "onMissing",
          "priority",
          "governance",
          "description");
      ObjectNode table = out.addObject();
      table.put("code", text(required(t, "code", item.path()), item.path() + ".code"));
      table.put("name", textOrCode(t, item.path()));
      table.put("scope", scope(t, item.path()));
      find(t, "when").ifPresent(n -> table.put("when", expr(n, item.path() + ".when")));
      table.set("hitPolicy", hitPolicy(t, item.path()));

      ArrayNode inputs = table.putArray("inputs");
      for (Indexed in : optionalSeq(t, "inputs", item.path() + ".inputs")) {
        MappingNode i = map(in.node(), in.path());
        allow(i, in.path(), "name", "expr");
        ObjectNode input = inputs.addObject();
        input.put("name", text(required(i, "name", in.path()), in.path() + ".name"));
        input.put("expr", expr(required(i, "expr", in.path()), in.path() + ".expr"));
      }

      ArrayNode outputs = table.putArray("outputs");
      for (Indexed on : optionalSeq(t, "outputs", item.path() + ".outputs")) {
        MappingNode o = map(on.node(), on.path());
        allow(o, on.path(), "name", "type");
        ObjectNode output = outputs.addObject();
        output.put("name", text(required(o, "name", on.path()), on.path() + ".name"));
        output.put("type", text(required(o, "type", on.path()), on.path() + ".type"));
      }

      ArrayNode rows = table.putArray("rows");
      for (Indexed rn : optionalSeq(t, "rows", item.path() + ".rows")) {
        MappingNode row = map(rn.node(), rn.path());
        allow(row, rn.path(), "when", "then", "label");
        ObjectNode target = rows.addObject();
        ArrayNode cells = target.putArray("when");
        for (Indexed cell : seq(required(row, "when", rn.path()), rn.path() + ".when")) {
          cells.add(expr(cell.node(), cell.path()));
        }
        target.set("then", rowResult(required(row, "then", rn.path()), rn.path() + ".then"));
        find(row, "label").ifPresent(n -> target.put("label", text(n, rn.path() + ".label")));
      }

      table.set("onMissing", onMissing(t, item.path()));
      find(t, "priority")
          .ifPresent(n -> table.put("priority", integer(n, item.path() + ".priority")));
      find(t, "governance").ifPresent(n -> table.set("governance", governance(n, item.path())));
      copyDescription(t, table, item.path());
    }
    return out;
  }

  private ArrayNode scorecards(MappingNode doc) {
    ArrayNode out = JSON.arrayNode();
    for (Indexed item : optionalSeq(doc, "scorecards", "scorecards")) {
      MappingNode s = map(item.node(), item.path());
      allow(
          s,
          item.path(),
          "code",
          "name",
          "scope",
          "baseScore",
          "characteristics",
          "adverseReasons",
          "scaling",
          "onMissing",
          "governance",
          "description");
      ObjectNode card = out.addObject();
      card.put("code", text(required(s, "code", item.path()), item.path() + ".code"));
      card.put("name", textOrCode(s, item.path()));
      card.put("scope", scope(s, item.path()));
      card.put(
          "baseScore", integer(required(s, "baseScore", item.path()), item.path() + ".baseScore"));

      ArrayNode chars = card.putArray("characteristics");
      for (Indexed cn : optionalSeq(s, "characteristics", item.path() + ".characteristics")) {
        MappingNode c = map(cn.node(), cn.path());
        allow(c, cn.path(), "name", "expr", "reasonCode", "bins", "description");
        ObjectNode ch = chars.addObject();
        ch.put("name", text(required(c, "name", cn.path()), cn.path() + ".name"));
        ch.put("expr", expr(required(c, "expr", cn.path()), cn.path() + ".expr"));
        find(c, "reasonCode")
            .ifPresent(n -> ch.put("reasonCode", text(n, cn.path() + ".reasonCode")));
        ArrayNode bins = ch.putArray("bins");
        for (Indexed bn : seq(required(c, "bins", cn.path()), cn.path() + ".bins")) {
          MappingNode b = map(bn.node(), bn.path());
          allow(b, bn.path(), "when", "points", "reasonCode");
          ObjectNode bin = bins.addObject();
          bin.put("when", expr(required(b, "when", bn.path()), bn.path() + ".when"));
          bin.put("points", integer(required(b, "points", bn.path()), bn.path() + ".points"));
          find(b, "reasonCode")
              .ifPresent(n -> bin.put("reasonCode", text(n, bn.path() + ".reasonCode")));
        }
        copyDescription(c, ch, cn.path());
      }

      card.put(
          "adverseReasons",
          integer(required(s, "adverseReasons", item.path()), item.path() + ".adverseReasons"));
      card.set("scaling", scaling(s, item.path()));
      card.set("onMissing", onMissing(s, item.path()));
      find(s, "governance").ifPresent(n -> card.set("governance", governance(n, item.path())));
      copyDescription(s, card, item.path());
    }
    return out;
  }

  private ObjectNode scaling(MappingNode owner, String path) {
    ObjectNode out = JSON.objectNode();
    Optional<Node> node = find(owner, "scaling");
    if (node.isEmpty()) {
      // M8: identity scaling. The multiplier and offset are decimal *strings* (§2.1 rule 4).
      out.put("multiplier", "1");
      out.put("offset", "0");
      out.put("scale", 0);
      out.put("roundingMode", "HALF_UP");
      return out;
    }
    MappingNode s = map(node.get(), path + ".scaling");
    allow(s, path + ".scaling", "multiplier", "offset", "scale", "roundingMode");
    out.put(
        "multiplier",
        find(s, "multiplier").map(n -> decimal(n, path + ".scaling.multiplier")).orElse("1"));
    out.put("offset", find(s, "offset").map(n -> decimal(n, path + ".scaling.offset")).orElse("0"));
    out.put("scale", find(s, "scale").map(n -> integer(n, path + ".scaling.scale")).orElse(0));
    out.put(
        "roundingMode",
        find(s, "roundingMode")
            .map(n -> text(n, path + ".scaling.roundingMode"))
            .orElse("HALF_UP"));
    return out;
  }

  private ObjectNode flow(MappingNode doc) {
    ObjectNode out = JSON.objectNode();
    ArrayNode stages = out.putArray("stages");
    Optional<Node> node = find(doc, "flow");
    if (node.isEmpty()) {
      return out;
    }
    MappingNode f = map(node.get(), "flow");
    allow(f, "flow", "stages");
    for (Indexed sn : optionalSeq(f, "stages", "flow.stages")) {
      MappingNode s = map(sn.node(), sn.path());
      allow(s, sn.path(), "name", "knockout", "steps", "description");
      ObjectNode stage = stages.addObject();
      stage.put("name", text(required(s, "name", sn.path()), sn.path() + ".name"));
      // M8: no knockout key means this stage does not stop the flow.
      stage.put(
          "knockout", find(s, "knockout").map(n -> bool(n, sn.path() + ".knockout")).orElse(false));
      ArrayNode steps = stage.putArray("steps");
      for (Indexed pn : optionalSeq(s, "steps", sn.path() + ".steps")) {
        MappingNode p = map(pn.node(), pn.path());
        allow(p, pn.path(), "ref", "roles", "aggregate");
        ObjectNode step = steps.addObject();
        step.put("ref", text(required(p, "ref", pn.path()), pn.path() + ".ref"));
        List<Indexed> roles = optionalSeq(p, "roles", pn.path() + ".roles");
        if (!roles.isEmpty()) {
          ArrayNode target = step.putArray("roles");
          for (Indexed role : roles) {
            target.add(text(role.node(), role.path()));
          }
        }
        step.set("aggregate", aggregate(p, pn.path()));
      }
      copyDescription(s, stage, sn.path());
    }
    return out;
  }

  private ArrayNode tests(MappingNode doc) {
    ArrayNode out = JSON.arrayNode();
    for (Indexed item : optionalSeq(doc, "tests", "tests")) {
      MappingNode t = map(item.node(), item.path());
      allow(t, item.path(), "name", "asOf", "facts", "smoke", "expect");
      ObjectNode test = out.addObject();
      test.put("name", text(required(t, "name", item.path()), item.path() + ".name"));
      test.put("asOf", text(required(t, "asOf", item.path()), item.path() + ".asOf"));
      test.set("facts", facts(required(t, "facts", item.path()), item.path() + ".facts"));
      // The schema requires `smoke`; absent means this case is not in the load-time smoke set.
      test.put("smoke", find(t, "smoke").map(n -> bool(n, item.path() + ".smoke")).orElse(false));
      test.set("expect", generic(required(t, "expect", item.path()), item.path() + ".expect"));
    }
    return out;
  }

  private JsonNode facts(Node node, String path) {
    // M9: {$ref: path} is resolved against the fixture store; anything else is inline facts.
    if (node instanceof MappingNode mapping && mapping.getValue().size() == 1) {
      NodeTuple only = mapping.getValue().get(0);
      if ("$ref".equals(keyText(only, path))) {
        return fixtures.apply(text(only.getValueNode(), path + ".$ref"));
      }
    }
    return generic(node, path);
  }

  // --- shared shapes --------------------------------------------------------------------------

  private ObjectNode outcome(Node node, String path) {
    MappingNode o = map(node, path);
    allow(o, path, "status", "reasonCode", "deviationType", "measure", "worseWhen");
    ObjectNode out = JSON.objectNode();
    out.put("status", text(required(o, "status", path), path + ".status"));
    find(o, "reasonCode").ifPresent(n -> out.put("reasonCode", text(n, path + ".reasonCode")));
    find(o, "deviationType")
        .ifPresent(n -> out.put("deviationType", text(n, path + ".deviationType")));
    find(o, "measure").ifPresent(n -> out.put("measure", expr(n, path + ".measure")));
    find(o, "worseWhen").ifPresent(n -> out.put("worseWhen", text(n, path + ".worseWhen")));
    return out;
  }

  private ObjectNode rowResult(Node node, String path) {
    MappingNode r = map(node, path);
    allow(r, path, "status", "reasonCode", "deviationType", "measure", "worseWhen", "outputs");
    ObjectNode out = JSON.objectNode();
    find(r, "status").ifPresent(n -> out.put("status", text(n, path + ".status")));
    find(r, "reasonCode").ifPresent(n -> out.put("reasonCode", text(n, path + ".reasonCode")));
    find(r, "deviationType")
        .ifPresent(n -> out.put("deviationType", text(n, path + ".deviationType")));
    find(r, "measure").ifPresent(n -> out.put("measure", expr(n, path + ".measure")));
    find(r, "worseWhen").ifPresent(n -> out.put("worseWhen", text(n, path + ".worseWhen")));
    ObjectNode outputs = outputs(r, path);
    if (!outputs.isEmpty()) {
      out.set("outputs", outputs);
    }
    return out;
  }

  private ObjectNode outputs(MappingNode owner, String path) {
    // M8: an absent outputs block is an empty one. Every value is a BEL expression (M2).
    ObjectNode out = JSON.objectNode();
    Optional<Node> node = find(owner, "outputs");
    if (node.isEmpty()) {
      return out;
    }
    MappingNode o = map(node.get(), path + ".outputs");
    for (NodeTuple tuple : o.getValue()) {
      String name = keyText(tuple, path + ".outputs");
      out.put(name, expr(tuple.getValueNode(), path + ".outputs." + name));
    }
    return out;
  }

  private ObjectNode onMissing(MappingNode owner, String path) {
    // M5: a bare word, or USE_DEFAULT(true|false), becomes the object form. Absent is REFER —
    // the fail-closed default (D9), which is why it is spelled out rather than left to the reader.
    Optional<Node> node = find(owner, "onMissing");
    ObjectNode out = JSON.objectNode();
    if (node.isEmpty()) {
      out.put("policy", "REFER");
      return out;
    }
    String where = path + ".onMissing";
    if (node.get() instanceof MappingNode mapping) {
      allow(mapping, where, "policy", "value", "reasonCode");
      out.put("policy", text(required(mapping, "policy", where), where + ".policy"));
      find(mapping, "value").ifPresent(n -> out.put("value", bool(n, where + ".value")));
      find(mapping, "reasonCode")
          .ifPresent(n -> out.put("reasonCode", text(n, where + ".reasonCode")));
      return out;
    }
    String word = text(node.get(), where);
    Matcher m = USE_DEFAULT.matcher(word);
    if (m.matches()) {
      out.put("policy", "USE_DEFAULT");
      out.put("value", Boolean.parseBoolean(m.group(1)));
      return out;
    }
    out.put("policy", word);
    return out;
  }

  private ObjectNode hitPolicy(MappingNode owner, String path) {
    // M6: FIRST | UNIQUE | PRIORITY | COLLECT(op), the last taking its output from the sibling
    // `collectOutput` key — COLLECT with nowhere to put the result is not a table, it is a typo.
    ObjectNode out = JSON.objectNode();
    Optional<Node> node = find(owner, "hitPolicy");
    if (node.isEmpty()) {
      out.put("kind", "FIRST");
      return out;
    }
    String where = path + ".hitPolicy";
    String word = text(node.get(), where);
    Matcher m = COLLECT.matcher(word);
    if (!m.matches()) {
      out.put("kind", word);
      return out;
    }
    out.put("kind", "COLLECT");
    out.put("op", m.group(1));
    out.put("collectOutput", text(required(owner, "collectOutput", path), path + ".collectOutput"));
    return out;
  }

  private ObjectNode aggregate(MappingNode owner, String path) {
    // M7: WORST | ALL_MUST_PASS | NONE | THRESHOLD(metric op value, STATUS|STATUS) -> STATUS
    ObjectNode out = JSON.objectNode();
    Optional<Node> node = find(owner, "aggregate");
    if (node.isEmpty()) {
      out.put("kind", "WORST");
      return out;
    }
    String where = path + ".aggregate";
    if (node.get() instanceof MappingNode mapping) {
      // Already in object form; copy it across so a hand-written IR-shaped step still works.
      allow(mapping, where, "kind", "metric", "op", "value", "statuses", "result");
      out.put("kind", text(required(mapping, "kind", where), where + ".kind"));
      find(mapping, "metric").ifPresent(n -> out.put("metric", text(n, where + ".metric")));
      find(mapping, "op").ifPresent(n -> out.put("op", text(n, where + ".op")));
      find(mapping, "value").ifPresent(n -> out.put("value", text(n, where + ".value")));
      find(mapping, "statuses")
          .ifPresent(
              n -> {
                ArrayNode statuses = out.putArray("statuses");
                for (Indexed s : seq(n, where + ".statuses")) {
                  statuses.add(text(s.node(), s.path()));
                }
              });
      find(mapping, "result").ifPresent(n -> out.put("result", text(n, where + ".result")));
      return out;
    }

    String word = text(node.get(), where).trim();
    Matcher m = THRESHOLD.matcher(word);
    if (!m.matches()) {
      out.put("kind", word);
      return out;
    }
    out.put("kind", "THRESHOLD");
    out.put("metric", m.group(1).toUpperCase(Locale.ROOT));
    out.put("op", m.group(2));
    out.put("value", m.group(3).trim());
    ArrayNode statuses = out.putArray("statuses");
    for (String status : m.group(4).split("\\|")) {
      if (!status.isBlank()) {
        statuses.add(status.trim());
      }
    }
    out.put("result", m.group(5));
    return out;
  }

  private ObjectNode governance(Node node, String path) {
    MappingNode g = map(node, path + ".governance");
    allow(g, path + ".governance", "overrideRef");
    ObjectNode out = JSON.objectNode();
    find(g, "overrideRef")
        .ifPresent(n -> out.put("overrideRef", text(n, path + ".governance.overrideRef")));
    return out;
  }

  // --- scalars --------------------------------------------------------------------------------

  /**
   * §2.4 M2: an expression position must be a YAML string, and a number or boolean written there
   * becomes its canonical text.
   *
   * <p>So {@code when: true} is the expression {@code "true"}, not the boolean, and {@code when:
   * '>= 4'} stays as written. The conversion is permitted by M2 rather than being an error because
   * {@code when: true} reads naturally and means exactly what it looks like.
   */
  private String expr(Node node, String path) {
    if (node instanceof ScalarNode scalar) {
      return scalar.getValue();
    }
    throw new PsfException(
        PsfException.Code.PSF_003, path, "expected a BEL expression as a string scalar");
  }

  private String text(Node node, String path) {
    if (node instanceof ScalarNode scalar && !Tag.NULL.equals(scalar.getTag())) {
      return scalar.getValue();
    }
    throw new PsfException(PsfException.Code.PSF_003, path, "expected a string scalar");
  }

  private String nullableText(Node node, String path) {
    if (node instanceof ScalarNode scalar && Tag.NULL.equals(scalar.getTag())) {
      return null;
    }
    return text(node, path);
  }

  private String textOrCode(MappingNode owner, String path) {
    // A table or scorecard with no name reads by its code. The schema requires a name; defaulting
    // it to the code is friendlier than rejecting a document over a label.
    return find(owner, "name")
        .map(n -> text(n, path + ".name"))
        .orElseGet(() -> text(required(owner, "code", path), path + ".code"));
  }

  private String decimal(Node node, String path) {
    // Decimals travel as strings (§2.1 rule 4). An integer here is accepted and widened, since
    // `multiplier: 1` is unambiguous; a non-integer number was already rejected by M3.
    String raw = text(node, path);
    try {
      new java.math.BigDecimal(raw);
      return raw;
    } catch (NumberFormatException e) {
      throw new PsfException(PsfException.Code.PSF_002, path, "expected a decimal string");
    }
  }

  private int integer(Node node, String path) {
    if (node instanceof ScalarNode scalar && Tag.INT.equals(scalar.getTag())) {
      try {
        return new BigInteger(scalar.getValue().replace("_", "")).intValueExact();
      } catch (ArithmeticException | NumberFormatException e) {
        throw new PsfException(PsfException.Code.PSF_002, path, "integer out of range");
      }
    }
    throw new PsfException(PsfException.Code.PSF_002, path, "expected an integer");
  }

  private boolean bool(Node node, String path) {
    if (node instanceof ScalarNode scalar && Tag.BOOL.equals(scalar.getTag())) {
      return Boolean.parseBoolean(scalar.getValue());
    }
    throw new PsfException(PsfException.Code.PSF_003, path, "expected true or false");
  }

  private String scope(MappingNode owner, String path) {
    return find(owner, "scope").map(n -> text(n, path + ".scope")).orElse("APPLICATION");
  }

  private void copyDescription(MappingNode from, ObjectNode to, String path) {
    // M10: comments are dropped, descriptions are kept. The description is the only prose that
    // survives a round trip, so it is where an author's intent has to live.
    find(from, "description").ifPresent(n -> to.put("description", text(n, path + ".description")));
  }

  /** Converts an arbitrary YAML subtree to JSON, for facts and expectations. */
  private JsonNode generic(Node node, String path) {
    switch (node) {
      case ScalarNode scalar -> {
        Tag tag = scalar.getTag();
        if (Tag.NULL.equals(tag)) {
          return JSON.nullNode();
        }
        if (Tag.BOOL.equals(tag)) {
          return JSON.booleanNode(Boolean.parseBoolean(scalar.getValue()));
        }
        if (Tag.INT.equals(tag)) {
          return JSON.numberNode(new BigInteger(scalar.getValue().replace("_", "")));
        }
        // A float can only be a quoted one by now (M3), so it stays a string and stays exact.
        return JSON.textNode(scalar.getValue());
      }
      case MappingNode mapping -> {
        ObjectNode out = JSON.objectNode();
        for (NodeTuple tuple : mapping.getValue()) {
          String key = keyText(tuple, path);
          out.set(key, generic(tuple.getValueNode(), child(path, key)));
        }
        return out;
      }
      case SequenceNode sequence -> {
        ArrayNode out = JSON.arrayNode();
        List<Node> items = sequence.getValue();
        for (int i = 0; i < items.size(); i++) {
          out.add(generic(items.get(i), path + "[" + i + "]"));
        }
        return out;
      }
      default -> throw new PsfException(PsfException.Code.PSF_001, path, "unsupported YAML node");
    }
  }

  // --- tree navigation ------------------------------------------------------------------------

  /** A sequence item with the path it was found at, so an error can name {@code rules[2]}. */
  private record Indexed(Node node, String path) {}

  private MappingNode map(Node node, String path) {
    if (node instanceof MappingNode mapping) {
      return mapping;
    }
    throw new PsfException(PsfException.Code.PSF_003, path, "expected a mapping");
  }

  private List<Indexed> seq(Node node, String path) {
    if (!(node instanceof SequenceNode sequence)) {
      throw new PsfException(PsfException.Code.PSF_003, path, "expected a sequence");
    }
    List<Node> items = sequence.getValue();
    List<Indexed> out = new ArrayList<>(items.size());
    for (int i = 0; i < items.size(); i++) {
      out.add(new Indexed(items.get(i), path + "[" + i + "]"));
    }
    return out;
  }

  private List<Indexed> optionalSeq(MappingNode owner, String key, String path) {
    return find(owner, key).map(n -> seq(n, path)).orElse(List.of());
  }

  private Optional<Node> find(MappingNode owner, String key) {
    for (NodeTuple tuple : owner.getValue()) {
      if (tuple.getKeyNode() instanceof ScalarNode k && key.equals(k.getValue())) {
        return Optional.of(tuple.getValueNode());
      }
    }
    return Optional.empty();
  }

  private Node required(MappingNode owner, String key, String path) {
    return find(owner, key)
        .orElseThrow(
            () ->
                new PsfException(
                    PsfException.Code.PSF_003, child(path, key), "required key is missing"));
  }

  /**
   * PSF-003: rejects any key not on the allow-list for this position, and any key given twice.
   *
   * <p>The duplicate check lives here rather than in the parser because snakeyaml-engine enforces
   * {@code allowDuplicateKeys} in its <em>constructor</em>, which composing deliberately bypasses
   * (see the class comment). Without this, a document with two {@code check} keys would compose
   * into two tuples, {@link #find} would return the first, and the second — the one the author most
   * likely meant, being the later edit — would be silently discarded. A policy that ignores half of
   * what it was told is the worst possible failure mode here, so it is an error.
   */
  private void allow(MappingNode owner, String path, String... keys) {
    Set<String> allowed = new LinkedHashSet<>(List.of(keys));
    Set<String> seen = new LinkedHashSet<>();
    for (NodeTuple tuple : owner.getValue()) {
      String key = keyText(tuple, path);
      if (!allowed.contains(key)) {
        throw new PsfException(
            PsfException.Code.PSF_003,
            child(path, key),
            "unknown key; this position accepts " + String.join(", ", allowed));
      }
      if (!seen.add(key)) {
        throw new PsfException(
            PsfException.Code.PSF_001,
            child(path, key),
            "duplicate key; one of the two values would be silently discarded");
      }
    }
  }

  private String keyText(NodeTuple tuple, String path) {
    if (tuple.getKeyNode() instanceof ScalarNode scalar) {
      return scalar.getValue();
    }
    throw new PsfException(PsfException.Code.PSF_003, path, "mapping keys must be scalars");
  }

  private static String child(String path, String key) {
    return path.isEmpty() ? key : path + "." + key;
  }
}
