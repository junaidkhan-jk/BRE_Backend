package dev.bre.registry.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What changed between two versions of a policy (FR-LIFE, U12, M3-02).
 *
 * <p><b>Two diffs, because a reviewer needs both and they answer different questions.</b>
 *
 * <ul>
 *   <li>The <b>semantic</b> diff is over the IR, keyed by component code. It says "rule MIN_AGE's
 *       check changed" and "decision table PRICING_GRID gained a row" regardless of where in the
 *       YAML those things moved to. Reordering a file's rules, renaming an anchor or reflowing a
 *       block produces no semantic change at all — which is the point: a reviewer asked to approve
 *       a 400-line text diff that changes nothing will approve the next one without reading it.
 *   <li>The <b>text</b> diff is over the PSF source, and exists because the IR is a compilation
 *       target. A comment the author wrote to explain a threshold does not survive into the IR, and
 *       that comment is sometimes the most review-worthy part of the change.
 * </ul>
 *
 * <p><b>The semantic diff is derived from the IR generically rather than field by field.</b>
 * Walking the JSON means a component the compiler learns to emit tomorrow appears in the diff
 * without anybody remembering to add it here — whereas a hand-written comparison of {@code check},
 * {@code onFail} and {@code outputs} silently omits whatever was added after it was written, and a
 * diff that silently omits something is worse than no diff.
 *
 * <p><b>Nothing here reads facts.</b> An IR contains expressions, thresholds and parameter
 * references — policy content, which a reviewer is entitled to see. It does not contain a
 * borrower's figures, so §9.6 is not engaged.
 */
public final class PolicyDiff {

  /** How many text-diff lines are returned; past this the semantic diff is the useful artefact. */
  public static final int MAX_UNIFIED_LINES = 2000;

  /** How many lines of source either side may have before the text diff is skipped. */
  public static final int MAX_SOURCE_LINES = 20_000;

  private PolicyDiff() {}

  /**
   * One semantic change.
   *
   * @param kind {@code ADDED}, {@code REMOVED} or {@code CHANGED}
   * @param subject what changed, e.g. {@code RULE MIN_AGE} or {@code FLOW}
   * @param detail which field, and from what to what, for a change
   */
  public record Change(String kind, String subject, String detail) {}

  /**
   * A whole comparison.
   *
   * @param policyCode the policy
   * @param fromVersion the earlier version
   * @param toVersion the later one
   * @param semantic what changed in the compiled policy
   * @param unified the PSF text diff, in unified form
   * @param textDiffSkipped true when a source was too large to diff, so the absence of text changes
   *     below means "not computed" rather than "none"
   */
  public record Diff(
      String policyCode,
      int fromVersion,
      int toVersion,
      List<Change> semantic,
      List<String> unified,
      boolean textDiffSkipped) {

    /** Creates a diff, copying both lists. */
    public Diff {
      semantic = List.copyOf(semantic);
      unified = List.copyOf(unified);
    }

    /** Whether the two versions compile to the same policy. */
    public boolean semanticallyIdentical() {
      return semantic.isEmpty();
    }
  }

  /** The IR arrays that are collections of code-identified components. */
  private static final Map<String, String> COMPONENT_ARRAYS =
      Map.of(
          "rules", "RULE",
          "decisionTables", "DECISION TABLE",
          "scorecards", "SCORECARD",
          "variables", "VARIABLE");

  /**
   * Compares two compiled policies and their sources.
   *
   * @param policyCode the policy
   * @param fromVersion the earlier version
   * @param toVersion the later version
   * @param fromIr the earlier IR
   * @param toIr the later IR
   * @param fromSource the earlier PSF source, or null when it was not kept
   * @param toSource the later PSF source, or null
   * @return the diff
   */
  public static Diff compare(
      String policyCode,
      int fromVersion,
      int toVersion,
      JsonNode fromIr,
      JsonNode toIr,
      String fromSource,
      String toSource) {

    List<Change> changes = new ArrayList<>();

    for (Map.Entry<String, String> array : COMPONENT_ARRAYS.entrySet()) {
      compareComponents(
          changes, array.getValue(), fromIr.path(array.getKey()), toIr.path(array.getKey()));
    }

    // The flow is compared as its ordered list of step references, because that order IS the
    // policy: moving a knockout rule later changes which findings a decline hides.
    List<String> fromFlow = flowSteps(fromIr);
    List<String> toFlow = flowSteps(toIr);
    if (!fromFlow.equals(toFlow)) {
      changes.add(
          new Change(
              "CHANGED",
              "FLOW",
              "step order or membership changed: " + fromFlow + " -> " + toFlow));
    }

    // Everything that is not a component array or the flow: settings, the authority amount, the
    // fact schema, the asset references. Compared whole, since each is a single scalar or object.
    for (String field : topLevelFields(fromIr, toIr)) {
      if (COMPONENT_ARRAYS.containsKey(field) || "flow".equals(field)) {
        continue;
      }
      JsonNode before = fromIr.path(field);
      JsonNode after = toIr.path(field);
      if (!before.equals(after)) {
        differences("", before, after)
            .forEach(difference -> changes.add(new Change("CHANGED", upper(field), difference)));
      }
    }

    boolean skipped =
        fromSource == null
            || toSource == null
            || lineCount(fromSource) > MAX_SOURCE_LINES
            || lineCount(toSource) > MAX_SOURCE_LINES;

    return new Diff(
        policyCode,
        fromVersion,
        toVersion,
        changes,
        skipped ? List.of() : unified(fromSource, toSource),
        skipped);
  }

  private static void compareComponents(
      List<Change> changes, String label, JsonNode before, JsonNode after) {
    Map<String, JsonNode> beforeByCode = byCode(before);
    Map<String, JsonNode> afterByCode = byCode(after);

    for (Map.Entry<String, JsonNode> entry : afterByCode.entrySet()) {
      JsonNode previous = beforeByCode.get(entry.getKey());
      if (previous == null) {
        changes.add(new Change("ADDED", label + " " + entry.getKey(), ""));
      } else if (!previous.equals(entry.getValue())) {
        for (String difference : differences("", previous, entry.getValue())) {
          changes.add(new Change("CHANGED", label + " " + entry.getKey(), difference));
        }
      }
    }
    beforeByCode.keySet().stream()
        .filter(code -> !afterByCode.containsKey(code))
        .forEach(code -> changes.add(new Change("REMOVED", label + " " + code, "")));
  }

  /** Indexes a component array by its {@code code}, or by {@code name} for a variable. */
  private static Map<String, JsonNode> byCode(JsonNode array) {
    Map<String, JsonNode> byCode = new LinkedHashMap<>();
    if (!array.isArray()) {
      return byCode;
    }
    for (int index = 0; index < array.size(); index++) {
      JsonNode component = array.get(index);
      String code =
          component.hasNonNull("code")
              ? component.get("code").asText()
              : component.hasNonNull("name")
                  ? component.get("name").asText()
                  // A component with neither is positional, so position is its identity. Comparing
                  // such a thing by index reports a reorder as a change, which is the honest
                  // reading: nothing else distinguishes the two.
                  : "#" + index;
      byCode.put(code, component);
    }
    return byCode;
  }

  /**
   * Every difference between two JSON values, as {@code path: before -> after}.
   *
   * <p>Recursive, so a changed decision-table cell is reported as {@code rows[3].when[1]} rather
   * than as "the table changed" — a reviewer looking at a forty-row grid needs to be told which
   * row.
   */
  private static List<String> differences(String path, JsonNode before, JsonNode after) {
    List<String> found = new ArrayList<>();
    if (before.equals(after)) {
      return found;
    }
    if (before.isObject() && after.isObject()) {
      Set<String> fields = new LinkedHashSet<>();
      before.fieldNames().forEachRemaining(fields::add);
      after.fieldNames().forEachRemaining(fields::add);
      for (String field : fields) {
        found.addAll(
            differences(
                path.isEmpty() ? field : path + "." + field,
                before.path(field),
                after.path(field)));
      }
      return found;
    }
    if (before.isArray() && after.isArray()) {
      int common = Math.min(before.size(), after.size());
      for (int index = 0; index < common; index++) {
        found.addAll(differences(path + "[" + index + "]", before.get(index), after.get(index)));
      }
      for (int index = common; index < after.size(); index++) {
        found.add(path + "[" + index + "] added: " + render(after.get(index)));
      }
      for (int index = common; index < before.size(); index++) {
        found.add(path + "[" + index + "] removed: " + render(before.get(index)));
      }
      return found;
    }
    found.add((path.isEmpty() ? "value" : path) + ": " + render(before) + " -> " + render(after));
    return found;
  }

  private static String render(JsonNode node) {
    if (node.isMissingNode() || node.isNull()) {
      return "(absent)";
    }
    String text = node.isValueNode() ? node.asText() : node.toString();
    return text.length() <= 200 ? text : text.substring(0, 200) + "…";
  }

  private static List<String> flowSteps(JsonNode ir) {
    List<String> steps = new ArrayList<>();
    for (JsonNode stage : ir.path("flow").path("stages")) {
      String name = stage.path("name").asText("");
      boolean knockout = stage.path("knockout").asBoolean(false);
      for (JsonNode step : stage.path("steps")) {
        steps.add(name + (knockout ? "!" : "") + "/" + step.path("ref").asText(""));
      }
    }
    return steps;
  }

  private static Set<String> topLevelFields(JsonNode before, JsonNode after) {
    Set<String> fields = new LinkedHashSet<>();
    if (before instanceof ObjectNode) {
      before.fieldNames().forEachRemaining(fields::add);
    }
    if (after instanceof ObjectNode) {
      after.fieldNames().forEachRemaining(fields::add);
    }
    return fields;
  }

  private static String upper(String field) {
    StringBuilder out = new StringBuilder(field.length() + 4);
    for (int index = 0; index < field.length(); index++) {
      char character = field.charAt(index);
      if (Character.isUpperCase(character) && index > 0) {
        out.append(' ');
      }
      out.append(Character.toUpperCase(character));
    }
    return out.toString();
  }

  private static int lineCount(String text) {
    int lines = 1;
    for (int index = 0; index < text.length(); index++) {
      if (text.charAt(index) == '\n') {
        lines++;
      }
    }
    return lines;
  }

  /**
   * A unified diff of two sources.
   *
   * <p>A longest-common-subsequence diff written here rather than pulled in as a dependency: it is
   * forty lines, it has no security surface, and the alternative is a library on the classpath of a
   * service that holds signing keys. D11 keeps that classpath small on purpose.
   *
   * <p>Quadratic in the number of lines, which is why {@link #MAX_SOURCE_LINES} exists. A policy is
   * a document a person wrote and reads; twenty thousand lines is already far past that.
   */
  private static List<String> unified(String before, String after) {
    String[] left = before.split("\n", -1);
    String[] right = after.split("\n", -1);

    int[][] lcs = new int[left.length + 1][right.length + 1];
    for (int i = left.length - 1; i >= 0; i--) {
      for (int j = right.length - 1; j >= 0; j--) {
        lcs[i][j] =
            left[i].equals(right[j])
                ? lcs[i + 1][j + 1] + 1
                : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
      }
    }

    List<String> out = new ArrayList<>();
    int i = 0;
    int j = 0;
    while (i < left.length && j < right.length && out.size() < MAX_UNIFIED_LINES) {
      if (left[i].equals(right[j])) {
        out.add("  " + left[i]);
        i++;
        j++;
      } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
        out.add("- " + left[i++]);
      } else {
        out.add("+ " + right[j++]);
      }
    }
    while (i < left.length && out.size() < MAX_UNIFIED_LINES) {
      out.add("- " + left[i++]);
    }
    while (j < right.length && out.size() < MAX_UNIFIED_LINES) {
      out.add("+ " + right[j++]);
    }
    if (out.size() >= MAX_UNIFIED_LINES) {
      out.add(
          "… truncated at " + MAX_UNIFIED_LINES + " lines; the semantic diff above is complete");
    }
    return out;
  }
}
