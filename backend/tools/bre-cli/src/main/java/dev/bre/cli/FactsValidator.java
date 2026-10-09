package dev.bre.cli;

import com.fasterxml.jackson.databind.JsonNode;
import dev.bre.facts.FactSchema;
import dev.bre.facts.ProhibitedData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates a facts document against the canonical fact schema (M6-05).
 *
 * <p>Built for the pilot integrator's CI, which is what shapes it. The Loan Origination System team
 * runs this over their fixtures on every build, so it has to answer three questions precisely and
 * fail the build on any of them:
 *
 * <ol>
 *   <li><b>Does every path exist?</b> A typo like {@code application.requstedAmount} is accepted
 *       silently by a JSON API and then reads as a missing fact — which, fail-closed, refers the
 *       application. The integrator sees unexplained REFERs in production and no error anywhere.
 *   <li><b>Is every value the right shape?</b> Money as a JSON number rather than a string loses
 *       precision before the engine ever sees it (§2.1 rule 4).
 *   <li><b>Is there prohibited data?</b> A raw Aadhaar number in a facts document is a compliance
 *       incident the moment it is transmitted, and the API rejects it with {@code 422} — but by
 *       then it has crossed a network and been written to the sender's own logs. Catching it in the
 *       integrator's CI is the only place it is caught before it exists anywhere.
 * </ol>
 *
 * <p><b>No finding ever carries a value.</b> This runs in a CI job whose output is retained,
 * searchable and often public to the whole engineering team — the one place a leak would be most
 * durable. A finding names the path and the problem (§9.6).
 */
public final class FactsValidator {

  /** One problem with a facts document. */
  public record Finding(String path, String problem) {
    @Override
    public String toString() {
      return path + ": " + problem;
    }
  }

  private final FactSchema schema;

  /**
   * Creates a validator.
   *
   * @param schema the fact schema to validate against
   */
  public FactsValidator(FactSchema schema) {
    this.schema = schema;
  }

  /**
   * Validates a facts document.
   *
   * @param facts the parsed facts
   * @return every finding, in document order; empty when the document is clean
   */
  public List<Finding> validate(JsonNode facts) {
    List<Finding> findings = new ArrayList<>();
    walk(facts, "", findings);
    return List.copyOf(findings);
  }

  private void walk(JsonNode node, String path, List<Finding> findings) {
    if (node.isObject()) {
      node.properties()
          .forEach(
              entry ->
                  walk(
                      entry.getValue(),
                      path.isEmpty() ? entry.getKey() : path + "." + entry.getKey(),
                      findings));
      return;
    }
    if (node.isArray()) {
      // A list element shares its parent's path plus "[]", matching how the schema types fields
      // rather than positions. The index is kept out of the path on purpose: reporting
      // parties[0], parties[1], parties[2] for one mistyped field would bury the actual problem.
      node.forEach(element -> walk(element, path + "[]", findings));
      return;
    }
    if (path.isEmpty()) {
      return;
    }
    checkLeaf(node, path, findings);
  }

  private void checkLeaf(JsonNode node, String path, List<Finding> findings) {
    // Prohibited data first, and on every string regardless of whether the schema knows the path.
    // §9.1 scans "every string in the facts, including ext" — an open extension map is exactly
    // where an unvalidated identifier ends up, and it is also where the schema has nothing to say.
    if (node.isTextual()) {
      ProhibitedData.Kind prohibited = ProhibitedData.scan(node.textValue());
      if (prohibited == ProhibitedData.Kind.AADHAAR) {
        findings.add(
            new Finding(
                path,
                "looks like a raw Aadhaar number (12 digits, valid Verhoeff check digit). Send a"
                    + " vault reference or token instead — a raw Aadhaar in facts is rejected with"
                    + " 422 prohibited-data (00 §3.9)"));
      } else if (prohibited == ProhibitedData.Kind.PAN) {
        findings.add(
            new Finding(
                path,
                "looks like a bare PAN. PAN is accepted only as a token or in encrypted form"
                    + " (00 §3.9)"));
      }
    }

    // An `ext` map is open by design (00 §3.9), so an unknown path beneath one is not a finding.
    // The prohibited-data scan above still ran on it, which is the half that matters: §9.1 says
    // the scan covers "every string in the facts, including ext", and an open map is exactly
    // where an unvalidated identifier ends up.
    if (isUnderExtensionMap(path)) {
      return;
    }

    if (!schema.declares(path)) {
      findings.add(
          new Finding(
              path,
              "is not a field in "
                  + schema.code()
                  + "@"
                  + schema.version()
                  + ". A path the schema does not declare is read as a missing fact, which refers"
                  + " the application rather than failing visibly"));
      return;
    }

    FactSchema.Field field = schema.field(path).orElseThrow();
    typeProblem(node, field).ifPresent(problem -> findings.add(new Finding(path, problem)));
  }

  /** Splits a dotted fact path; an explicit limit, since split drops trailing empty strings. */
  private static final java.util.regex.Pattern DOT = java.util.regex.Pattern.compile("\\.");

  /** Whether any segment of the path is an {@code ext} extension map. */
  private static boolean isUnderExtensionMap(String path) {
    for (String segment : DOT.split(path, -1)) {
      if ("ext".equals(segment) || "ext[]".equals(segment)) {
        return true;
      }
    }
    return false;
  }

  private java.util.Optional<String> typeProblem(JsonNode node, FactSchema.Field field) {
    return switch (field.belType()) {
      case "Decimal" -> {
        if (node.isNumber()) {
          // The one that loses data silently. A JSON number goes through a double on most
          // clients, so 123456789012345.67 arrives as a different amount (§2.1 rule 4).
          yield java.util.Optional.of(
              "is a Decimal and must be a JSON STRING, not a number. A JSON number goes through a"
                  + " double and loses precision on large amounts");
        }
        if (!node.isTextual()) {
          yield java.util.Optional.of("is a Decimal and must be a string");
        }
        try {
          new java.math.BigDecimal(node.textValue());
          yield java.util.Optional.empty();
        } catch (NumberFormatException e) {
          yield java.util.Optional.of("is a Decimal but the value does not parse as one");
        }
      }
      case "Integer" ->
          node.isIntegralNumber()
              ? java.util.Optional.empty()
              : java.util.Optional.of("is an Integer and must be a JSON integer");
      case "Boolean" ->
          node.isBoolean()
              ? java.util.Optional.empty()
              : java.util.Optional.of("is a Boolean and must be true or false");
      case "Date" -> isoProblem(node, "Date", "YYYY-MM-DD");
      case "DateTime" -> isoProblem(node, "DateTime", "an ISO-8601 instant with an offset");
      case "Text" -> {
        if (!node.isTextual()) {
          yield java.util.Optional.of("is Text and must be a string");
        }
        if (!field.enumValues().isEmpty() && !field.enumValues().contains(node.textValue())) {
          // The value is NOT echoed; the permitted set is, which is what the author needs.
          yield java.util.Optional.of(
              "is not one of the permitted values: "
                  + String.join(", ", new java.util.TreeSet<>(field.enumValues())));
        }
        yield java.util.Optional.empty();
      }
      default -> java.util.Optional.empty();
    };
  }

  private java.util.Optional<String> isoProblem(JsonNode node, String type, String shape) {
    if (!node.isTextual()) {
      return java.util.Optional.of("is a " + type + " and must be a string of " + shape);
    }
    try {
      if ("Date".equals(type)) {
        java.time.LocalDate.parse(node.textValue());
      } else {
        java.time.OffsetDateTime.parse(node.textValue());
      }
      return java.util.Optional.empty();
    } catch (java.time.format.DateTimeParseException e) {
      return java.util.Optional.of("is a " + type + " and must be " + shape);
    }
  }

  /**
   * Suggests the closest declared path to a mistyped one.
   *
   * <p>A typo is the most common finding and the least obvious to fix from a path alone, because
   * the integrator is looking at their own field name and it reads correctly to them.
   *
   * @param path the path that was not found
   * @return the nearest declared path, when one is close enough to be worth naming
   */
  public java.util.Optional<String> nearestPath(String path) {
    String best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (String candidate : schema.fields().keySet()) {
      int distance = editDistance(path, candidate);
      if (distance < bestDistance) {
        bestDistance = distance;
        best = candidate;
      }
    }
    // A third of the path's length, so a long path tolerates more than a short one and an
    // unrelated name is never suggested — a wrong suggestion sends someone down a blind alley.
    return bestDistance <= Math.max(2, path.length() / 3)
        ? java.util.Optional.ofNullable(best)
        : java.util.Optional.empty();
  }

  private static int editDistance(String a, String b) {
    int[] previous = new int[b.length() + 1];
    int[] current = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) {
      previous[j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      current[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
        current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return previous[b.length()];
  }

  /**
   * The paths the schema declares, for {@code bre facts schema}.
   *
   * @return path to field
   */
  public Map<String, FactSchema.Field> declaredFields() {
    return schema.fields();
  }
}
