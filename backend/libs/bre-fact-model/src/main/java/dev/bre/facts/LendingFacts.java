package dev.bre.facts;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The canonical lending fact schema, v1 (00 §3.9).
 *
 * <p>Loaded once from a resource. It is the schema every MVP policy is written against, and the one
 * the type checker resolves {@code application.*}, {@code parties[].*} and {@code group.*} through.
 */
public final class LendingFacts {

  /** The schema's code, as a policy's {@code factSchema} reference spells it. */
  public static final String CODE = "lending-facts";

  /** The version this build carries. */
  public static final String VERSION = "1.0.0";

  private static final String RESOURCE = "/dev/bre/facts/lending-facts-1.0.0.schema.json";

  private static final FactSchema SCHEMA = load();

  private LendingFacts() {}

  /**
   * Returns the canonical lending fact schema.
   *
   * @return the schema
   */
  public static FactSchema schema() {
    return SCHEMA;
  }

  /**
   * Returns the schema's JSON Schema document, verbatim.
   *
   * <p>The parsed {@link FactSchema} is a flattened path-to-type view and cannot be rendered back
   * into the original document — it keeps no {@code $defs}, no descriptions and no enum ordering.
   * The registry stores the document itself (§5.2 {@code fact_schemas.json_schema}) and hashes it,
   * so a lender can show an auditor the exact bytes their policies were validated against, which
   * means the bytes have to be available rather than reconstructed.
   *
   * @return the document
   */
  public static String schemaJson() {
    try (InputStream in = LendingFacts.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("the lending fact schema resource is missing: " + RESOURCE);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("the lending fact schema could not be read", e);
    }
  }

  private static FactSchema load() {
    try (InputStream in = LendingFacts.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("the lending fact schema resource is missing: " + RESOURCE);
      }
      return FactSchema.parse(CODE, VERSION, new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("the lending fact schema could not be read", e);
    }
  }
}
