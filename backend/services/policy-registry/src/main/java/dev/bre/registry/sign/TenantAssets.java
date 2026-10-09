package dev.bre.registry.sign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.bre.irjson.AssetCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Resolves a tenant's parameter sets, reference lists and Authority Matrix (§8.4). */
@Component
public class TenantAssets {

  private static final Logger log = LoggerFactory.getLogger(TenantAssets.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Path bundleDir;

  /**
   * Creates the resolver.
   *
   * @param bundleDir where the per-tenant asset source file lives
   */
  public TenantAssets(@Value("${bre.registry.bundle-dir:./var/bundles}") Path bundleDir) {
    this.bundleDir = bundleDir;
  }

  /**
   * A policy's resolved assets.
   *
   * @param assets the bundle's {@code assets} node — parameters and lists, by name
   * @param authorityMatrix the resolved matrix, to substitute into the IR
   * @param parameters the {@code param.} namespace, ready for an evaluation
   * @param lists the {@code list.} namespace
   * @param present whether the installation had an asset source at all
   */
  public record Resolved(
      ObjectNode assets,
      Optional<ObjectNode> authorityMatrix,
      Map<String, dev.bre.engine.eval.Value> parameters,
      Map<String, dev.bre.engine.eval.Value> lists,
      boolean present) {}

  /**
   * Resolves what a policy's IR names.
   *
   * <p><b>One resolver for both the test runner and the bundle builder, deliberately.</b> They had
   * been separate, and the consequence was specific and bad: the compile step ran a version's test
   * cases with EMPTY parameters, so every {@code param.X} was Missing and the reference policy —
   * which reads nineteen of them — referred every applicant. The test failed with "expected
   * APPROVE, got REFER" while the bundle those same assets would be signed into decided correctly.
   *
   * <p>A test that evaluates against different thresholds from the ones the bundle will carry is
   * worse than no test: it is a red signal with no defect behind it, and the way out is to stop
   * believing the tests.
   *
   * @param tenantId the tenant
   * @param irAssets the IR's {@code assets} member
   * @return the resolved assets
   */
  public Resolved resolve(String tenantId, JsonNode irAssets) {
    ObjectNode source = sourceFor(tenantId);
    if (source == null) {
      return new Resolved(JSON.createObjectNode(), Optional.empty(), Map.of(), Map.of(), false);
    }
    ObjectNode assets = AssetCodec.resolveForPolicy(source, irAssets);
    return new Resolved(
        assets,
        AssetCodec.resolveAuthorityMatrix(source, irAssets),
        AssetCodec.parameters(assets),
        AssetCodec.lists(assets),
        true);
  }

  /**
   * Whether a policy names any asset at all.
   *
   * @param ir the policy IR
   * @return true when it declares a parameter set or reference lists
   */
  public static boolean declaresAssets(JsonNode ir) {
    JsonNode assets = ir == null ? null : ir.get("assets");
    return assets != null
        && (!assets.path("parameterSet").isNull() || !assets.path("referenceLists").isEmpty());
  }

  /**
   * Where a tenant's asset source comes from.
   *
   * <p>ponytail: a JSON file per tenant under the bundle directory, which is the offline form the
   * CLI already uses. §5.2's {@code parameters}, {@code parameter_values}, {@code
   * reference_list_items} and {@code authority_entries} tables are the real home and are already
   * migrated; reading from them is M3-03, because it needs the maker-checker and effective-dating
   * those tables carry — and that is a control, not a query.
   *
   * <p>Named so the gap is obvious: a threshold edited in a file beside the policy is not a
   * threshold under maker-checker, and a lender's credit thresholds belong under maker-checker.
   */
  private ObjectNode sourceFor(String tenantId) {
    Path file = bundleDir.resolve("assets-" + tenantId + ".json");
    if (!Files.isRegularFile(file)) {
      return null;
    }
    try {
      return (ObjectNode) JSON.readTree(Files.readString(file));
    } catch (IOException | ClassCastException e) {
      log.error("the asset source {} could not be read", file, e);
      throw new IllegalStateException("the asset source " + file + " could not be read", e);
    }
  }
}
