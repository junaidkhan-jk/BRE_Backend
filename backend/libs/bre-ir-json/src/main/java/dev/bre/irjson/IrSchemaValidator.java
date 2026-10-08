package dev.bre.irjson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Validates an IR document against the PolicyVersion JSON Schema (LLD §2.2).
 *
 * <p>The schema is the one in §2.2, extracted verbatim to a resource rather than retyped, because a
 * hand-copied schema drifts from the specification silently and then disagrees with the studio and
 * the control plane about what a legal policy is.
 *
 * <p>It is draft 2020-12 with {@code additionalProperties: false} throughout and a set of
 * conditional requirements that are real constraints rather than decoration — a DEVIATION outcome
 * must carry a {@code deviationType} and a {@code reasonCode}, because an approver cannot act on an
 * unnamed tolerance; a FAIL must not carry a {@code measure}, because there is no tolerance to
 * measure it against. Validating here means those hold before a policy is ever evaluated.
 *
 * <p>The validator is built once and is immutable, so it costs nothing per call and is safe to
 * share across threads.
 */
public final class IrSchemaValidator {

  private static final String RESOURCE = "/dev/bre/irjson/policy-version-1.schema.json";

  private static final JsonSchema SCHEMA = load();

  private IrSchemaValidator() {}

  /** Thrown when an IR document is not a legal PolicyVersion. */
  public static final class IrSchemaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<String> violations;

    IrSchemaException(List<String> violations) {
      super(
          "the IR document is not a valid PolicyVersion ("
              + violations.size()
              + (violations.size() == 1 ? " violation): " : " violations): ")
              + String.join("; ", violations));
      this.violations = List.copyOf(violations);
    }

    /**
     * Returns every violation, in document order.
     *
     * @return the violations, each naming the path it occurred at
     */
    public List<String> violations() {
      return violations;
    }
  }

  /**
   * Validates an IR document, throwing on the first document that is not legal.
   *
   * @param ir the IR document
   * @throws IrSchemaException if it violates the schema, listing every violation rather than only
   *     the first — an author fixing a policy wants the whole list
   */
  public static void validate(JsonNode ir) {
    List<String> violations = violations(ir);
    if (!violations.isEmpty()) {
      throw new IrSchemaException(violations);
    }
  }

  /**
   * Returns every way an IR document violates the schema.
   *
   * @param ir the IR document
   * @return the violations, sorted by path; empty when the document is legal
   */
  public static List<String> violations(JsonNode ir) {
    Set<ValidationMessage> messages = SCHEMA.validate(ir);
    // Sorted by message text, which begins with the instance path: a stable order, so that two
    // runs over the same document report the same list and a diff of CI output means something.
    return messages.stream()
        .map(ValidationMessage::getMessage)
        .collect(Collectors.toCollection(TreeSet::new))
        .stream()
        .toList();
  }

  private static JsonSchema load() {
    try (InputStream in = IrSchemaValidator.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("the IR schema resource is missing: " + RESOURCE);
      }
      JsonNode schema = new ObjectMapper().readTree(in);
      SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().build();
      return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
          .getSchema(schema, config);
    } catch (IOException e) {
      throw new IllegalStateException("the IR schema resource could not be read", e);
    }
  }
}
