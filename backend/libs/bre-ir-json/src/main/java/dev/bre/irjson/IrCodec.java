package dev.bre.irjson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bre.engine.ir.Ir.AggregateSpec;
import dev.bre.engine.ir.Ir.AuthorityEntry;
import dev.bre.engine.ir.Ir.AuthorityLevel;
import dev.bre.engine.ir.Ir.AuthorityMatrix;
import dev.bre.engine.ir.Ir.Bin;
import dev.bre.engine.ir.Ir.Characteristic;
import dev.bre.engine.ir.Ir.DecisionTableDef;
import dev.bre.engine.ir.Ir.FlowDef;
import dev.bre.engine.ir.Ir.HitPolicy;
import dev.bre.engine.ir.Ir.MissingPolicy;
import dev.bre.engine.ir.Ir.OnMissing;
import dev.bre.engine.ir.Ir.Outcome;
import dev.bre.engine.ir.Ir.OutcomeStatus;
import dev.bre.engine.ir.Ir.PolicyIr;
import dev.bre.engine.ir.Ir.RowResult;
import dev.bre.engine.ir.Ir.RuleDef;
import dev.bre.engine.ir.Ir.RuntimeStatus;
import dev.bre.engine.ir.Ir.Scaling;
import dev.bre.engine.ir.Ir.Scope;
import dev.bre.engine.ir.Ir.ScorecardDef;
import dev.bre.engine.ir.Ir.StageDef;
import dev.bre.engine.ir.Ir.StepDef;
import dev.bre.engine.ir.Ir.TableInput;
import dev.bre.engine.ir.Ir.TableOutput;
import dev.bre.engine.ir.Ir.TableRow;
import dev.bre.engine.ir.Ir.VariableDef;
import dev.bre.engine.ir.Ir.WorseWhen;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the PolicyVersion IR from JSON (LLD §2.2, M1-12).
 *
 * <p>This lives in {@code bre-ir-json} rather than in the engine because the engine must not depend
 * on Jackson (LLD §1.2) — an ArchUnit rule fails the build if it does. The engine evaluates IR
 * records; turning bytes into records is this module's job.
 *
 * <p><b>Fragments are first-class.</b> A conformance vector and a studio preview both hand over a
 * <em>part</em> of a policy — often just a {@code rules} array — and expect it to run. So every
 * section is optional, and {@link #defaultFlow} synthesises a flow when none is given. The
 * alternative, demanding a complete document, would mean every one of the LLD §13 policy vectors
 * carrying a flow it does not care about.
 *
 * <p>What this deliberately does <b>not</b> do is validate against the IR JSON Schema. The schema's
 * conditional requirements (a DEVIATION needs a {@code deviationType}; a FAIL must not carry a
 * {@code measure}) are compile-time concerns that belong with the compiler's diagnostics in M1-10,
 * where the author gets a positioned error. Reading is permissive; approval is where the policy is
 * judged.
 */
public final class IrCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private IrCodec() {}

  /**
   * Reads a policy, or a fragment of one, from JSON text.
   *
   * @param json the IR JSON
   * @return the policy
   * @throws UncheckedIOException if the text is not valid JSON
   */
  public static PolicyIr readPolicy(String json) {
    try {
      return readPolicy(MAPPER.readTree(json));
    } catch (IOException e) {
      throw new UncheckedIOException("the IR document is not valid JSON", e);
    }
  }

  /**
   * Reads a policy, or a fragment of one, from a parsed JSON tree.
   *
   * @param node the IR object
   * @return the policy
   */
  public static PolicyIr readPolicy(JsonNode node) {
    List<VariableDef> variables = readArray(node.get("variables"), IrCodec::readVariable);
    List<RuleDef> rules = readArray(node.get("rules"), IrCodec::readRule);
    List<DecisionTableDef> tables = readArray(node.get("decisionTables"), IrCodec::readTable);
    List<ScorecardDef> scorecards = readArray(node.get("scorecards"), IrCodec::readScorecard);

    FlowDef flow =
        node.hasNonNull("flow")
            ? readFlow(node.get("flow"))
            : defaultFlow(rules, tables, scorecards);

    JsonNode settings = node.get("settings");
    Optional<Integer> fuelLimit =
        settings != null && settings.hasNonNull("fuelLimit")
            ? Optional.of(settings.get("fuelLimit").asInt())
            : Optional.empty();

    JsonNode assets = node.get("assets");
    Optional<AuthorityMatrix> authorityMatrix =
        assets != null && assets.hasNonNull("authorityMatrix")
            ? Optional.of(readAuthorityMatrix(assets.get("authorityMatrix")))
            : Optional.empty();

    Optional<String> authorityAmount =
        settings != null ? optionalText(settings, "authorityAmount") : Optional.empty();

    return new PolicyIr(
        node.hasNonNull("irVersion") ? node.get("irVersion").asInt() : 1,
        variables,
        rules,
        tables,
        scorecards,
        flow,
        fuelLimit,
        authorityAmount,
        authorityMatrix);
  }

  /**
   * Synthesises a flow for a fragment that declares none: one non-knockout stage running every
   * component in declaration order.
   *
   * <p>The aggregate is WORST rather than NONE because WORST over a single entity <em>is</em> that
   * entity's status, so it is correct for an application-scope component and still correct for a
   * MEMBER-scope one — where NONE would silently report only the first member and hide the rest.
   * Picking the safe one matters: the wrong default here would quietly drop findings.
   */
  private static FlowDef defaultFlow(
      List<RuleDef> rules, List<DecisionTableDef> tables, List<ScorecardDef> scorecards) {
    List<StepDef> steps = new ArrayList<>();
    AggregateSpec worst = AggregateSpec.of(AggregateSpec.Kind.WORST);
    rules.forEach(rule -> steps.add(StepDef.of(rule.code(), worst)));
    tables.forEach(table -> steps.add(StepDef.of(table.code(), worst)));
    scorecards.forEach(scorecard -> steps.add(StepDef.of(scorecard.code(), worst)));
    return new FlowDef(List.of(new StageDef("DEFAULT", false, steps)));
  }

  /**
   * Reads the Authority Matrix (§4.9).
   *
   * <p>A {@code null} bound is read as <b>absent, meaning unbounded</b> — not as zero. §4.9 gives
   * null that meaning explicitly, and a null upper bound defaulted to zero would stop the band
   * covering the amounts it exists for.
   */
  private static AuthorityMatrix readAuthorityMatrix(JsonNode node) {
    return new AuthorityMatrix(
        node.hasNonNull("code") ? text(node, "code") : "AUTHORITY",
        node.hasNonNull("version") ? node.get("version").asInt() : 1,
        node.hasNonNull("routingMode") ? text(node, "routingMode") : "HIGHEST_ONLY",
        readArray(
            node.get("levels"),
            level ->
                new AuthorityLevel(
                    text(level, "levelCode"),
                    level.hasNonNull("rank") ? level.get("rank").asInt() : 0)),
        readArray(node.get("entries"), IrCodec::readAuthorityEntry));
  }

  private static AuthorityEntry readAuthorityEntry(JsonNode node) {
    return new AuthorityEntry(
        node.hasNonNull("entryNo") ? node.get("entryNo").asInt() : 0,
        text(node, "deviationType"),
        node.hasNonNull("productLine") ? text(node, "productLine") : "*",
        node.hasNonNull("segment") ? text(node, "segment") : "*",
        optionalDecimal(node, "amountAbove"),
        optionalDecimal(node, "amountUpTo"),
        text(node, "levelCode"));
  }

  private static Optional<BigDecimal> optionalDecimal(JsonNode node, String field) {
    JsonNode value = node.get(field);
    // Absent and explicit null both mean unbounded here.
    return value == null || value.isNull()
        ? Optional.empty()
        : Optional.of(new BigDecimal(value.asText()));
  }

  // ------------------------------------------------------------- components

  private static VariableDef readVariable(JsonNode node) {
    return new VariableDef(
        text(node, "name"),
        node.hasNonNull("type") ? text(node, "type") : "Decimal",
        scope(node),
        text(node, "expr"));
  }

  private static RuleDef readRule(JsonNode node) {
    // The schema requires code, name, scope, check, onFail, onMissing and outputs. A fragment
    // routinely gives only code and check, so the rest default here rather than at every caller.
    String code = node.hasNonNull("code") ? text(node, "code") : text(node, "id");
    return new RuleDef(
        code,
        node.hasNonNull("name") ? text(node, "name") : code,
        scope(node),
        optionalText(node, "when"),
        text(node, "check"),
        node.hasNonNull("onFail")
            ? readOutcome(node.get("onFail"))
            : defaultOnFail(optionalText(node, "reasonCode")),
        readOnMissing(node.get("onMissing")),
        readExprMap(node.get("outputs")),
        optionalInt(node, "priority"));
  }

  /**
   * The failure outcome for a rule that declares none: a plain FAIL, carrying a {@code reasonCode}
   * written directly on the rule if there is one.
   *
   * <p>Accepting a top-level {@code reasonCode} is a concession to the vectors, several of which
   * write it there rather than inside {@code onFail}. It is read, never required.
   */
  private static Outcome defaultOnFail(Optional<String> reasonCode) {
    return new Outcome(
        OutcomeStatus.FAIL, reasonCode, Optional.empty(), Optional.empty(), Optional.empty());
  }

  private static DecisionTableDef readTable(JsonNode node) {
    return new DecisionTableDef(
        text(node, "code"),
        node.hasNonNull("name") ? text(node, "name") : text(node, "code"),
        scope(node),
        optionalText(node, "when"),
        readHitPolicy(node.get("hitPolicy")),
        readArray(
            node.get("inputs"), input -> new TableInput(text(input, "name"), text(input, "expr"))),
        readArray(
            node.get("outputs"),
            output ->
                new TableOutput(
                    text(output, "name"),
                    output.hasNonNull("type") ? text(output, "type") : "Decimal")),
        readArray(node.get("rows"), IrCodec::readRow),
        readOnMissing(node.get("onMissing")),
        optionalInt(node, "priority"));
  }

  private static HitPolicy readHitPolicy(JsonNode node) {
    if (node == null || node.isNull()) {
      // FIRST is the IR's least surprising default: declaration order decides.
      return HitPolicy.of(HitPolicy.Kind.FIRST);
    }
    HitPolicy.Kind kind = HitPolicy.Kind.valueOf(text(node, "kind").toUpperCase(Locale.ROOT));
    if (kind != HitPolicy.Kind.COLLECT) {
      return HitPolicy.of(kind);
    }
    return HitPolicy.collect(
        HitPolicy.CollectOp.valueOf(text(node, "op").toUpperCase(Locale.ROOT)),
        text(node, "collectOutput"));
  }

  private static TableRow readRow(JsonNode node) {
    List<String> cells = new ArrayList<>();
    JsonNode when = node.get("when");
    if (when != null && when.isArray()) {
      when.forEach(cell -> cells.add(cell.asText()));
    }
    return new TableRow(cells, readRowResult(node.get("then")), optionalText(node, "label"));
  }

  private static RowResult readRowResult(JsonNode node) {
    if (node == null || node.isNull()) {
      return new RowResult(
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Map.of());
    }
    return new RowResult(
        optionalText(node, "status").map(IrCodec::outcomeStatus),
        optionalText(node, "reasonCode"),
        optionalText(node, "deviationType"),
        optionalText(node, "measure"),
        optionalText(node, "worseWhen")
            .map(value -> WorseWhen.valueOf(value.toUpperCase(Locale.ROOT))),
        readExprMap(node.get("outputs")));
  }

  private static ScorecardDef readScorecard(JsonNode node) {
    return new ScorecardDef(
        text(node, "code"),
        node.hasNonNull("name") ? text(node, "name") : text(node, "code"),
        scope(node),
        node.hasNonNull("baseScore") ? node.get("baseScore").asInt() : 0,
        readArray(node.get("characteristics"), IrCodec::readCharacteristic),
        node.hasNonNull("adverseReasons") ? node.get("adverseReasons").asInt() : 0,
        readScaling(node.get("scaling")),
        readOnMissing(node.get("onMissing")));
  }

  private static Characteristic readCharacteristic(JsonNode node) {
    return new Characteristic(
        text(node, "name"),
        text(node, "expr"),
        optionalText(node, "reasonCode"),
        readArray(
            node.get("bins"),
            bin ->
                new Bin(
                    text(bin, "when"),
                    bin.hasNonNull("points") ? bin.get("points").asInt() : 0,
                    optionalText(bin, "reasonCode"))));
  }

  private static Scaling readScaling(JsonNode node) {
    if (node == null || node.isNull()) {
      return Scaling.IDENTITY;
    }
    return new Scaling(
        decimal(node, "multiplier", BigDecimal.ONE),
        decimal(node, "offset", BigDecimal.ZERO),
        node.hasNonNull("scale") ? node.get("scale").asInt() : 0,
        node.hasNonNull("roundingMode") ? text(node, "roundingMode") : "HALF_UP");
  }

  // ------------------------------------------------------------------ flow

  private static FlowDef readFlow(JsonNode node) {
    return new FlowDef(readArray(node.get("stages"), IrCodec::readStage));
  }

  private static StageDef readStage(JsonNode node) {
    List<String> ignoredRoles = new ArrayList<>();
    return new StageDef(
        node.hasNonNull("name") ? text(node, "name") : "STAGE",
        node.hasNonNull("knockout") && node.get("knockout").asBoolean(),
        readArray(node.get("steps"), step -> readStep(step, ignoredRoles)));
  }

  private static StepDef readStep(JsonNode node, List<String> scratch) {
    List<String> roles = new ArrayList<>();
    JsonNode rolesNode = node.get("roles");
    if (rolesNode != null && rolesNode.isArray()) {
      rolesNode.forEach(role -> roles.add(role.asText()));
    }
    // The IR calls a step's component pointer `ref`; `id` is accepted because some vectors use it.
    String ref = node.hasNonNull("ref") ? text(node, "ref") : text(node, "id");
    return new StepDef(ref, roles, readAggregate(node.get("aggregate")));
  }

  private static AggregateSpec readAggregate(JsonNode node) {
    if (node == null || node.isNull()) {
      return AggregateSpec.of(AggregateSpec.Kind.WORST);
    }
    AggregateSpec.Kind kind =
        AggregateSpec.Kind.valueOf(text(node, "kind").toUpperCase(Locale.ROOT));
    if (kind != AggregateSpec.Kind.THRESHOLD) {
      return AggregateSpec.of(kind);
    }

    List<RuntimeStatus> statuses = new ArrayList<>();
    JsonNode statusesNode = node.get("statuses");
    if (statusesNode != null && statusesNode.isArray()) {
      statusesNode.forEach(
          status -> statuses.add(RuntimeStatus.valueOf(status.asText().toUpperCase(Locale.ROOT))));
    }

    return new AggregateSpec(
        kind,
        Optional.of(AggregateSpec.Metric.valueOf(text(node, "metric").toUpperCase(Locale.ROOT))),
        Optional.of(text(node, "op")),
        Optional.of(text(node, "value")),
        statuses,
        optionalText(node, "result").map(IrCodec::outcomeStatus));
  }

  // --------------------------------------------------------------- helpers

  private static Outcome readOutcome(JsonNode node) {
    return new Outcome(
        outcomeStatus(text(node, "status")),
        optionalText(node, "reasonCode"),
        optionalText(node, "deviationType"),
        optionalText(node, "measure"),
        optionalText(node, "worseWhen")
            .map(value -> WorseWhen.valueOf(value.toUpperCase(Locale.ROOT))));
  }

  private static OnMissing readOnMissing(JsonNode node) {
    if (node == null || node.isNull()) {
      return OnMissing.DEFAULT;
    }
    // Accepts both the IR's object form and a bare string, which fragments use.
    if (node.isTextual()) {
      return OnMissing.of(missingPolicy(node.asText()), null);
    }
    return new OnMissing(
        missingPolicy(text(node, "policy")),
        node.hasNonNull("value") ? Optional.of(node.get("value").asBoolean()) : Optional.empty(),
        optionalText(node, "reasonCode"));
  }

  /**
   * Maps an {@code onMissing} policy name.
   *
   * <p>{@code DATA_MISSING} is accepted as a synonym for {@code REFER}: the IR names the policy
   * after what it does (refer the application) and §13's M-01 row names it after the status it
   * produces. Rejecting either spelling would be pedantry at the author's expense.
   */
  private static MissingPolicy missingPolicy(String name) {
    String upper = name.toUpperCase(Locale.ROOT);
    return "DATA_MISSING".equals(upper) ? MissingPolicy.REFER : MissingPolicy.valueOf(upper);
  }

  private static OutcomeStatus outcomeStatus(String name) {
    return OutcomeStatus.valueOf(name.toUpperCase(Locale.ROOT));
  }

  private static Scope scope(JsonNode node) {
    return node.hasNonNull("scope")
        ? Scope.valueOf(node.get("scope").asText().toUpperCase(Locale.ROOT))
        : Scope.APPLICATION;
  }

  private static Map<String, String> readExprMap(JsonNode node) {
    if (node == null || !node.isObject()) {
      return Map.of();
    }
    Map<String, String> exprs = new LinkedHashMap<>();
    node.properties().forEach(entry -> exprs.put(entry.getKey(), entry.getValue().asText()));
    return exprs;
  }

  private static <T> List<T> readArray(
      JsonNode node, java.util.function.Function<JsonNode, T> reader) {
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<T> items = new ArrayList<>(node.size());
    node.forEach(element -> items.add(reader.apply(element)));
    return items;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException(
          "the IR is missing the required field '" + field + "' on " + describe(node));
    }
    return value.asText();
  }

  /** Names the object for an error message, by its code if it has one. Never dumps its contents. */
  private static String describe(JsonNode node) {
    JsonNode code = node.get("code");
    if (code != null && !code.isNull()) {
      return "'" + code.asText() + "'";
    }
    return "an IR object";
  }

  private static Optional<String> optionalText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? Optional.empty() : Optional.of(value.asText());
  }

  private static Optional<Integer> optionalInt(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? Optional.empty() : Optional.of(value.asInt());
  }

  private static BigDecimal decimal(JsonNode node, String field, BigDecimal fallback) {
    JsonNode value = node.get(field);
    // Via the string form: a decimal read through a double is not the decimal that was written.
    return value == null || value.isNull() ? fallback : new BigDecimal(value.asText());
  }
}
