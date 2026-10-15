package dev.bre.registry.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.bre.irjson.BreJcs;
import dev.bre.registry.audit.AuditLog;
import dev.bre.registry.auth.Principal;
import dev.bre.registry.auth.Role;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The shared-asset lifecycle, under maker-checker (M3-03, FR-MODEL-007, FR-DEV-002).
 *
 * <p><b>Why a shared asset needs the same ceremony as a policy.</b> A parameter set holds the
 * indebtedness cap. Changing one number in it changes what every policy that pins it will decide —
 * without any policy being edited, reviewed or approved. So an asset gets the same lifecycle as a
 * policy version and the same segregation of duties: {@code DRAFT → IN_REVIEW → APPROVED}, the
 * reviewer cannot be the author, and nothing can be pinned until it is APPROVED. AC-MODEL-007 says
 * exactly this: a parameter change requires a second user's approval before any PolicyVersion can
 * pin it.
 *
 * <p><b>An APPROVED asset is immutable, and a change is a new version.</b> That is what makes
 * FR-MODEL-003 true — a later change to a shared asset never changes an approved policy version,
 * because the policy pinned {@code MFI_PARAMS@7} and the change produced {@code MFI_PARAMS@8}. The
 * signed bundle carries the resolved content of @7 and keeps deciding with it.
 *
 * <p><b>Effective dating is not a substitute for versioning, and neither replaces the other.</b>
 * Versioning answers "which set of parameters did this policy agree to"; effective dating answers
 * "which value applied on the date this application was decided". A regulator's new cap arriving in
 * April is a new effective window inside a new asset version: the window keeps March's decisions
 * correct, and the version keeps March's approvals honest.
 *
 * <p>The administrator override behaves exactly as it does for policies — permitted only when the
 * tenant allows it, requiring the capability, and audited on every use.
 */
@Service
public class AssetService {

  private static final Logger log = LoggerFactory.getLogger(AssetService.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final AssetRepository repository;
  private final AuditLog audit;

  /**
   * Creates the service.
   *
   * @param repository the asset tables
   * @param audit the hash-chained audit log
   */
  public AssetService(AssetRepository repository, AuditLog audit) {
    this.repository = repository;
    this.audit = audit;
  }

  /** A lifecycle or content rule was broken. Mapped to 409, never 500. */
  public static final class AssetException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what rule was broken, and why it exists
     */
    public AssetException(String message) {
      super(message);
    }
  }

  /**
   * An asset version as the API returns it.
   *
   * @param kind the kind
   * @param code the asset code
   * @param version the version number
   * @param status its lifecycle state
   * @param contentHash the canonical hash, once approved
   * @param author who created the draft
   * @param submissionRound how many times it has been submitted
   * @param approvedAt when it was approved
   * @param content the asset's content, in the shape a bundle carries. A {@code Map}, not a Jackson
   *     2 {@code ObjectNode}: Spring Boot 4 serialises HTTP with Jackson 3, to which a Jackson 2
   *     node is an unrecognised POJO that comes out as every bean accessor and none of the data.
   * @param rowVersion for optimistic locking
   */
  public record AssetVersion(
      AssetRepository.Kind kind,
      String code,
      int version,
      String status,
      Optional<String> contentHash,
      String author,
      int submissionRound,
      Optional<String> approvedAt,
      java.util.Map<String, Object> content,
      long rowVersion) {}

  /**
   * Opens a DRAFT of an asset, optionally cloning the last approved version into it.
   *
   * <p>Cloning is the default for a change: a parameter set has nineteen parameters and a change
   * usually touches one, so starting empty would make the author retype the other eighteen — and a
   * retyped parameter set is where a wrong threshold comes from.
   *
   * @param tenantId the tenant
   * @param kind the kind
   * @param code the asset code
   * @param actor who is creating it
   * @param cloneLatest true to copy the latest APPROVED version's content into the draft
   * @return the draft
   */
  @Transactional
  public AssetVersion createDraft(
      String tenantId, AssetRepository.Kind kind, String code, String actor, boolean cloneLatest) {
    Optional<Integer> open = repository.openVersion(tenantId, kind, code);
    if (open.isPresent()) {
      throw new AssetException(
          kind
              + " "
              + code
              + " already has version "
              + open.get()
              + " open for editing or review. Two open versions of one asset would let two people"
              + " change the same threshold in different directions and both be approved.");
    }

    int next = repository.nextVersion(tenantId, kind, code);
    repository.insertDraft(tenantId, kind, code, next, actor);

    Optional<Integer> source =
        cloneLatest ? repository.latestApproved(tenantId, kind, code) : Optional.empty();
    source.ifPresent(from -> copyContent(tenantId, kind, code, from, next));

    audit.record(
        tenantId,
        actor,
        "ASSET_VERSION.CREATED",
        "asset_version",
        key(kind, code, next),
        source.map(from -> "{\"clonedFrom\":" + from + "}").orElse("{}"));
    return read(tenantId, kind, code, next);
  }

  /**
   * Replaces a DRAFT's content.
   *
   * @param tenantId the tenant
   * @param kind the kind
   * @param code the asset code
   * @param version the version
   * @param content the whole content, in the shape {@link #read} returns
   * @param actor who is saving
   * @return the saved version
   */
  @Transactional
  public AssetVersion save(
      String tenantId,
      AssetRepository.Kind kind,
      String code,
      int version,
      JsonNode content,
      String actor) {
    AssetRepository.Header header = require(tenantId, kind, code, version);
    if (!"DRAFT".equals(header.status())) {
      throw new AssetException(
          kind
              + " "
              + code
              + "@"
              + version
              + " is "
              + header.status()
              + ", not DRAFT, so it cannot be edited. An approved asset is what some policy was"
              + " signed against; changing it in place would change decisions nobody reviewed."
              + " Open a new version instead.");
    }

    switch (kind) {
      case PARAMETER_SET -> {
        JsonNode parameters = content.path("parameters");
        if (!parameters.isArray() || parameters.isEmpty()) {
          throw new AssetException(
              "a parameter set must declare at least one parameter, as"
                  + " {name, type, regulatory, values:[{effectiveFrom, effectiveTo, value}]}.");
        }
        repository.replaceParameters(tenantId, code, version, parameters);
      }
      case REFERENCE_LIST -> {
        List<String> items = new java.util.ArrayList<>();
        content.path("items").forEach(item -> items.add(item.asText()));
        repository.replaceList(
            tenantId,
            code,
            version,
            content.path("itemType").asText("Text"),
            content.path("description").asText(null),
            items);
      }
      case REASON_CODE_CATALOGUE ->
          repository.replaceReasonCodes(tenantId, code, version, content.path("codes"));
      case AUTHORITY_MATRIX ->
          repository.replaceAuthorityMatrix(
              tenantId,
              code,
              version,
              content.path("routingMode").asText("HIGHEST_ONLY"),
              content.path("levels"),
              content.path("entries"));
    }

    audit.record(
        tenantId, actor, "ASSET_VERSION.SAVED", "asset_version", key(kind, code, version), "{}");
    return read(tenantId, kind, code, version);
  }

  /**
   * Submits a DRAFT for review.
   *
   * @param tenantId the tenant
   * @param kind the kind
   * @param code the asset code
   * @param version the version
   * @param actor who is submitting
   * @return the submitted version
   */
  @Transactional
  public AssetVersion submit(
      String tenantId, AssetRepository.Kind kind, String code, int version, String actor) {
    AssetRepository.Header header = require(tenantId, kind, code, version);
    if (!"DRAFT".equals(header.status())) {
      throw new AssetException(
          "only a DRAFT can be submitted; " + key(kind, code, version) + " is " + header.status());
    }
    ObjectNode content = contentOf(tenantId, kind, code, version);
    if (isEmptyContent(kind, content)) {
      throw new AssetException(
          key(kind, code, version)
              + " has no content, so there is nothing to review. An empty asset that policies pin"
              + " resolves every reference to Missing, and every policy using it refers.");
    }

    repository.submit(tenantId, kind, code, version);
    audit.record(
        tenantId,
        actor,
        "ASSET_VERSION.SUBMITTED",
        "asset_version",
        key(kind, code, version),
        "{\"submissionRound\":" + (header.submissionRound() + 1) + "}");
    return read(tenantId, kind, code, version);
  }

  /**
   * Records a review decision, approving the asset when it passes.
   *
   * <p><b>The reviewer cannot be the author</b> (00 §3.3, AC-MODEL-007). One approval is enough for
   * an asset — unlike a PROD deployment's two — because an asset change is always reviewed again
   * downstream: no policy can pin it without a policy version of its own going through review.
   *
   * @param tenantId the tenant
   * @param kind the kind
   * @param code the asset code
   * @param version the version
   * @param principal who is reviewing
   * @param approve true to approve, false to request changes
   * @param comment their comment
   * @return the version, APPROVED when it passed
   */
  @Transactional
  public AssetVersion review(
      String tenantId,
      AssetRepository.Kind kind,
      String code,
      int version,
      Principal principal,
      boolean approve,
      String comment) {
    AssetRepository.Header header = require(tenantId, kind, code, version);
    if (!"IN_REVIEW".equals(header.status())) {
      throw new AssetException(
          key(kind, code, version) + " is " + header.status() + ", so there is nothing to review.");
    }

    String reviewer = principal.subject();
    if (reviewer.equals(header.author())) {
      if (!principal.mayOverrideSegregationOfDuties()) {
        throw new AssetException(
            "the reviewer cannot be the author of this asset version (00 §3.3). "
                + reviewer
                + " created it, so somebody else has to approve it. A parameter set holds the"
                + " thresholds every policy pinning it will decide by, so one person changing and"
                + " approving a figure is the control this exists to prevent."
                + (principal.can(Role.Capability.SOD_OVERRIDE)
                    ? " This tenant has allow_admin_sod_override switched off, so an administrator"
                        + " cannot bypass it either."
                    : ""));
      }
      audit.record(
          tenantId,
          reviewer,
          "ASSET_VERSION.SOD_OVERRIDDEN",
          "asset_version",
          key(kind, code, version),
          "{\"control\":\"REVIEWER_IS_AUTHOR\",\"authority\":\"TENANT_ADMIN\"}");
      log.warn(
          "SEGREGATION OF DUTIES OVERRIDDEN: {} is approving the asset version they authored."
              + " tenant={} asset={}. Permitted by allow_admin_sod_override and recorded in the"
              + " audit log.",
          reviewer,
          tenantId,
          key(kind, code, version));
    }

    int round = header.submissionRound();
    repository.recordReview(tenantId, kind, code, version, round, reviewer, approve, comment);
    audit.record(
        tenantId,
        reviewer,
        approve ? "ASSET_VERSION.REVIEW_APPROVED" : "ASSET_VERSION.CHANGES_REQUESTED",
        "asset_version",
        key(kind, code, version),
        "{\"round\":" + round + "}");

    if (!approve) {
      repository.returnToDraft(tenantId, kind, code, version);
      return read(tenantId, kind, code, version);
    }

    // The content hash is over the canonical content, using the same BRE-JCS canonicalisation a
    // bundle hash uses — so two asset versions with the same content have the same hash whatever
    // order the rows were inserted in, and "did this change" is a string comparison.
    ObjectNode content = contentOf(tenantId, kind, code, version);
    String contentHash = BreJcs.sha256(content);
    repository.approve(tenantId, kind, code, version, contentHash);
    audit.record(
        tenantId,
        reviewer,
        "ASSET_VERSION.APPROVED",
        "asset_version",
        key(kind, code, version),
        "{\"contentHash\":\"" + contentHash + "\"}");
    log.info(
        "asset approved tenant={} asset={} contentHash={}",
        tenantId,
        key(kind, code, version),
        contentHash);
    return read(tenantId, kind, code, version);
  }

  /**
   * Discards a DRAFT.
   *
   * @param tenantId the tenant
   * @param kind the kind
   * @param code the asset code
   * @param version the version
   * @param actor who is discarding it
   * @return the discarded version
   */
  @Transactional
  public AssetVersion discard(
      String tenantId, AssetRepository.Kind kind, String code, int version, String actor) {
    AssetRepository.Header header = require(tenantId, kind, code, version);
    if (!"DRAFT".equals(header.status())) {
      throw new AssetException(
          "only a DRAFT can be discarded; " + key(kind, code, version) + " is " + header.status());
    }
    repository.discard(tenantId, kind, code, version);
    audit.record(
        tenantId,
        actor,
        "ASSET_VERSION.DISCARDED",
        "asset_version",
        key(kind, code, version),
        "{}");
    return read(tenantId, kind, code, version);
  }

  /**
   * Reads one version with its content.
   *
   * @param tenantId the tenant
   * @param kind the kind
   * @param code the asset code
   * @param version the version
   * @return the version
   */
  public AssetVersion read(String tenantId, AssetRepository.Kind kind, String code, int version) {
    AssetRepository.Header header = require(tenantId, kind, code, version);
    return new AssetVersion(
        header.kind(),
        header.code(),
        header.version(),
        header.status(),
        header.contentHash(),
        header.author(),
        header.submissionRound(),
        header.approvedAt(),
        JSON.convertValue(
            contentOf(tenantId, kind, code, version),
            new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}),
        header.rowVersion());
  }

  /**
   * Every asset version of a kind.
   *
   * @param tenantId the tenant
   * @param kind the kind, or null for all
   * @return the headers, newest version first
   */
  public List<AssetRepository.Header> list(String tenantId, AssetRepository.Kind kind) {
    return repository.list(tenantId, kind);
  }

  /** The content of one version, in the shape a bundle carries. */
  ObjectNode contentOf(String tenantId, AssetRepository.Kind kind, String code, int version) {
    return switch (kind) {
      case PARAMETER_SET -> repository.parameterSet(tenantId, code, version);
      case REFERENCE_LIST -> repository.referenceList(tenantId, code, version);
      case REASON_CODE_CATALOGUE -> repository.reasonCodes(tenantId, code, version);
      case AUTHORITY_MATRIX -> repository.authorityMatrix(tenantId, code, version);
    };
  }

  private static boolean isEmptyContent(AssetRepository.Kind kind, ObjectNode content) {
    return switch (kind) {
      case PARAMETER_SET -> content.path("parameters").isEmpty();
      case REFERENCE_LIST -> content.path("items").isEmpty();
      case REASON_CODE_CATALOGUE -> content.path("codes").isEmpty();
      case AUTHORITY_MATRIX ->
          content.path("levels").isEmpty() || content.path("entries").isEmpty();
    };
  }

  private void copyContent(
      String tenantId, AssetRepository.Kind kind, String code, int from, int to) {
    ObjectNode content = contentOf(tenantId, kind, code, from);
    switch (kind) {
      case PARAMETER_SET ->
          repository.replaceParameters(tenantId, code, to, content.path("parameters"));
      case REFERENCE_LIST -> {
        List<String> items = new java.util.ArrayList<>();
        content.path("items").forEach(item -> items.add(item.asText()));
        repository.replaceList(
            tenantId,
            code,
            to,
            content.path("itemType").asText("Text"),
            content.path("description").asText(null),
            items);
      }
      case REASON_CODE_CATALOGUE ->
          repository.replaceReasonCodes(tenantId, code, to, content.path("codes"));
      case AUTHORITY_MATRIX ->
          repository.replaceAuthorityMatrix(
              tenantId,
              code,
              to,
              content.path("routingMode").asText("HIGHEST_ONLY"),
              content.path("levels"),
              content.path("entries"));
    }
  }

  private AssetRepository.Header require(
      String tenantId, AssetRepository.Kind kind, String code, int version) {
    return repository
        .find(tenantId, kind, code, version)
        .orElseThrow(
            () -> new AssetException("no " + key(kind, code, version) + " for this tenant."));
  }

  private static String key(AssetRepository.Kind kind, String code, int version) {
    return kind + " " + code + "@" + version;
  }

  /** Exposes the mapper so a caller can build content without its own instance. */
  public static ObjectMapper json() {
    return JSON;
  }
}
