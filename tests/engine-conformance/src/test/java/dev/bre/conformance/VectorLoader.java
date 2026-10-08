package dev.bre.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Reads the conformance vectors from {@code tests/engine-conformance/vectors}. */
final class VectorLoader {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNode EMPTY_OBJECT = JsonNodeFactory.instance.objectNode();

  private VectorLoader() {}

  /** Returns the directory holding the vectors, as wired by the build. */
  static Path vectorDirectory() {
    String configured = System.getProperty("bre.conformance.vectors");
    if (configured == null) {
      throw new IllegalStateException(
          "bre.conformance.vectors is not set; the build must point the runner at the vectors "
              + "directory (see tests/engine-conformance/build.gradle.kts)");
    }
    return Path.of(configured);
  }

  /**
   * Loads every vector, failing loudly on a malformed file or a duplicate id.
   *
   * <p>A malformed vector must never be silently skipped: a vector that does not load is a piece of
   * the specification that is not being checked, and the whole point of the suite is that it is
   * complete.
   *
   * @return every vector found, ordered by id
   */
  static List<ConformanceVector> loadAll() {
    Path directory = vectorDirectory();
    if (!Files.isDirectory(directory)) {
      throw new IllegalStateException("vectors directory does not exist: " + directory);
    }

    List<ConformanceVector> vectors = new ArrayList<>();
    Map<String, String> seenIds = new HashMap<>();

    try (Stream<Path> files = Files.walk(directory)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
        JsonNode root = MAPPER.readTree(Files.readString(file));
        // A file holds either one vector or an array of them, so related vectors can be grouped.
        for (JsonNode node : root.isArray() ? root : List.of(root)) {
          ConformanceVector vector = read(node, directory.relativize(file).toString());
          String previous = seenIds.put(vector.id(), vector.source());
          if (previous != null) {
            throw new IllegalStateException(
                "duplicate vector id "
                    + vector.id()
                    + " in "
                    + vector.source()
                    + " and "
                    + previous);
          }
          // shape() validates the expr/policy invariant; call it at load time so a malformed
          // vector fails the suite rather than one test.
          vector.shape();
          vectors.add(vector);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("could not read the conformance vectors", e);
    }

    return vectors.stream().sorted(java.util.Comparator.comparing(ConformanceVector::id)).toList();
  }

  private static ConformanceVector read(JsonNode node, String source) {
    return new ConformanceVector(
        required(node, "id", source).asText(),
        required(node, "description", source).asText(),
        required(node, "asOf", source).asText(),
        optionalText(node, "scope"),
        node.hasNonNull("params") ? node.get("params") : EMPTY_OBJECT,
        node.hasNonNull("lists") ? node.get("lists") : EMPTY_OBJECT,
        node.hasNonNull("facts") ? node.get("facts") : EMPTY_OBJECT,
        optionalText(node, "expr"),
        node.hasNonNull("policy") ? Optional.of(node.get("policy")) : Optional.empty(),
        required(node, "expect", source),
        source);
  }

  private static JsonNode required(JsonNode node, String field, String source) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalStateException(
          "vector in " + source + " is missing the required field '" + field + "'");
    }
    return value;
  }

  private static Optional<String> optionalText(JsonNode node, String field) {
    return node.hasNonNull(field) ? Optional.of(node.get(field).asText()) : Optional.empty();
  }
}
