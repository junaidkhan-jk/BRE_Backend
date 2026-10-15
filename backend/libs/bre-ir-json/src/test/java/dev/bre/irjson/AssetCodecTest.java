package dev.bre.irjson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.bre.engine.eval.ParameterTable;
import dev.bre.engine.eval.Value;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Asset resolution: what the bundle carries, and what it refuses to carry (§8.4). */
class AssetCodecTest {

  /** Any date at all: a parameter declared without effective windows applies on every date. */
  private static final java.time.LocalDate ANY_DATE = java.time.LocalDate.parse("2026-10-01");

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String SOURCE =
      """
      { "parameterSets": {
          "P@1": { "CAP":    { "type": "Decimal", "value": "200000.00" },
                   "MAX":    { "type": "Integer", "value": 3 },
                   "BUREAU": { "type": "Text",    "value": "HIGHMARK" },
                   "ON":     { "type": "Boolean", "value": true },
                   "FROM":   { "type": "Date",    "value": "2026-04-01" } },
          "OTHER@1": { "UNUSED": { "type": "Integer", "value": 1 } } },
        "referenceLists": {
          "OPEN@2": { "type": "Text", "values": ["ACTIVE", "OVERDUE"] } },
        "authorityMatrices": {
          "DOP@1": { "routingMode": "HIGHEST_ONLY",
                     "levels": [ { "levelCode": "BM", "rank": 1 } ],
                     "entries": [ { "entryNo": 1, "deviationType": "CAP", "levelCode": "BM" } ] } } }
      """;

  private static ObjectNode irAssets(String parameterSet, String matrix, String... lists) {
    ObjectNode assets = JSON.createObjectNode();
    if (parameterSet == null) {
      assets.putNull("parameterSet");
    } else {
      assets.putObject("parameterSet").put("code", code(parameterSet)).put("version", 1);
    }
    var array = assets.putArray("referenceLists");
    for (String list : lists) {
      array.addObject().put("code", code(list)).put("version", version(list));
    }
    if (matrix == null) {
      assets.putNull("authorityMatrix");
    } else {
      assets.putObject("authorityMatrix").put("code", code(matrix)).put("version", 1);
    }
    return assets;
  }

  private static String code(String ref) {
    return ref.substring(0, ref.indexOf('@'));
  }

  private static int version(String ref) {
    return Integer.parseInt(ref.substring(ref.indexOf('@') + 1));
  }

  private static JsonNode source() throws IOException {
    return JSON.readTree(SOURCE);
  }

  @Test
  @DisplayName("every declared type round-trips into the engine's value model")
  void everyTypeRoundTrips() throws IOException {
    ObjectNode resolved = AssetCodec.resolve(source(), "P@1", List.of("OPEN@2"));
    ParameterTable table = AssetCodec.parameters(resolved);
    // Any date: these are declared without effective windows, which means open-ended.
    Map<String, Value> parameters = new java.util.LinkedHashMap<>();
    table.names().forEach(name -> parameters.put(name, table.valueAt(name, ANY_DATE)));

    assertThat(parameters.get("CAP")).isEqualTo(new Value.DecimalV(new BigDecimal("200000.00")));
    assertThat(parameters.get("MAX")).isEqualTo(new Value.IntV(3));
    assertThat(parameters.get("BUREAU")).isEqualTo(new Value.TextV("HIGHMARK"));
    assertThat(parameters.get("ON")).isEqualTo(Value.BoolV.of(true));
    assertThat(parameters.get("FROM"))
        .isEqualTo(new Value.DateV(java.time.LocalDate.of(2026, 4, 1)));

    assertThat(AssetCodec.lists(resolved).get("OPEN"))
        .as("a policy writes list.OPEN; the @2 is what was resolved, not part of the name")
        .isEqualTo(new Value.ListV(List.of(new Value.TextV("ACTIVE"), new Value.TextV("OVERDUE"))));
  }

  @Test
  @DisplayName("a decimal written as a bare number is refused, not rounded")
  void bareDecimalIsRefused() throws IOException {
    JsonNode bad =
        JSON.readTree(
            """
            { "parameterSets": { "P@1": { "CAP": { "type": "Decimal", "value": 0.1 } } } }
            """);
    assertThatThrownBy(() -> AssetCodec.resolve(bad, "P@1", List.of()))
        .isInstanceOf(PsfException.class)
        .hasMessageContaining("ASSET-002")
        .hasMessageContaining("QUOTED");
  }

  @Test
  @DisplayName("only the assets the policy names go into the bundle")
  void unusedAssetsAreDropped() throws IOException {
    // Otherwise two bundles with identical behaviour would have different hashes, because the hash
    // would cover parameter sets no decision can reach.
    ObjectNode resolved = AssetCodec.resolve(source(), "P@1", List.of("OPEN@2"));
    assertThat(AssetCodec.parameters(resolved).names()).doesNotContain("UNUSED");
  }

  @Test
  @DisplayName("a policy naming an asset the source does not define fails the build")
  void missingAssetFailsTheBuild() throws IOException {
    assertThatThrownBy(() -> AssetCodec.resolve(source(), "NOPE@1", List.of()))
        .isInstanceOf(PsfException.class)
        .hasMessageContaining("ASSET-001")
        .as("the message must name what IS defined, or the author has to guess")
        .hasMessageContaining("P@1");
  }

  @Test
  @DisplayName("the Authority Matrix resolves with its levels and keeps the policy's own code")
  void authorityMatrixResolves() throws IOException {
    ObjectNode resolved =
        AssetCodec.resolveAuthorityMatrix(source(), irAssets("P@1", "DOP@1")).orElseThrow();

    assertThat(resolved.get("code").asText()).isEqualTo("DOP");
    assertThat(resolved.get("version").asInt()).isEqualTo(1);
    assertThat(resolved.get("levels")).hasSize(1);
    assertThat(resolved.get("entries")).hasSize(1);
  }

  @Test
  @DisplayName("a matrix with no levels is refused: it would approve nothing while looking present")
  void matrixWithNoLevelsIsRefused() throws IOException {
    JsonNode empty =
        JSON.readTree(
            """
            { "authorityMatrices": { "DOP@1": { "routingMode": "HIGHEST_ONLY",
                                                "levels": [], "entries": [] } } }
            """);
    assertThatThrownBy(() -> AssetCodec.resolveAuthorityMatrix(empty, irAssets(null, "DOP@1")))
        .isInstanceOf(PsfException.class)
        .hasMessageContaining("approves nothing");
  }

  @Test
  @DisplayName("a policy with no assets resolves to empty rather than failing")
  void noAssetsIsNotAnError() throws IOException {
    ObjectNode resolved = AssetCodec.resolveForPolicy(source(), irAssets(null, null));
    assertThat(AssetCodec.parameters(resolved).isEmpty()).isTrue();
    assertThat(AssetCodec.lists(resolved)).isEmpty();
    assertThat(AssetCodec.resolveAuthorityMatrix(source(), irAssets(null, null))).isEmpty();
  }
}
