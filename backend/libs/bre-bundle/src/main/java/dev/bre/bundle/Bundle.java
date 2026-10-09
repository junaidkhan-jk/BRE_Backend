package dev.bre.bundle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.bre.irjson.BreJcs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@code bre-bundle/1} document: a signed, content-addressed policy version (LLD §2.6).
 *
 * <p>The bundle is what makes a decision reproducible years later. It carries the IR, every asset
 * the policy pins — parameter values with their effective windows, reference lists, the reason
 * catalogue, the authority matrix — and a manifest recording who approved it and what the static
 * checks and tests said. A decision records its {@code bundleHash}, so replaying it means fetching
 * exactly these bytes, not "version 3 of that policy as it stands today".
 *
 * <p><b>Content-addressed, and the address is computed rather than believed.</b> {@code bundleHash
 * = "sha256:" + hex(SHA-256(BRE-JCS({manifest, ir, assets})))}. The {@code bundleHash} and {@code
 * signatures} fields are deliberately outside that object: a hash cannot cover itself, and a
 * signature is made over the hash.
 */
public record Bundle(
    String format,
    String bundleHash,
    JsonNode manifest,
    JsonNode ir,
    JsonNode assets,
    List<Signature> signatures) {

  /** The only format version this build understands. */
  public static final String FORMAT = "bre-bundle/1";

  /** The media type a bundle is served as (§2.6). */
  public static final String MEDIA_TYPE = "application/vnd.bre.bundle+json";

  /**
   * The domain-separation prefix for a bundle signature (§2.6).
   *
   * <p>Signed message: {@code UTF-8("bre-bundle/1:") || UTF-8(bundleHash)}. The prefix is what
   * stops a signature being lifted from one artifact type and replayed on another — the Active
   * Pointer Manifest uses {@code "bre-manifest/1:"} over a different digest, and without distinct
   * prefixes a manifest signature and a bundle signature would be interchangeable to a verifier.
   */
  public static final String SIGNING_PREFIX = "bre-bundle/1:";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Who a signature speaks for (§2.6). */
  public enum Role {
    /** Added by the registry that approved the PolicyVersion. */
    ORIGIN,
    /** A countersignature added by a registry that imported and verified an ORIGIN signature. */
    IMPORT
  }

  /**
   * One signature over the bundle hash.
   *
   * @param kid the key identifier
   * @param alg the algorithm; only {@code Ed25519} is accepted
   * @param role what the signature speaks for
   * @param sig the signature bytes, base64url without padding
   */
  public record Signature(String kid, String alg, Role role, String sig) {}

  /** Creates a bundle, copying the signature list. */
  public Bundle {
    signatures = List.copyOf(signatures);
  }

  /**
   * Computes the content address of a manifest, IR and asset set (§2.5).
   *
   * <p>Over BRE-JCS, so the hash is a property of the <em>content</em> and not of how the producer
   * happened to order its keys or space its JSON. Two registries building the same policy produce
   * the same address.
   *
   * @param manifest the manifest
   * @param ir the policy IR
   * @param assets the resolved assets
   * @return {@code "sha256:"} followed by lowercase hex
   */
  public static String computeHash(JsonNode manifest, JsonNode ir, JsonNode assets) {
    ObjectNode content = MAPPER.createObjectNode();
    content.set("manifest", manifest);
    content.set("ir", ir);
    content.set("assets", assets);
    return BreJcs.sha256(content);
  }

  /**
   * The exact bytes a signature is made over (§2.6).
   *
   * @param bundleHash the content address
   * @return {@code UTF-8("bre-bundle/1:") || UTF-8(bundleHash)}
   */
  public static byte[] signingInput(String bundleHash) {
    return (SIGNING_PREFIX + bundleHash).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Recomputes this bundle's content address from its own content.
   *
   * <p>Never read {@link #bundleHash()} to decide what to verify. The stored value is a claim made
   * by whoever produced the document; this is the fact.
   *
   * @return the recomputed address
   */
  public String recomputeHash() {
    return computeHash(manifest, ir, assets);
  }

  /**
   * Reads a bundle from JSON.
   *
   * @param json the document
   * @return the bundle
   * @throws BundleException if it is not a well-formed {@code bre-bundle/1}
   */
  public static Bundle parse(String json) {
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (IOException e) {
      throw new BundleException(BundleException.Reason.MALFORMED, "not valid JSON");
    }
    return of(root);
  }

  /**
   * Reads a bundle from a parsed document.
   *
   * @param root the document
   * @return the bundle
   * @throws BundleException if it is not a well-formed {@code bre-bundle/1}
   */
  public static Bundle of(JsonNode root) {
    if (!root.isObject()) {
      throw new BundleException(BundleException.Reason.MALFORMED, "not a JSON object");
    }
    String format = root.path("format").asText("");
    if (!FORMAT.equals(format)) {
      // The format string is producer-controlled but is not content: naming it in the error is
      // what tells an operator they are pointing at the wrong kind of artifact entirely.
      throw new BundleException(
          BundleException.Reason.MALFORMED, "format is '" + format + "', expected " + FORMAT);
    }
    for (String required : new String[] {"bundleHash", "manifest", "ir", "assets"}) {
      if (!root.hasNonNull(required)) {
        throw new BundleException(BundleException.Reason.MALFORMED, "missing '" + required + "'");
      }
    }

    List<Signature> signatures = new ArrayList<>();
    JsonNode node = root.get("signatures");
    if (node != null && node.isArray()) {
      for (JsonNode signature : node) {
        Role role;
        try {
          role = Role.valueOf(signature.path("role").asText(""));
        } catch (IllegalArgumentException unknownRole) {
          throw new BundleException(
              BundleException.Reason.MALFORMED,
              "signature role '" + signature.path("role").asText("") + "' is not ORIGIN or IMPORT");
        }
        signatures.add(
            new Signature(
                signature.path("kid").asText(""),
                signature.path("alg").asText(""),
                role,
                signature.path("sig").asText("")));
      }
    }

    return new Bundle(
        format,
        root.get("bundleHash").asText(),
        root.get("manifest"),
        root.get("ir"),
        root.get("assets"),
        signatures);
  }

  /**
   * Serialises this bundle to JSON.
   *
   * @return the document
   */
  public String toJson() {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("format", format);
    root.put("bundleHash", bundleHash);
    root.set("manifest", manifest);
    root.set("ir", ir);
    root.set("assets", assets);
    ArrayNode signatureArray = root.putArray("signatures");
    for (Signature signature : signatures) {
      ObjectNode node = signatureArray.addObject();
      node.put("kid", signature.kid());
      node.put("alg", signature.alg());
      node.put("role", signature.role().name());
      node.put("sig", signature.sig());
    }
    try {
      return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    } catch (IOException e) {
      throw new UncheckedIOException("the bundle could not be serialised", e);
    }
  }

  /**
   * Returns a copy carrying one more signature.
   *
   * @param signature the signature to add
   * @return the countersigned bundle
   */
  public Bundle withSignature(Signature signature) {
    List<Signature> all = new ArrayList<>(signatures);
    all.add(signature);
    return new Bundle(format, bundleHash, manifest, ir, assets, all);
  }
}
