package dev.bre.registry.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * A tenant's APPROVED shared assets, as the asset source document §8.4 resolution expects.
 *
 * <p><b>Only APPROVED versions, and that is the control.</b> AC-MODEL-007 requires a parameter
 * change to be approved by a second person before any policy version can pin it. Enforcing that
 * here — in the one place a bundle's thresholds come from — means it holds for the bundle builder,
 * for the test runner that must agree with it, and for the run endpoint, without each of them
 * remembering. A DRAFT parameter set simply does not exist as far as a policy is concerned.
 *
 * <p><b>It produces the same document a file does</b>, keyed {@code CODE@version}, so {@link
 * dev.bre.irjson.AssetCodec} stays the single implementation of §8.4 resolution. Building a second
 * shape here would be a second place the effective-window semantics could drift, and the two would
 * disagree first about the edge case nobody tested.
 */
@Component
public class ApprovedAssets {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final AssetRepository repository;
  private final AssetService service;

  /**
   * Creates the view.
   *
   * @param repository the asset tables
   * @param service reads one version's content in the bundle's own shape
   */
  public ApprovedAssets(AssetRepository repository, AssetService service) {
    this.repository = repository;
    this.service = service;
  }

  /**
   * Every approved asset this tenant has, as an asset source document.
   *
   * @param tenantId the tenant
   * @return {@code {parameterSets:{…}, referenceLists:{…}, reasonCodes:{…}, authorityMatrices:{…}}}
   *     keyed by {@code CODE@version}, or null when the tenant has no approved asset at all
   */
  public ObjectNode sourceFor(String tenantId) {
    ObjectNode source = JSON.createObjectNode();
    ObjectNode parameterSets = source.putObject("parameterSets");
    ObjectNode referenceLists = source.putObject("referenceLists");
    ObjectNode reasonCodes = source.putObject("reasonCodes");
    ObjectNode authorityMatrices = source.putObject("authorityMatrices");

    boolean any = false;
    for (AssetRepository.Header header : repository.list(tenantId, null)) {
      if (!"APPROVED".equals(header.status())) {
        continue;
      }
      any = true;
      String key = header.code() + "@" + header.version();
      ObjectNode content =
          service.contentOf(tenantId, header.kind(), header.code(), header.version());
      switch (header.kind()) {
        // A parameter set is keyed name -> {type, values:[…]}, which is the shape AssetCodec
        // reads. The stored content is an ARRAY of parameters because that is the order a bundle
        // carries; converting here keeps both readers happy without a second storage format.
        case PARAMETER_SET -> {
          ObjectNode set = parameterSets.putObject(key);
          for (var parameter : content.path("parameters")) {
            ObjectNode declared = set.putObject(parameter.path("name").asText());
            declared.put("type", parameter.path("type").asText());
            declared.set("values", parameter.path("values").deepCopy());
          }
        }
        case REFERENCE_LIST -> {
          ObjectNode list = referenceLists.putObject(key);
          list.put("type", content.path("itemType").asText("Text"));
          list.set("values", content.path("items").deepCopy());
        }
        case REASON_CODE_CATALOGUE -> reasonCodes.set(key, content.deepCopy());
        case AUTHORITY_MATRIX -> authorityMatrices.set(key, content.deepCopy());
      }
    }
    return any ? source : null;
  }
}
