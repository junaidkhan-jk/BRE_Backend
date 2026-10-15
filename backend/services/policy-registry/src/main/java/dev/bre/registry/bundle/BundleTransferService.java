package dev.bre.registry.bundle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bre.bundle.Bundle;
import dev.bre.bundle.BundleException;
import dev.bre.bundle.BundleSigner;
import dev.bre.bundle.BundleVerifier;
import dev.bre.bundle.TrustStore;
import dev.bre.registry.audit.AuditLog;
import dev.bre.registry.auth.Principal;
import dev.bre.registry.policy.PolicyVersionService;
import dev.bre.registry.sign.SigningKeySource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moving a signed bundle between installations (§8.6, LLD §6.5, M3-07).
 *
 * <p><b>This is not the same as importing PSF, and the difference matters.</b> Importing source
 * (M3-02) brings somebody else's <em>policy</em>, which this installation then compiles against its
 * own parameter sets and puts through its own review — so the thresholds are ours and the approval
 * is ours. Importing a bundle brings the <em>artefact</em>, byte for byte, with the exporting
 * installation's thresholds baked in and its approval already given. One is a policy you adopt; the
 * other is a decision you inherit.
 *
 * <p>Which is why a bundle import is a {@code DEPLOYMENT_APPROVER} act rather than an author's. The
 * person doing it is not reviewing a policy — they are accepting that another installation's review
 * stands here, and that is an approval in its own right.
 *
 * <p><b>Three checks, and none of them is optional:</b>
 *
 * <ol>
 *   <li><b>The hash is recomputed.</b> A bundle's address is a fact about its content, not a claim
 *       about it, so the content is hashed and compared rather than trusted.
 *   <li><b>The ORIGIN signature verifies against a trusted exporter.</b> Not against our own keys —
 *       the exporting installation signed it, and the point of the trusted-exporter list is that we
 *       name whose approvals we will accept.
 *   <li><b>It is countersigned IMPORT with our own key.</b> From then on a pod in this installation
 *       accepts it under its own trust store, which means a bundle cannot become servable here
 *       without this registry having verified it first. {@link BundleSigner#countersign} recomputes
 *       the hash again before signing, so this registry can never put its signature on content it
 *       did not actually hash.
 * </ol>
 *
 * <p><b>The imported version is recorded as APPROVED and {@code imported}.</b> It arrives with a
 * signed bundle, so the lifecycle's own CHECK requires that state — and an auditor reading the
 * version must be able to see that nobody here reviewed its contents. The audit entry names the
 * exporting key.
 */
@Service
public class BundleTransferService {

  private static final Logger log = LoggerFactory.getLogger(BundleTransferService.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * The largest bundle this will accept, in bytes.
   *
   * <p>A bundle is verified and held in memory by every decision pod, and the content is parsed
   * before it is checked — so the size limit has to come before the parse, not after. Sixteen
   * megabytes is far above the reference policy's forty kilobytes and far below anything that
   * threatens a pod.
   */
  public static final int MAX_BUNDLE_BYTES = 16 * 1024 * 1024;

  private final JdbcTemplate jdbc;
  private final SigningKeySource keys;
  private final AuditLog audit;
  private final Path bundleDir;
  private final Map<String, String> trustedExporters;

  /**
   * Creates the service.
   *
   * @param jdbc the control-plane database
   * @param keys this installation's signing keys
   * @param audit the hash-chained audit log
   * @param bundleDir where bundles are written for pods to fetch
   * @param trustedExporters kid to base64 X.509 public key, for installations whose ORIGIN
   *     signatures this one will accept. Empty by default: an installation that has not named
   *     anybody imports from nobody, which is the right default for a thing that accepts another
   *     party's approval.
   */
  public BundleTransferService(
      JdbcTemplate jdbc,
      SigningKeySource keys,
      AuditLog audit,
      @Value("${bre.registry.bundle-dir:./var/bundles}") Path bundleDir,
      @Value("#{${bre.registry.trusted-exporters:{:}}}") Map<String, String> trustedExporters) {
    this.jdbc = jdbc;
    this.keys = keys;
    this.audit = audit;
    this.bundleDir = bundleDir;
    this.trustedExporters = Map.copyOf(trustedExporters);
  }

  /**
   * What an import established.
   *
   * @param bundleHash the content address, recomputed
   * @param policyCode the policy the bundle declares
   * @param policyVersion the version it declares
   * @param originKid the exporting installation's key
   * @param countersignedBy this installation's key
   */
  public record Imported(
      String bundleHash,
      String policyCode,
      int policyVersion,
      String originKid,
      String countersignedBy) {}

  /**
   * Exports a bundle's bytes.
   *
   * <p>Returned exactly as it was signed. Re-serialising would change the bytes and therefore the
   * hash, and a bundle whose hash no longer matches is a bundle no pod will serve.
   *
   * @param tenantId the tenant
   * @param bundleHash the content address
   * @return the document, or empty when this installation does not hold it
   */
  public Optional<String> export(String tenantId, String bundleHash) {
    boolean known =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT exists(SELECT 1 FROM bre_ctl.bundles WHERE tenant_id = ?"
                    + " AND bundle_hash = ?)",
                Boolean.class,
                tenantId,
                bundleHash));
    if (!known) {
      // Checked against the DATABASE first, under row-level security, rather than against the
      // directory. The directory is shared by every tenant on this node; a path built from a
      // caller's parameter is how one lender reads another's policy.
      return Optional.empty();
    }
    Path file = bundleDir.resolve(bundleHash.replace(':', '-') + ".json");
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Files.readString(file));
    } catch (IOException unreadable) {
      throw new PolicyVersionService.LifecycleException(
          "bundle " + bundleHash + " is recorded but its bytes could not be read.");
    }
  }

  /**
   * Imports a bundle from another installation.
   *
   * @param principal who is importing — a deployment approver, because accepting another
   *     installation's review is an approval
   * @param document the exported bundle, as bytes
   * @return what was established
   * @throws PolicyVersionService.LifecycleException when any check fails
   */
  @Transactional
  public Imported importBundle(Principal principal, String document) {
    if (document == null || document.isBlank()) {
      throw new PolicyVersionService.LifecycleException("no bundle was supplied.");
    }
    if (document.length() > MAX_BUNDLE_BYTES) {
      throw new PolicyVersionService.LifecycleException(
          "the bundle is "
              + document.length()
              + " bytes and the limit is "
              + MAX_BUNDLE_BYTES
              + ". Every decision pod verifies and holds a bundle in memory, so the size is a"
              + " property of what a pod can serve rather than of what this endpoint can parse.");
    }
    if (trustedExporters.isEmpty()) {
      throw new PolicyVersionService.LifecycleException(
          "this installation trusts no exporters, so it can import no bundle. Importing means"
              + " accepting another installation's approval as binding here; set"
              + " bre.registry.trusted-exporters to the public keys of the installations whose"
              + " approvals you accept, and nobody else's.");
    }

    Bundle bundle;
    try {
      bundle = Bundle.parse(document);
    } catch (RuntimeException notABundle) {
      throw new PolicyVersionService.LifecycleException(
          "this is not a readable bundle: " + notABundle.getMessage());
    }

    // The ORIGIN signature is verified against the TRUSTED EXPORTERS, not against our own keys.
    // The exporting installation signed it; the whole function of the trusted-exporter list is
    // that we say in advance whose approvals we will accept.
    TrustStore.Builder trust = TrustStore.builder();
    trustedExporters.forEach((kid, encoded) -> trust.trustedExporter(kid, publicKey(kid, encoded)));
    BundleVerifier.Verified verified;
    try {
      verified = new BundleVerifier(trust.build()).verify(bundle);
    } catch (BundleException refused) {
      throw new PolicyVersionService.LifecycleException(
          "the bundle was refused: "
              + refused.getMessage()
              + ". An importing installation accepts a bundle only from a key it has named as a"
              + " trusted exporter, and only when the signature verifies over the content as"
              + " hashed here.");
    }
    if (verified.role() != Bundle.Role.ORIGIN) {
      // An IMPORT countersignature speaks for the installation that imported it, not for the one
      // that approved the policy. Accepting a chain of them would mean trusting a party we never
      // named, through one we did.
      throw new PolicyVersionService.LifecycleException(
          "the only signature this installation trusts on that bundle has role "
              + verified.role()
              + ", not ORIGIN. An IMPORT countersignature speaks for whoever imported the bundle,"
              + " not for whoever approved the policy — so importing from it would mean trusting a"
              + " party you never named.");
    }

    String tenantId = principal.tenantId();
    JsonNode manifest = bundle.manifest();
    String policyCode = manifest.path("policyCode").asText("");
    int policyVersion = manifest.path("policyVersion").asInt(0);
    if (policyCode.isBlank() || policyVersion < 1) {
      throw new PolicyVersionService.LifecycleException(
          "the bundle's manifest names no policy and version, so there is nothing to record it"
              + " against.");
    }

    // The bundle's own tenant is the EXPORTING installation's. The importing tenant is the
    // caller's, and the mapping is deliberate: two lenders may use different tenant ids for the
    // same book of business, and a bundle that could only be imported under its original id
    // would be importable only by the installation that made it.
    String exportingTenant = manifest.path("tenantId").asText("");

    Bundle countersigned =
        BundleSigner.countersign(
            bundle, keys.bundleKey().kid(), Bundle.Role.IMPORT, keys.bundleKey().privateKey());
    String stored = countersigned.toJson();

    try {
      Files.createDirectories(bundleDir);
      Files.writeString(
          bundleDir.resolve(countersigned.bundleHash().replace(':', '-') + ".json"), stored);
    } catch (IOException unwritable) {
      throw new PolicyVersionService.LifecycleException(
          "the bundle verified but could not be written to " + bundleDir + ".");
    }

    // The version row first: bundles has a foreign key to it. APPROVED because it arrives with a
    // signed bundle and §5.2's CHECK ties those together — and `imported` so an auditor can see
    // that nobody here reviewed the contents.
    jdbc.update(
        """
        INSERT INTO bre_ctl.policies (tenant_id, policy_code, name, stage, created_by)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (tenant_id, policy_code) DO NOTHING
        """,
        tenantId,
        policyCode,
        policyCode,
        manifest.path("stage").asText("CREDIT_UW"),
        principal.subject());

    jdbc.update(
        """
        INSERT INTO bre_ctl.policy_versions (
            tenant_id, policy_code, version, status, ir, fact_schema_code, fact_schema_version,
            imported, bundle_hash, approved_at, submitted_at, change_note, committee_reference,
            created_by)
        VALUES (?, ?, ?, 'APPROVED', ?::jsonb, ?, ?, true, ?, now(), now(), ?, ?, ?)
        ON CONFLICT (tenant_id, policy_code, version) DO NOTHING
        """,
        tenantId,
        policyCode,
        policyVersion,
        bundle.ir().toString(),
        bundle.ir().path("factSchema").path("code").asText("lending-facts"),
        bundle.ir().path("factSchema").path("version").asText("1.0.0"),
        countersigned.bundleHash(),
        "Imported as a signed bundle from "
            + (exportingTenant.isBlank() ? "another installation" : exportingTenant)
            + ", approved there and countersigned here.",
        "IMPORT/" + verified.kid(),
        principal.subject());

    jdbc.update(
        """
        INSERT INTO bre_ctl.bundles (
            tenant_id, bundle_hash, policy_code, policy_version, origin, object_key,
            size_bytes, ir_version, engine_min_version, signatures)
        VALUES (?, ?, ?, ?, 'IMPORTED', ?, ?, ?, ?, ?::jsonb)
        ON CONFLICT (tenant_id, bundle_hash) DO NOTHING
        """,
        tenantId,
        countersigned.bundleHash(),
        policyCode,
        policyVersion,
        "bundles/" + tenantId + "/" + countersigned.bundleHash().replace(':', '-'),
        stored.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
        bundle.ir().path("irVersion").asInt(1),
        manifest.path("engine").path("minVersion").asText("0.1.0"),
        JSON.valueToTree(countersigned.signatures()).toString());

    audit.record(
        tenantId,
        principal.subject(),
        "BUNDLE.IMPORTED",
        "bundle",
        countersigned.bundleHash(),
        "{\"policy\":\""
            + policyCode
            + "\",\"version\":"
            + policyVersion
            + ",\"originKid\":\""
            + verified.kid()
            + "\",\"exportingTenant\":\""
            + exportingTenant
            + "\",\"countersignedBy\":\""
            + keys.bundleKey().kid()
            + "\"}");
    log.warn(
        "BUNDLE IMPORTED: {} for policy {} v{}, approved by the installation holding key {} and"
            + " countersigned here by {}. Nobody in this installation reviewed its contents.",
        countersigned.bundleHash(),
        policyCode,
        policyVersion,
        verified.kid(),
        keys.bundleKey().kid());

    return new Imported(
        countersigned.bundleHash(),
        policyCode,
        policyVersion,
        verified.kid(),
        keys.bundleKey().kid());
  }

  private static PublicKey publicKey(String kid, String base64) {
    try {
      return java.security.KeyFactory.getInstance("Ed25519")
          .generatePublic(
              new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
    } catch (java.security.GeneralSecurityException | IllegalArgumentException unreadable) {
      // Two unrelated failures of the same configuration mistake: not a key, or not base64.
      throw new IllegalStateException(
          "the trusted exporter key '" + kid + "' is not a base64 X.509 Ed25519 public key",
          unreadable);
    }
  }
}
