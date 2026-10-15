package dev.bre.registry.api;

import dev.bre.registry.asset.AssetRepository;
import dev.bre.registry.asset.AssetService;
import dev.bre.registry.auth.Principal;
import dev.bre.registry.auth.Role;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shared assets: parameter sets, reference lists, reason codes and authority matrices (M3-03).
 *
 * <p><b>The same lifecycle verbs as a policy version</b>, deliberately. A reviewer who has learnt
 * that approving a policy means {@code POST …/reviews} should not have to learn a second shape to
 * approve the thresholds that policy decides by.
 *
 * <p><b>Content crosses this boundary as a {@code Map}, never as a Jackson 2 {@code JsonNode}.</b>
 * Spring Boot 4 reads and writes HTTP with Jackson 3 ({@code tools.jackson}) while this build
 * parses PSF and IR with Jackson 2 — so a {@code JsonNode} parameter fails with "Cannot construct
 * instance of JsonNode (no Creators)" and a {@code JsonNode} return value comes out as {@code
 * {"array":false,"object":true,…}}: every bean accessor and none of the data. A {@code Map} is
 * native to both. The audit API hit the same wall from the other direction.
 *
 * <p><b>POLICY_EDIT and POLICY_REVIEW, not new capabilities.</b> 00 §3.12's POLICY_AUTHOR is
 * defined as "edit DRAFT versions <em>and assets</em>" — the role already covers this, and
 * inventing {@code ASSET_EDIT} would mean every existing author silently losing the ability the
 * specification says they have.
 */
@RestController
@RequestMapping("/v1/assets")
public class AssetController {

  private final AssetService assets;

  /**
   * Creates the controller.
   *
   * @param assets the asset lifecycle
   */
  public AssetController(AssetService assets) {
    this.assets = assets;
  }

  /** A review decision. */
  public record ReviewRequest(Boolean approve, String comment) {}

  /**
   * Lists asset versions.
   *
   * @param request the request
   * @param kind a kind to filter on, or absent for all
   * @return the headers, newest version first
   */
  @GetMapping
  public List<AssetRepository.Header> list(
      HttpServletRequest request, @RequestParam(required = false) AssetRepository.Kind kind) {
    Principal caller = Authorisation.require(request, Role.Capability.POLICY_READ);
    return assets.list(caller.tenantId(), kind);
  }

  /**
   * Reads one version with its content.
   *
   * @param request the request
   * @param kind the kind
   * @param code the asset code
   * @param version the version
   * @return the version
   */
  @GetMapping("/{kind}/{code}/versions/{version}")
  public AssetService.AssetVersion read(
      HttpServletRequest request,
      @PathVariable AssetRepository.Kind kind,
      @PathVariable String code,
      @PathVariable int version) {
    Principal caller = Authorisation.require(request, Role.Capability.POLICY_READ);
    return assets.read(caller.tenantId(), kind, code, version);
  }

  /**
   * Which policy versions pin this asset, and which of them are live.
   *
   * <p>Not version-scoped on purpose — see {@link AssetService#usedBy}. The screen that calls this
   * is showing an editor what their change will and will not reach.
   *
   * @param request the request
   * @param kind the kind
   * @param code the asset code
   * @return every pinning policy version
   */
  @GetMapping("/{kind}/{code}/usage")
  public List<AssetRepository.Usage> usage(
      HttpServletRequest request,
      @PathVariable AssetRepository.Kind kind,
      @PathVariable String code) {
    Principal caller = Authorisation.require(request, Role.Capability.POLICY_READ);
    return assets.usedBy(caller.tenantId(), kind, code);
  }

  /**
   * Opens a DRAFT, cloning the latest approved version by default.
   *
   * @param request the request
   * @param kind the kind
   * @param code the asset code
   * @param clone false to start empty rather than from the last approved version
   * @return the draft
   */
  @PostMapping("/{kind}/{code}/versions")
  public ResponseEntity<AssetService.AssetVersion> createDraft(
      HttpServletRequest request,
      @PathVariable AssetRepository.Kind kind,
      @PathVariable String code,
      @RequestParam(defaultValue = "true") boolean clone) {
    Principal author = Authorisation.require(request, Role.Capability.POLICY_EDIT);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(assets.createDraft(author.tenantId(), kind, code, author.subject(), clone));
  }

  /**
   * Replaces a DRAFT's content.
   *
   * @param request the request
   * @param kind the kind
   * @param code the asset code
   * @param version the version
   * @param content the whole content, in the shape a read returns
   * @return the saved version
   */
  @PutMapping("/{kind}/{code}/versions/{version}")
  public AssetService.AssetVersion save(
      HttpServletRequest request,
      @PathVariable AssetRepository.Kind kind,
      @PathVariable String code,
      @PathVariable int version,
      @RequestBody java.util.Map<String, Object> content) {
    Principal author = Authorisation.require(request, Role.Capability.POLICY_EDIT);
    return assets.save(
        author.tenantId(),
        kind,
        code,
        version,
        AssetService.json().valueToTree(content),
        author.subject());
  }

  /**
   * Submits a DRAFT for review.
   *
   * @param request the request
   * @param kind the kind
   * @param code the asset code
   * @param version the version
   * @return the submitted version
   */
  @PostMapping("/{kind}/{code}/versions/{version}/submit")
  public AssetService.AssetVersion submit(
      HttpServletRequest request,
      @PathVariable AssetRepository.Kind kind,
      @PathVariable String code,
      @PathVariable int version) {
    Principal author = Authorisation.require(request, Role.Capability.POLICY_SUBMIT);
    return assets.submit(author.tenantId(), kind, code, version, author.subject());
  }

  /**
   * Approves an asset version or requests changes.
   *
   * <p>The reviewer cannot be the author (AC-MODEL-007). Until this returns APPROVED, no policy
   * version can pin the asset.
   *
   * @param request the request
   * @param kind the kind
   * @param code the asset code
   * @param version the version
   * @param body the decision
   * @return the version
   */
  @PostMapping("/{kind}/{code}/versions/{version}/reviews")
  public AssetService.AssetVersion review(
      HttpServletRequest request,
      @PathVariable AssetRepository.Kind kind,
      @PathVariable String code,
      @PathVariable int version,
      @RequestBody ReviewRequest body) {
    Principal reviewer = Authorisation.require(request, Role.Capability.POLICY_REVIEW);
    return assets.review(
        reviewer.tenantId(),
        kind,
        code,
        version,
        reviewer,
        Boolean.TRUE.equals(body.approve()),
        body.comment());
  }

  /**
   * Discards a DRAFT. A version is never deleted.
   *
   * @param request the request
   * @param kind the kind
   * @param code the asset code
   * @param version the version
   * @return the discarded version
   */
  @DeleteMapping("/{kind}/{code}/versions/{version}")
  public AssetService.AssetVersion discard(
      HttpServletRequest request,
      @PathVariable AssetRepository.Kind kind,
      @PathVariable String code,
      @PathVariable int version) {
    Principal author = Authorisation.require(request, Role.Capability.POLICY_EDIT);
    return assets.discard(author.tenantId(), kind, code, version, author.subject());
  }
}
