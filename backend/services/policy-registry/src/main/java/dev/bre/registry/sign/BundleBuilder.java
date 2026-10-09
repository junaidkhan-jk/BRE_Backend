package dev.bre.registry.sign;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.bre.bundle.Bundle;
import dev.bre.bundle.BundleSigner;
import dev.bre.engine.check.CheckFinding;
import dev.bre.engine.check.StaticChecks;
import dev.bre.irjson.AssetCodec;
import dev.bre.irjson.IrCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds and signs a bundle at the moment a version is approved (§8.1, D4/D5).
 *
 * <p><b>Signing happens inside the approval transaction, not in a release step afterwards.</b> The
 * artefact a pod will serve is produced from the exact IR that was reviewed, at the moment the
 * approval is recorded. A build step that ran later could build from something else — a
 * re-translated source, a different compiler version, a merge that landed in between — and the
 * audit log would show an approval of a document that was never deployed.
 *
 * <p><b>It refuses to sign a policy that fails its own static checks</b>, even though the submit
 * gate already checked. Not redundant: the gate ran against the compile report stored at save time,
 * and this runs against the IR about to be signed. If those ever disagree, the disagreement is the
 * finding — and a signature over a known decision-table gap is worse than no signature, because the
 * signature is what everything downstream trusts instead of looking.
 *
 * <p><b>Assets are resolved into the bundle</b> (§8.4). D2 makes a decision a pure function of
 * {@code (facts, policy, asOf)}, so a parameter fetched at decision time would mean two replays of
 * the same decision could differ. Resolving them here makes a parameter change a new bundle with a
 * new hash.
 */
@Component
public class BundleBuilder {

  private static final Logger log = LoggerFactory.getLogger(BundleBuilder.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final SigningKeySource keys;
  private final Path bundleDir;
  private final String engineVersion;

  /**
   * Creates the builder.
   *
   * @param keys where the bundle signing key comes from
   * @param bundleDir where built bundles are written for pods to fetch
   * @param engineVersion the minimum engine version recorded on the bundle
   */
  public BundleBuilder(
      SigningKeySource keys,
      @Value("${bre.registry.bundle-dir:./var/bundles}") Path bundleDir,
      @Value("${bre.engine-version:0.1.0-SNAPSHOT}") String engineVersion) {
    this.keys = keys;
    this.bundleDir = bundleDir;
    this.engineVersion = engineVersion;
  }

  /**
   * A built, signed bundle.
   *
   * @param bundleHash the content address
   * @param document the bundle JSON
   * @param sizeBytes its size
   * @param irVersion the IR version inside it
   * @param engineMinVersion the minimum engine version that can evaluate it
   * @param signaturesJson the signatures, for the {@code bundles} row
   */
  public record Built(
      String bundleHash,
      String document,
      long sizeBytes,
      int irVersion,
      String engineMinVersion,
      String signaturesJson) {}

  /**
   * The bundle could not be built. Mapped to 409 — an approval that cannot sign has not happened.
   */
  public static final class BuildException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    BuildException(String message) {
      super(message);
    }

    BuildException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Builds and signs.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version
   * @param ir the reviewed IR
   * @return the signed bundle
   */
  public Built build(String tenantId, String policyCode, int version, ObjectNode ir) {
    if (ir == null || ir.isEmpty()) {
      throw new BuildException(
          "version "
              + version
              + " of "
              + policyCode
              + " has no compiled IR, so there is nothing to sign. It was approved without ever"
              + " being saved, which the submit gate should have prevented — report this.");
    }

    List<CheckFinding> errors =
        StaticChecks.run(IrCodec.readPolicy(ir)).stream().filter(CheckFinding::isError).toList();
    if (!errors.isEmpty()) {
      throw new BuildException(
          "refusing to sign version "
              + version
              + " of "
              + policyCode
              + ": "
              + errors.size()
              + " static-check error(s) against the IR about to be signed. The submit gate passed"
              + " on the report stored at save time, so these two disagree and that disagreement"
              + " is the defect. First: "
              + errors.getFirst());
    }

    // Assets resolved into the bundle, where the policy names any. Without a resolved snapshot
    // every `param.X` is Missing at evaluation and the policy refers every applicant, so a bundle
    // signed without them is a bundle that cannot decide.
    ObjectNode assets = JSON.createObjectNode();
    ObjectNode irToSign = ir.deepCopy();
    try {
      ObjectNode source = assetSource(tenantId);
      if (source != null) {
        assets = AssetCodec.resolveForPolicy(source, irToSign.get("assets"));
        AssetCodec.resolveAuthorityMatrix(source, irToSign.get("assets"))
            .ifPresent(
                matrix -> ((ObjectNode) irToSign.get("assets")).set("authorityMatrix", matrix));
      } else if (declaresAssets(irToSign)) {
        log.warn(
            "policy {}@{} for tenant {} names a parameter set or reference lists but this"
                + " installation has no asset source configured, so the bundle carries no resolved"
                + " values and every param./list. reference will be Missing at evaluation.",
            policyCode,
            version,
            tenantId);
      }
    } catch (RuntimeException e) {
      throw new BuildException(
          "the assets version "
              + version
              + " of "
              + policyCode
              + " names could not be resolved: "
              + e.getMessage(),
          e);
    }

    ObjectNode manifest = JSON.createObjectNode();
    manifest.put("policyCode", policyCode);
    manifest.put("policyVersion", version);
    manifest.set("factSchema", irToSign.get("factSchema"));
    manifest.putObject("staticCheck").put("errors", 0);

    SigningKeySource.Key key = keys.bundleKey();
    Bundle bundle =
        BundleSigner.sign(
            manifest, irToSign, assets, key.kid(), Bundle.Role.ORIGIN, key.privateKey());

    String document = bundle.toJson();
    writeForPods(bundle.bundleHash(), document);

    return new Built(
        bundle.bundleHash(),
        document,
        document.getBytes(StandardCharsets.UTF_8).length,
        irToSign.path("irVersion").asInt(1),
        engineVersion,
        signaturesJson(bundle));
  }

  /**
   * Where a tenant's asset source comes from.
   *
   * <p>ponytail: a JSON file per tenant under the bundle directory, which is the offline form the
   * CLI already uses. §5.2's {@code parameters}, {@code parameter_values}, {@code
   * reference_list_items} and {@code authority_entries} tables are the real home and are already
   * migrated; reading from them is M3-03, because it needs the maker-checker and effective-dating
   * those tables carry and that is a separate control, not a query.
   *
   * <p>Named so the gap is obvious: a parameter edited in a file beside the policy is not a
   * parameter under maker-checker, and a lender's thresholds belong under maker-checker.
   */
  private ObjectNode assetSource(String tenantId) {
    Path file = bundleDir.resolve("assets-" + tenantId + ".json");
    if (!Files.isRegularFile(file)) {
      return null;
    }
    try {
      return (ObjectNode) JSON.readTree(Files.readString(file));
    } catch (IOException | ClassCastException e) {
      throw new BuildException("the asset source " + file + " could not be read", e);
    }
  }

  private static boolean declaresAssets(ObjectNode ir) {
    var assets = ir.get("assets");
    return assets != null
        && (!assets.path("parameterSet").isNull() || !assets.path("referenceLists").isEmpty());
  }

  private void writeForPods(String bundleHash, String document) {
    try {
      Files.createDirectories(bundleDir);
      // Content-addressed file name, so a bundle filed under the wrong name cannot masquerade as
      // the right one — the pod compares the address it asked for with the address of what came
      // back, and then recomputes that from the content.
      Files.writeString(bundleDir.resolve(bundleHash.replace(':', '-') + ".json"), document);
    } catch (IOException e) {
      throw new BuildException(
          "the bundle was signed but could not be written to "
              + bundleDir
              + ", so no pod could"
              + " fetch it. The approval is rolled back.",
          e);
    }
  }

  private static String signaturesJson(Bundle bundle) {
    ObjectNode out = JSON.createObjectNode();
    var array = out.putArray("signatures");
    bundle
        .signatures()
        .forEach(
            signature -> {
              ObjectNode node = array.addObject();
              node.put("kid", signature.kid());
              node.put("alg", signature.alg());
              node.put("role", signature.role().name());
            });
    return out.toString();
  }
}
