package dev.bre.registry.api;

import dev.bre.registry.audit.AuditLog;
import dev.bre.registry.auth.Principal;
import dev.bre.registry.auth.Role;
import dev.bre.registry.policy.PolicyDiff;
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
import org.springframework.web.bind.annotation.RequestParam;
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
 *   POST   …/versions/{n}/run                        RUN it against facts — any state, no deploy
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

  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper();

  private final PolicyVersionService versions;
  private final PolicyVersionRepository repository;
  private final dev.bre.registry.policy.PolicyRunService runs;
  private final AuditLog audit;

  /**
   * Creates the controller.
   *
   * @param versions the lifecycle
   * @param repository the policy tables
   * @param runs runs a version against supplied facts
   * @param audit the hash-chained audit log
   */
  public PolicyController(
      PolicyVersionService versions,
      PolicyVersionRepository repository,
      dev.bre.registry.policy.PolicyRunService runs,
      AuditLog audit) {
    this.versions = versions;
    this.repository = repository;
    this.runs = runs;
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
   * @return one entry per version, newest first, with who did what and when (M5-04)
   */
  @GetMapping("/{policyCode}/versions")
  public List<PolicyVersionRepository.HistoryEntry> listVersions(
      HttpServletRequest request, @PathVariable String policyCode) {
    Principal caller = Authorisation.require(request, Role.Capability.POLICY_READ);
    return repository.listVersions(caller.tenantId(), policyCode);
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
      HttpServletRequest request,
      @PathVariable String policyCode,
      @RequestParam(required = false) Integer cloneFrom) {
    Principal author = Authorisation.require(request, Role.Capability.POLICY_EDIT);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(versions.createDraft(author.tenantId(), policyCode, author.subject(), cloneFrom));
  }

  /**
   * What an import carries.
   *
   * @param policyCode the code to import as — the importing tenant's choice, because two lenders
   *     may use different codes for the same policy
   * @param name a display name, used only when the policy does not exist here yet
   * @param psf the PSF source
   */
  public record ImportRequest(String policyCode, String name, String psf) {}

  /**
   * Imports a policy from PSF source (§2.6, M3-02).
   *
   * <p>It lands as a DRAFT and is marked imported. An import that arrived APPROVED would be a way
   * to put a policy into production without anybody at the receiving lender reviewing it.
   *
   * @param request the request
   * @param body the code, name and source
   * @return the draft, with its compile report — which may carry findings, since another
   *     installation's policy can reference parameters this one has never defined
   */
  @PostMapping("/import")
  public ResponseEntity<PolicyVersionService.SavedVersion> importPolicy(
      HttpServletRequest request, @RequestBody ImportRequest body) {
    Principal author = Authorisation.require(request, Role.Capability.POLICY_EDIT);
    if (body.psf() == null || body.psf().isBlank()) {
      throw new PolicyVersionService.LifecycleException("psf is required and must not be empty.");
    }
    if (body.policyCode() == null || body.policyCode().isBlank()) {
      throw new PolicyVersionService.LifecycleException(
          "policyCode is required. PSF carries no tenant and no code of its own — which is what"
              + " makes a policy portable — so the importing tenant chooses what to call it.");
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            versions.importVersion(
                author.tenantId(), body.policyCode(), body.name(), body.psf(), author.subject()));
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
   * The compiled policy, as the studio renders it (U3, U5, U6, U7).
   *
   * <p>The IR rather than the source, because the source is YAML a person wrote and the IR is the
   * structure the engine actually runs: every table's rows in order, every scorecard's bins, the
   * flow's stages and their knockout markers. A grid drawn from the source would be a second
   * parser, and the two would disagree first about the edge case nobody tested.
   *
   * <p>A {@code Map}, not a Jackson 2 node — Spring Boot 4 serialises HTTP with Jackson 3, to
   * which a Jackson 2 node is an unrecognised POJO that comes out as every bean accessor and none
   * of the data.
   *
   * @param request the request
   * @param policyCode the policy
   * @param version the version
   * @return the IR
   */
  @GetMapping("/{policyCode}/versions/{version}/ir")
  public Map<String, Object> ir(
      HttpServletRequest request, @PathVariable String policyCode, @PathVariable int version) {
    Principal caller = Authorisation.require(request, Role.Capability.POLICY_READ);
    return JSON.convertValue(
        requireIr(caller.tenantId(), policyCode, version),
        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
  }

  /**
   * Compares two versions of a policy (M3-02).
   *
   * <p>Available on any pair, in either direction, including a DRAFT — reviewing a change means
   * reading what it does to the version it replaces, and that comparison has to be possible before
   * the change is submitted, not only after.
   *
   * @param request the request
   * @param policyCode the policy
   * @param from the earlier version
   * @param to the later version
   * @return the semantic and text diffs
   */
  @GetMapping("/{policyCode}/versions/{from}/diff/{to}")
  public PolicyDiff.Diff diff(
      HttpServletRequest request,
      @PathVariable String policyCode,
      @PathVariable int from,
      @PathVariable int to) {
    Principal caller = Authorisation.require(request, Role.Capability.POLICY_READ);
    com.fasterxml.jackson.databind.node.ObjectNode fromIr =
        requireIr(caller.tenantId(), policyCode, from);
    com.fasterxml.jackson.databind.node.ObjectNode toIr =
        requireIr(caller.tenantId(), policyCode, to);
    return PolicyDiff.compare(
        policyCode,
        from,
        to,
        fromIr,
        toIr,
        repository.sourceOf(caller.tenantId(), policyCode, from).orElse(null),
        repository.sourceOf(caller.tenantId(), policyCode, to).orElse(null));
  }

  private com.fasterxml.jackson.databind.node.ObjectNode requireIr(
      String tenantId, String policyCode, int version) {
    com.fasterxml.jackson.databind.node.ObjectNode ir =
        repository.irOf(tenantId, policyCode, version);
    if (ir == null || ir.isEmpty()) {
      throw new PolicyVersionService.LifecycleException(
          "version "
              + version
              + " of "
              + policyCode
              + " has never been saved, so there is nothing to compare. An empty draft and a"
              + " missing version look the same from here.");
    }
    return ir;
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
   * A request to run a version against facts.
   *
   * @param facts the fact snapshot
   * @param asOf the business date to evaluate on; required, because the same facts on two dates are
   *     two different decisions and defaulting to today would silently pick one
   */
  public record RunRequest(Map<String, Object> facts, LocalDate asOf) {}

  /**
   * Runs a version against a fact snapshot.
   *
   * <p><b>Any state, including DRAFT — that is the point of it.</b> An author writing a rule needs
   * to know what it does with a particular applicant before it is finished, let alone approved;
   * requiring a deployment first is how a half-written rule reaches an environment.
   *
   * <p>Nothing is persisted. No decision record, no audit entry, and the facts are gone when the
   * response returns.
   *
   * @param request the request
   * @param policyCode the policy
   * @param version the version
   * @param body the facts and the business date
   * @return what the policy did, per flow step and per entity
   */
  @PostMapping("/{policyCode}/versions/{version}/run")
  public dev.bre.registry.policy.PolicyRunService.Run run(
      HttpServletRequest request,
      @PathVariable String policyCode,
      @PathVariable int version,
      @Valid @RequestBody RunRequest body) {

    Principal caller = Authorisation.require(request, Role.Capability.SIMULATE);
    if (body.asOf() == null) {
      throw new PolicyVersionService.LifecycleException(
          "asOf is required. The same facts on two dates are two different decisions — an age, a"
              + " group's tenure and a delinquency window all move — so the run has to be told"
              + " which date it is being asked about rather than quietly using today's.");
    }
    return runs.run(
        caller.tenantId(),
        policyCode,
        version,
        body.facts() == null ? Map.of() : body.facts(),
        body.asOf());
  }

  /**
   * The evidence FR-LIFE-002 requires at submit.
   *
   * @param changeNote what changed and why, at least ten characters
   * @param committeeReference the credit-committee minute, ticket or approval behind the change
   */
  public record SubmitRequest(String changeNote, String committeeReference) {}

  /**
   * Submits a DRAFT for review.
   *
   * @param request the request
   * @param policyCode the policy
   * @param version the version
   * @param body the change note and committee reference
   * @return the submitted version
   */
  @PostMapping("/{policyCode}/versions/{version}/submit")
  public PolicyVersionService.SavedVersion submit(
      HttpServletRequest request,
      @PathVariable String policyCode,
      @PathVariable int version,
      @RequestBody(required = false) SubmitRequest body) {
    Principal author = Authorisation.require(request, Role.Capability.POLICY_SUBMIT);
    return versions.submit(
        author.tenantId(),
        policyCode,
        version,
        author.subject(),
        body == null ? null : body.changeNote(),
        body == null ? null : body.committeeReference());
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
