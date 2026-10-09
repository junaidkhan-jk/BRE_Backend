package dev.bre.registry.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.bre.bundle.PointerManifest;
import dev.bre.irjson.BreJcs;
import dev.bre.registry.sign.SigningKeySource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds, signs and publishes the Active Pointer Manifest (§8.1, §8.2).
 *
 * <p><b>The manifest is the only thing a decision pod reads.</b> A deployment row that is ACTIVE
 * and not in a published manifest is a statement of intent nobody can act on — so publication
 * happens in the activating transaction, and a manifest that cannot be signed rolls the activation
 * back rather than leaving the two out of step.
 *
 * <p><b>It is rebuilt whole, every time, from the current ACTIVE deployments.</b> Not patched. A
 * patch would have to be applied in the same order everywhere and would diverge the first time one
 * was missed; a full rebuild makes the manifest a pure function of the deployment table, so the
 * worst a bug can do is publish a correct manifest twice.
 *
 * <p><b>Versions are monotonic per cell</b> (§8.1), and a pod refuses a manifest whose version is
 * not greater than the one it holds (§8.5) — so an older document cannot replay traffic onto a
 * retired policy. That is why the version comes from {@code max(manifest_version) + 1} inside the
 * transaction rather than from a timestamp or a counter in this process: two registry instances
 * must not both mint version 7.
 *
 * <p><b>Signed with the cell's manifest key, which is not the bundle key</b> (§8.6). Whoever holds
 * this key can point traffic at any bundle that already exists; whoever holds the bundle key can
 * mint a policy but cannot make a cell serve it.
 */
@Component
public class ManifestPublisher {

  private static final Logger log = LoggerFactory.getLogger(ManifestPublisher.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final DeploymentRepository repository;
  private final SigningKeySource keys;
  private final int cellId;
  private final Path manifestDir;

  /**
   * Creates the publisher.
   *
   * @param repository the deployment tables
   * @param keys the manifest signing key
   * @param cellId the cell this registry publishes for
   * @param manifestDir where the signed manifest is written for pods to read
   */
  public ManifestPublisher(
      DeploymentRepository repository,
      SigningKeySource keys,
      @Value("${bre.registry.cell-id:1}") int cellId,
      @Value("${bre.registry.manifest-dir:./var/manifests}") Path manifestDir) {
    this.repository = repository;
    this.keys = keys;
    this.cellId = cellId;
    this.manifestDir = manifestDir;
  }

  /**
   * Rebuilds, signs and publishes the manifest for this cell.
   *
   * @param tenantId the tenant whose change triggered it, for the log line only — the manifest
   *     covers the whole cell
   * @return the published manifest version
   */
  public long publish(String tenantId) {
    long version = repository.nextManifestVersion(cellId);

    ObjectNode manifest = JSON.createObjectNode();
    manifest.put("format", PointerManifest.FORMAT);
    manifest.put("cellId", cellId);
    manifest.put("manifestVersion", version);
    // Second precision and a trailing Z, because the manifest is canonicalised and hashed: a
    // timestamp whose format varied would change the hash without changing the content.
    manifest.put(
        "issuedAt", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());

    ArrayNode entries = manifest.putArray("entries");
    Map<String, ObjectNode> byKey = new LinkedHashMap<>();

    for (DeploymentRepository.ManifestEntry active : repository.activeForCell(cellId)) {
      String key = active.tenantId() + "" + active.envCode() + "" + active.policyCode();
      ObjectNode entry =
          byKey.computeIfAbsent(
              key,
              k -> {
                ObjectNode created = entries.addObject();
                created.put("tenantId", active.tenantId());
                created.put("environment", active.envCode());
                created.put("policyCode", active.policyCode());
                created.put("timeZone", active.timeZone());
                created.put("pinningAllowed", active.pinningAllowed());
                created.putArray("challengers");
                created.putArray("servable");
                return created;
              });

      switch (active.mode()) {
        case "CHAMPION" -> {
          ObjectNode champion = entry.putObject("champion");
          champion.put("deploymentId", active.deploymentId());
          champion.put("bundleHash", active.bundleHash());
        }
        case "CHALLENGER" -> {
          ObjectNode challenger = ((ArrayNode) entry.get("challengers")).addObject();
          challenger.put("deploymentId", active.deploymentId());
          challenger.put("bundleHash", active.bundleHash());
          challenger.put("experimentId", active.experimentId());
          challenger.put("bucketFrom", active.bucketFrom() == null ? 0 : active.bucketFrom());
          challenger.put("bucketTo", active.bucketTo() == null ? 0 : active.bucketTo());
        }
        default -> {
          // SHADOW. Not in the manifest's served set: a shadow deployment is evaluated for
          // comparison and its answer is never returned (§4.11), which is the data plane's job
          // and arrives with M4. Skipped rather than silently included as a challenger, which
          // would put real traffic on a policy nobody approved for traffic.
          log.debug(
              "shadow deployment {} is not published to the manifest yet (M4)",
              active.deploymentId());
        }
      }
    }

    // Servable: every bundle a pinned request may still ask for. The pod applies the grandfather
    // window against the request's arrival time, so no "now" is baked into the document.
    for (DeploymentRepository.ServableEntry servable : repository.servableForCell(cellId)) {
      String key = servable.tenantId() + "" + servable.envCode() + "" + servable.policyCode();
      ObjectNode entry = byKey.get(key);
      if (entry == null) {
        // The policy has no ACTIVE deployment in this environment any more, so there is nothing
        // for a pin to be grandfathered against: a caller pinning it would be asking for a policy
        // this cell no longer serves at all.
        continue;
      }
      ObjectNode row = ((ArrayNode) entry.get("servable")).addObject();
      row.put("bundleHash", servable.bundleHash());
      row.put("deploymentId", servable.deploymentId());
      if (servable.championUntil() == null) {
        row.putNull("championUntil");
      } else {
        row.put("championUntil", servable.championUntil());
      }
    }

    // Entries with no champion are dropped. An entry with challengers and no champion would make
    // a pod serve a challenger to its bucket and have nothing for everybody else, which fails
    // closed but reports NOT_SERVED for traffic that has a perfectly good policy elsewhere.
    List<Integer> withoutChampion = new ArrayList<>();
    for (int i = 0; i < entries.size(); i++) {
      if (!entries.get(i).has("champion")) {
        withoutChampion.add(i);
      }
    }
    for (int i = withoutChampion.size() - 1; i >= 0; i--) {
      ObjectNode dropped = (ObjectNode) entries.get(withoutChampion.get(i));
      log.warn(
          "not publishing {}/{}/{}: it has no ACTIVE champion, only challengers or shadows",
          dropped.path("tenantId").asText(),
          dropped.path("environment").asText(),
          dropped.path("policyCode").asText());
      entries.remove(withoutChampion.get(i));
    }

    SigningKeySource.Key key = keys.manifestKey();
    ObjectNode signed = PointerManifest.sign(manifest, key.kid(), key.privateKey());

    String body = signed.toString();
    String bodyHash = BreJcs.sha256(manifest);
    repository.insertManifest(cellId, version, body, bodyHash, signed.get("signature").toString());
    writeForPods(body);

    log.info(
        "published Active Pointer Manifest cell={} version={} entries={} (triggered by tenant {})",
        cellId,
        version,
        entries.size(),
        tenantId);
    return version;
  }

  private void writeForPods(String body) {
    try {
      Files.createDirectories(manifestDir);
      Path target = manifestDir.resolve("manifest.json");
      // Written to a temporary file and moved, because a pod may be reading it. A partial manifest
      // fails its signature check and is correctly ignored (§8.5), but a pod that reads a
      // half-written file every time one is published is a pod that looks intermittently broken.
      Path temporary = manifestDir.resolve("manifest.json.tmp");
      Files.writeString(temporary, body);
      Files.move(
          temporary,
          target,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new ManifestException(
          "the manifest was signed and recorded but could not be written to "
              + manifestDir
              + ", so no pod can read it. The activation is rolled back.",
          e);
    }
  }

  /** The manifest could not be published. Mapped to 409 — the activation did not happen. */
  public static final class ManifestException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ManifestException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
