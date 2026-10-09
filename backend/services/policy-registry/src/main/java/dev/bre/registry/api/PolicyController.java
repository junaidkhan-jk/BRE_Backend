package dev.bre.registry.api;

import dev.bre.registry.audit.AuditLog;
import dev.bre.registry.auth.Principal;
import dev.bre.registry.auth.Role;
import dev.bre.registry.policy.PolicyVersionRepository;
import dev.bre.registry.policy.PolicyVersionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Writing, saving, testing, submitting and approving policies.
 *
 * <p>The path an author actually walks, and it is the shape of the service:
 *
 * <pre>
 *   POST   /v1/policies                              create the policy (once)
 *   POST   /v1/policies/{code}/versions              open a DRAFT
 *   PUT    /v1/policies/{code}/versions/{n}/source   SAVE — compiles and runs tests on every save
 *   PUT    …/versions/{n}/test-cases/{name}          add an expectation
 *   POST   …/versions/{n}/submit                     DRAFT → IN_REVIEW, gated on the compile report
 *   POST   …/versions/{n}/reviews                    approve or request changes; approval SIGNS
 *   POST   /v1/deployments                           ship it (DeploymentController)
 * </pre>
 *
 * <p><b>Source is sent as {@code text/plain}, not wrapped in JSON.</b> A policy is a YAML document
 * an author edits in a text editor; JSON-escaping it to put it in a field would mean every newline
 * and quote in a credit policy survives a round trip through two encodings, and the first time that
 * goes wrong it corrupts the document silently. {@code PUT} of the raw bytes is both simpler and
 * harder to get wrong.
 */
@RestController
@RequestMapping("/v1/policies")
public class PolicyController {

  private final PolicyVersionService versions;
  private final PolicyVersionRepository repository;
  private final AuditLog audit;

  /**
   * Creates the controller.
   *
   * @param versions the lifecycle
   * @param repository the policy tables
   * @param audit the hash-chained audit log
   */
  public PolicyController(
      PolicyVersionService versions, PolicyVersionRepository repository, AuditLog audit) {
    this.versions = versions;
    this.repository = repository;
    this.audit = audit;
  }

  /**
   * A request to create a policy.
   *
   * @param policyCode the code, {@code ^[A-Z][A-Z0-9_]{1,63}$}
   * @param name the display name
   * @param stage the decision stage, e.g. {@code CREDIT_UW}
   * @param segment the segment, or null for all
   */
  public record CreatePolicyRequest(
      @NotBlank String policyCode, @NotBlank String name, @NotBlank String stage, String segment) {}

  /**
   * Creates a policy.
   *
   * @param request the request
   * @param body the policy
   * @return 201
   */
  @PostMapping
  public ResponseEntity<Map<String, String>> createPolicy(
      HttpServletRequest request, @Valid @RequestBody CreatePolicyRequest body) {
    Principal author = Authorisation.require(request, Role.Capability.POLICY_EDIT);
    repository.insertPolicy(
        author.tenantId(),
        body.policyCode(),
        body.name(),
        body.stage(),
        body.segment(),
        author.subject());
    audit.record(
        author.tenantId(),
        author.subject(),
        "POLICY.CREATED",
        "policy",
        body.policyCode(),
        "{\"stage\":\"" + body.stage() + "\"}");
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Map.of("policyCode", body.policyCode(), "name", body.name()));
  }

  /**
   * Lists this tenant's policies.
   *
   * @param request the request
   * @return code, name, stage and status per policy
   */
  @GetMapping
  public List<Map<String, String>> listPolicies(HttpServletRequest request) {
    Principal caller = Authorisation.require(request, Role.Capability.POLICY_READ);
    return repository.listPolicies(caller.tenantId()).stream()
        .map(row -> Map.of("policyCode", row[0], "name", row[1], "stage", row[2], "status", row[3]))
        .toList();
  }

  /**
   * Lists a policy's versions, newest first.
   *
   * @param request the request
   * @param policyCode the policy
   * @return version, status, bundle hash, approval time and author
   */
  @GetMapping("/{policyCode}/versions")
  public List<Map<String, String>> listVersions(
      HttpServletRequest request, @PathVariable String policyCode) {
    Principal caller = Authorisation.require(request, Role.Capability.POLICY_READ);
    return repository.listVersions(caller.tenantId(), policyCode).stream()
        .map(
            row ->
                Map.of(
                    "version", row[0],
                    "status", row[1],
                    "bundleHash", row[2],
                    "approvedAt", row[3],
                    "createdBy", row[4]))
        .toList();
  }

  /**
   * Opens a DRAFT, or returns the open one.
   *
   * @param request the request
   * @param policyCode the policy
   * @return the draft
   */
  @PostMapping("/{policyCode}/versions")
  public ResponseEntity<PolicyVersionService.SavedVersion> createDraft(
      HttpServletRequest request, @PathVariable String policyCode) {
    Principal author = Authorisation.require(request, Role.Capability.POLICY_EDIT);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(versions.createDraft(author.tenantId(), policyCode, author.subject()));
  }

  /**
   * Reads a version's state and latest compile report.
   *
   * @param request the request
   * @param policyCode the policy
   * @param version the version
   * @return the version
   */
  @GetMapping("/{policyCode}/versions/{version}")
  public PolicyVersionService.SavedVersion read(
      HttpServletRequest request, @PathVariable String policyCode, @PathVariable int version) {
    Principal caller = Authorisation.require(request, Role.Capability.POLICY_READ);
    return versions.read(caller.tenantId(), policyCode, version);
  }

  /**
   * Reads a version's PSF source.
   *
   * @param request the request
   * @param policyCode the policy
   * @param version the version
   * @return the source, or 404 when the draft has never been saved
   */
  @GetMapping(
      value = "/{policyCode}/versions/{version}/source",
      produces = MediaType.TEXT_PLAIN_VALUE)
  public ResponseEntity<String> source(
      HttpServletRequest request, @PathVariable String policyCode, @PathVariable int version) {
    Principal caller = Authorisation.require(request, Role.Capability.POLICY_READ);
    return repository
        .sourceOf(caller.tenantId(), policyCode, version)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
  }

  /**
   * Saves PSF source into a DRAFT, compiling and running its test cases.
   *
   * @param request the request
   * @param policyCode the policy
   * @param version the version
   * @param rowVersion the row version last read, for optimistic locking; omit to skip the check
   * @param psfYaml the policy source
   * @return the saved version with its compile report
   */
  @PutMapping(
      value = "/{policyCode}/versions/{version}/source",
      consumes = MediaType.TEXT_PLAIN_VALUE)
  public PolicyVersionService.SavedVersion save(
      HttpServletRequest request,
      @PathVariable String policyCode,
      @PathVariable int version,
      @RequestHeader(value = "If-Match", required = false) Long rowVersion,
      @RequestBody String psfYaml) {
    Principal author = Authorisation.require(request, Role.Capability.POLICY_EDIT);
    return versions.save(
        author.tenantId(), policyCode, version, psfYaml, author.subject(), rowVersion);
  }

  /**
   * A test case.
   *
   * @param asOf the business date to evaluate on
   * @param facts the fact snapshot — synthetic only (§9.7)
   * @param expect the expectations: {@code decision}, and optionally {@code steps}
   */
  public record TestCaseRequest(LocalDate asOf, Object facts, Object expect) {}

  /**
   * Creates or replaces a test case.
   *
   * @param request the request
   * @param policyCode the policy
   * @param version the version
   * @param name the case name
   * @param body the case
   * @return the version, recompiled so the new case's result is immediate
   */
  @PutMapping("/{policyCode}/versions/{version}/test-cases/{name}")
  public PolicyVersionService.SavedVersion upsertTestCase(
      HttpServletRequest request,
      @PathVariable String policyCode,
      @PathVariable int version,
      @PathVariable String name,
      @Valid @RequestBody TestCaseRequest body)
      throws com.fasterxml.jackson.core.JsonProcessingException {

    Principal author = Authorisation.require(request, Role.Capability.TEST_EDIT);
    com.fasterxml.jackson.databind.ObjectMapper json =
        new com.fasterxml.jackson.databind.ObjectMapper();
    repository.upsertTestCase(
        author.tenantId(),
        policyCode,
        version,
        name,
        body.asOf(),
        json.writeValueAsString(body.facts()),
        json.writeValueAsString(body.expect()));
    audit.record(
        author.tenantId(),
        author.subject(),
        "TEST_CASE.SAVED",
        "policy_version",
        policyCode + "@" + version,
        "{\"testName\":\"" + name + "\"}");

    // Recompile, so the author sees the new case's result without a second call. Re-saving the
    // existing source rather than inventing a "run tests" path keeps one code path compiling.
    return repository
        .sourceOf(author.tenantId(), policyCode, version)
        .map(
            source ->
                versions.save(
                    author.tenantId(), policyCode, version, source, author.subject(), null))
        .orElseGet(() -> versions.read(author.tenantId(), policyCode, version));
  }

  /**
   * Deletes a test case and recompiles.
   *
   * <p><b>The recompile is the point, not a nicety.</b> The submit gate reads the <em>latest stored
   * compile report</em> rather than re-deriving one, so a deletion that did not recompile would
   * leave a report naming a failure from a test case that no longer exists — and the version would
   * be unsubmittable with nothing in it to fix. Found by deleting a test case and then being unable
   * to submit a policy whose only failing assertion had just been removed.
   *
   * @param request the request
   * @param policyCode the policy
   * @param version the version
   * @param name the case name
   * @return the version with its fresh compile report, or 404 when there was no such case
   */
  @DeleteMapping("/{policyCode}/versions/{version}/test-cases/{name}")
  public ResponseEntity<PolicyVersionService.SavedVersion> deleteTestCase(
      HttpServletRequest request,
      @PathVariable String policyCode,
      @PathVariable int version,
      @PathVariable String name) {
    Principal author = Authorisation.require(request, Role.Capability.TEST_EDIT);
    if (!repository.deleteTestCase(author.tenantId(), policyCode, version, name)) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
    audit.record(
        author.tenantId(),
        author.subject(),
        "TEST_CASE.DELETED",
        "policy_version",
        policyCode + "@" + version,
        "{\"testName\":\"" + name + "\"}");

    return ResponseEntity.ok(
        repository
            .sourceOf(author.tenantId(), policyCode, version)
            .map(
                source ->
                    versions.save(
                        author.tenantId(), policyCode, version, source, author.subject(), null))
            .orElseGet(() -> versions.read(author.tenantId(), policyCode, version)));
  }

  /**
   * Submits a DRAFT for review.
   *
   * @param request the request
   * @param policyCode the policy
   * @param version the version
   * @return the submitted version
   */
  @PostMapping("/{policyCode}/versions/{version}/submit")
  public PolicyVersionService.SavedVersion submit(
      HttpServletRequest request, @PathVariable String policyCode, @PathVariable int version) {
    Principal author = Authorisation.require(request, Role.Capability.POLICY_SUBMIT);
    return versions.submit(author.tenantId(), policyCode, version, author.subject());
  }

  /**
   * A review decision.
   *
   * @param approve true to approve, false to request changes. Boxed for the same reason as {@code
   *     DeployRequest.expedited}: an absent field binds as null, and null into a primitive is a
   *     parse error rather than a default. Absent is treated as "not an approval", which is the
   *     safe reading of a review request that did not say.
   * @param comment the reviewer's comment
   */
  public record ReviewRequest(Boolean approve, String comment) {}

  /**
   * Records a review. Approval builds and signs the bundle.
   *
   * <p>Requires {@code POLICY_REVIEW} — which a {@code TENANT_ADMIN} does <b>not</b> hold, so an
   * administrator who wants to approve has to hold {@code POLICY_REVIEWER} as well. The §3.3
   * override only excuses them from the "not your own work" rule; it does not make them a reviewer.
   *
   * @param request the request
   * @param policyCode the policy
   * @param version the version
   * @param body the decision
   * @return the version, APPROVED and signed when the approval count is met
   */
  @PostMapping("/{policyCode}/versions/{version}/reviews")
  public PolicyVersionService.SavedVersion review(
      HttpServletRequest request,
      @PathVariable String policyCode,
      @PathVariable int version,
      @Valid @RequestBody ReviewRequest body) {
    Principal reviewer = Authorisation.require(request, Role.Capability.POLICY_REVIEW);
    return versions.review(
        reviewer.tenantId(),
        policyCode,
        version,
        reviewer,
        Boolean.TRUE.equals(body.approve()),
        body.comment());
  }

  /**
   * Discards a DRAFT.
   *
   * @param request the request
   * @param policyCode the policy
   * @param version the version
   * @return the discarded version
   */
  @PostMapping("/{policyCode}/versions/{version}/discard")
  public PolicyVersionService.SavedVersion discard(
      HttpServletRequest request, @PathVariable String policyCode, @PathVariable int version) {
    Principal author = Authorisation.require(request, Role.Capability.POLICY_EDIT);
    return versions.discard(author.tenantId(), policyCode, version, author.subject());
  }
}
