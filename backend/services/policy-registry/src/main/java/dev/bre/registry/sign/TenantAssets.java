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
  private final dev.bre.registry.asset.ApprovedAssets assets;

  /**
   * Creates the resolver.
   *
   * @param bundleDir where the per-tenant asset source file lives, for the offline fallback
   * @param assets the tenant's APPROVED shared assets, from §5.2's tables
   */
  public TenantAssets(
      @Value("${bre.registry.bundle-dir:./var/bundles}") Path bundleDir,
      dev.bre.registry.asset.ApprovedAssets assets) {
    this.bundleDir = bundleDir;
    this.assets = assets;
  }

  /**
   * A policy's resolved assets.
   *
   * @param assets the bundle's {@code assets} node — parameters and lists, by name
   * @param authorityMatrix the resolved matrix, to substitute into the IR
   * @param parameters the {@code param.} namespace with each parameter's effective windows, ready
   *     for an evaluation
   * @param lists the {@code list.} namespace
   * @param present whether the installation had an asset source at all
   */
  public record Resolved(
      ObjectNode assets,
      Optional<ObjectNode> authorityMatrix,
      dev.bre.engine.eval.ParameterTable parameters,
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
      return new Resolved(
          JSON.createObjectNode(),
          Optional.empty(),
          dev.bre.engine.eval.ParameterTable.EMPTY,
          Map.of(),
          false);
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
   * <p><b>The database first, APPROVED versions only.</b> §5.2's asset tables carry the
   * maker-checker and the effective dating, and that is the whole point: a threshold edited in a
   * file beside the policy is not a threshold under maker-checker, and a lender's credit thresholds
   * belong under maker-checker. A DRAFT or IN_REVIEW asset is invisible here by construction, which
   * is what AC-MODEL-007 asks for — a parameter change cannot be pinned by any policy version until
   * a second person has approved it.
   *
   * <p><b>The file is the fallback, and it is still useful.</b> {@code bre psf bundle --assets}
   * works offline with no registry and no database, which is how an integrator tries a policy
   * against their own thresholds; the acceptance suite uses it too. So a file is read when the
   * tenant has no approved asset of a kind the policy names, and the fact that a file supplied a
   * threshold is logged — because in a deployed installation that is a finding.
   */
  private ObjectNode sourceFor(String tenantId) {
    ObjectNode fromDatabase = assets.sourceFor(tenantId);
    ObjectNode fromFile = sourceFile(tenantId);
    if (fromFile == null) {
      return fromDatabase;
    }
    if (fromDatabase == null) {
      log.warn(
          "tenant {} has no APPROVED shared assets, so its thresholds are coming from"
              + " assets-{}.json. A threshold in a file is a threshold outside maker-checker; in a"
              + " deployed installation that is a finding.",
          tenantId,
          tenantId);
      return fromFile;
    }
    // Merged with the database winning, so an approved asset is never shadowed by a stale file —
    // the other order would let somebody change a signed-off threshold by editing a file.
    return merge(fromFile, fromDatabase);
  }

  /**
   * Overlays one asset source on another, by {@code code@version} key.
   *
   * <p>Shallow by design: an asset version is replaced whole or not at all, because half of {@code
   * MFI_PARAMS@7} from the database and half from a file is a parameter set nobody approved.
   */
  private static ObjectNode merge(ObjectNode base, ObjectNode overlay) {
    ObjectNode merged = base.deepCopy();
    overlay
        .properties()
        .forEach(
            section -> {
              if (!section.getValue().isObject()) {
                merged.set(section.getKey(), section.getValue().deepCopy());
                return;
              }
              ObjectNode target =
                  merged.path(section.getKey()).isObject()
                      ? (ObjectNode) merged.get(section.getKey())
                      : merged.putObject(section.getKey());
              section
                  .getValue()
                  .properties()
                  .forEach(entry -> target.set(entry.getKey(), entry.getValue().deepCopy()));
            });
    return merged;
  }

  private ObjectNode sourceFile(String tenantId) {
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
