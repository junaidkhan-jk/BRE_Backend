package dev.bre.bundle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.bre.irjson.BreJcs;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * The Active Pointer Manifest: what a cell's pods are allowed to serve (LLD §8.1).
 *
 * <p>Signed and monotonically versioned per cell. It names, per tenant and environment, the
 * champion, any challengers, the shadows, and — separately — the {@code servable} set: every bundle
 * that was champion either now or recently enough to still be honoured for a pinned request.
 *
 * <p><b>Signed over a different message from a bundle, deliberately.</b> {@code
 * UTF-8("bre-manifest/1:") || SHA-256(BRE-JCS(manifest without "signature"))}. A bundle signs
 * {@code "bre-bundle/1:"} over its own hash. Without distinct prefixes a manifest signature and a
 * bundle signature would be interchangeable, and an attacker holding one valid signature could
 * present it as the other.
 *
 * <p>The manifest key is also a different key: one per cell, distinct from the installation's
 * bundle key (§8.6). A compromise of the thing that says <em>what to serve</em> is not
 * automatically a compromise of the thing that says <em>what a policy contains</em>.
 */
public record PointerManifest(
    int cellId,
    long manifestVersion,
    Instant issuedAt,
    List<Entry> entries,
    Optional<Bundle.Signature> signature) {

  /** The only manifest format this build understands. */
  public static final String FORMAT = "bre-manifest/1";

  /** The domain-separation prefix for a manifest signature (§8.1). */
  public static final String SIGNING_PREFIX = "bre-manifest/1:";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * What one tenant, environment and policy may serve.
   *
   * @param tenantId the tenant
   * @param environment the environment code
   * @param policyCode the policy
   * @param timeZone the tenant's zone, for day boundaries
   * @param pinningAllowed whether a caller may pin a bundle hash at all
   * @param champion the bundle served by default
   * @param challengers bundles served to their bucket ranges
   * @param servable every bundle a pinned request may still ask for
   */
  public record Entry(
      String tenantId,
      String environment,
      String policyCode,
      String timeZone,
      boolean pinningAllowed,
      Target champion,
      List<Challenger> challengers,
      List<Servable> servable) {

    /** Creates an entry, copying the lists. */
    public Entry {
      challengers = List.copyOf(challengers);
      servable = List.copyOf(servable);
    }
  }

  /**
   * A deployment and the bundle it serves.
   *
   * @param deploymentId the deployment
   * @param bundleHash the bundle's content address
   */
  public record Target(String deploymentId, String bundleHash) {}

  /**
   * A challenger and the bucket range it serves.
   *
   * @param deploymentId the deployment
   * @param bundleHash the bundle
   * @param experimentId the experiment all concurrent challengers share
   * @param bucketFrom inclusive lower bucket
   * @param bucketTo exclusive upper bucket
   */
  public record Challenger(
      String deploymentId, String bundleHash, String experimentId, int bucketFrom, int bucketTo) {}

  /**
   * A bundle a pinned request may ask for.
   *
   * @param bundleHash the bundle
   * @param deploymentId the deployment that served it
   * @param championUntil when it stopped being champion, or empty while it still is
   */
  public record Servable(String bundleHash, String deploymentId, Optional<Instant> championUntil) {}

  /** Creates a manifest, copying the entry list. */
  public PointerManifest {
    entries = List.copyOf(entries);
  }

  /**
   * Finds the entry for a tenant, environment and policy.
   *
   * @param tenantId the tenant
   * @param environment the environment
   * @param policyCode the policy
   * @return the entry, or empty when this cell serves nothing for it
   */
  public Optional<Entry> entry(String tenantId, String environment, String policyCode) {
    return entries.stream()
        .filter(
            e ->
                e.tenantId().equals(tenantId)
                    && e.environment().equals(environment)
                    && e.policyCode().equals(policyCode))
        .findFirst();
  }

  /**
   * Whether a pinned bundle may still be served (§8.1, 00 §3.7a).
   *
   * <p>Three conditions, and the third is the one with teeth: the hash must be in {@code servable},
   * pinning must be allowed for that entry, and if the bundle has stopped being champion then
   * {@code receivedAt − championUntil} must be inside the grandfather window.
   *
   * <p><b>The comparison uses the request's {@code receivedAt}, never the caller's {@code
   * asOf}.</b> {@code asOf} is a client-supplied field, so a caller who could pin against it would
   * simply back-date a request to keep using a policy version the lender has retired — which is
   * exactly the kind of quiet drift a grandfather window exists to bound.
   *
   * @param entry the entry
   * @param pinnedHash the bundle the caller asked for
   * @param receivedAt when the request arrived, by the server's clock
   * @param grandfatherWindowDays the tenant's window
   * @return true when the pin may be honoured
   */
  public static boolean mayServePinned(
      Entry entry, String pinnedHash, Instant receivedAt, int grandfatherWindowDays) {
    if (!entry.pinningAllowed()) {
      return false;
    }
    return entry.servable().stream()
        .filter(s -> s.bundleHash().equals(pinnedHash))
        .findFirst()
        .map(
            s ->
                s.championUntil()
                    .map(
                        until ->
                            !receivedAt.isAfter(
                                until.plus(java.time.Duration.ofDays(grandfatherWindowDays))))
                    .orElse(true))
        .orElse(false);
  }

  /**
   * The exact bytes a manifest signature is made over (§8.1).
   *
   * @param withoutSignature the manifest document with no {@code signature} member
   * @return {@code UTF-8("bre-manifest/1:") || SHA-256(BRE-JCS(…))}
   */
  public static byte[] signingInput(JsonNode withoutSignature) {
    String digest = BreJcs.sha256(withoutSignature);
    return (SIGNING_PREFIX + digest).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Signs a manifest document.
   *
   * @param document the manifest with no {@code signature} member
   * @param kid the cell's manifest key identifier
   * @param privateKey the signing key
   * @return the document with its signature added
   */
  public static ObjectNode sign(ObjectNode document, String kid, PrivateKey privateKey) {
    ObjectNode unsigned = document.deepCopy();
    unsigned.remove("signature");
    try {
      java.security.Signature signer = java.security.Signature.getInstance(BundleSigner.ALGORITHM);
      signer.initSign(privateKey);
      signer.update(signingInput(unsigned));
      ObjectNode signed = unsigned.deepCopy();
      ObjectNode signature = signed.putObject("signature");
      signature.put("kid", kid);
      signature.put("alg", BundleSigner.ALGORITHM);
      signature.put("sig", Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
      return signed;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("the manifest could not be signed", e);
    }
  }

  /**
   * Verifies a manifest document's signature.
   *
   * <p>Recomputes the digest from the document with the signature member removed — the same
   * reasoning as a bundle: a verifier that trusts a digest the document supplies is verifying a
   * claim rather than the content.
   *
   * @param document the signed manifest
   * @param key the cell's manifest public key
   * @return true when the signature verifies
   */
  public static boolean verify(JsonNode document, PublicKey key) {
    JsonNode signature = document.get("signature");
    if (signature == null || !signature.isObject()) {
      return false;
    }
    if (!BundleSigner.ALGORITHM.equals(signature.path("alg").asText(""))) {
      return false;
    }
    ObjectNode unsigned = ((ObjectNode) document).deepCopy();
    unsigned.remove("signature");
    try {
      java.security.Signature verifier =
          java.security.Signature.getInstance(BundleSigner.ALGORITHM);
      verifier.initVerify(key);
      verifier.update(signingInput(unsigned));
      return verifier.verify(Base64.getUrlDecoder().decode(signature.path("sig").asText("")));
    } catch (GeneralSecurityException | IllegalArgumentException malformed) {
      return false;
    }
  }

  /**
   * Reads a manifest from a verified document.
   *
   * @param document the manifest
   * @return the manifest
   * @throws BundleException if it is not a well-formed {@code bre-manifest/1}
   */
  public static PointerManifest of(JsonNode document) {
    if (!FORMAT.equals(document.path("format").asText(""))) {
      throw new BundleException(
          BundleException.Reason.MALFORMED,
          "format is '" + document.path("format").asText("") + "', expected " + FORMAT);
    }
    List<Entry> entries = new ArrayList<>();
    for (JsonNode e : document.path("entries")) {
      List<Challenger> challengers = new ArrayList<>();
      for (JsonNode c : e.path("challengers")) {
        challengers.add(
            new Challenger(
                c.path("deploymentId").asText(),
                c.path("bundleHash").asText(),
                c.path("experimentId").asText(),
                c.path("bucketFrom").asInt(),
                c.path("bucketTo").asInt()));
      }
      List<Servable> servable = new ArrayList<>();
      for (JsonNode s : e.path("servable")) {
        JsonNode until = s.get("championUntil");
        servable.add(
            new Servable(
                s.path("bundleHash").asText(),
                s.path("deploymentId").asText(),
                until == null || until.isNull()
                    ? Optional.empty()
                    : Optional.of(Instant.parse(until.asText()))));
      }
      entries.add(
          new Entry(
              e.path("tenantId").asText(),
              e.path("environment").asText(),
              e.path("policyCode").asText(),
              e.path("timeZone").asText("Asia/Kolkata"),
              e.path("pinningAllowed").asBoolean(false),
              new Target(
                  e.path("champion").path("deploymentId").asText(),
                  e.path("champion").path("bundleHash").asText()),
              challengers,
              servable));
    }

    JsonNode signature = document.get("signature");
    Optional<Bundle.Signature> parsed =
        signature == null || !signature.isObject()
            ? Optional.empty()
            : Optional.of(
                new Bundle.Signature(
                    signature.path("kid").asText(),
                    signature.path("alg").asText(),
                    Bundle.Role.ORIGIN,
                    signature.path("sig").asText()));

    return new PointerManifest(
        document.path("cellId").asInt(),
        document.path("manifestVersion").asLong(),
        Instant.parse(document.path("issuedAt").asText()),
        entries,
        parsed);
  }

  /**
   * Reads a manifest from JSON text.
   *
   * @param json the document
   * @return the manifest
   */
  public static PointerManifest parse(String json) {
    try {
      return of(MAPPER.readTree(json));
    } catch (java.io.IOException e) {
      throw new BundleException(BundleException.Reason.MALFORMED, "not valid JSON");
    }
  }
}
