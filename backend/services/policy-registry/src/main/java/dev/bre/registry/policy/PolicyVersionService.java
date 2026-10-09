package dev.bre.registry.policy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.bre.registry.audit.AuditLog;
import dev.bre.registry.auth.Principal;
import dev.bre.registry.sign.BundleBuilder;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The policy-version lifecycle (00 §3.3).
 *
 * <pre>
 *   DRAFT ──submit──▶ IN_REVIEW ──approve(n)──▶ APPROVED ──supersede──▶ ARCHIVED
 *     │                   │
 *     │                   └──request changes──▶ DRAFT   (submission_round + 1)
 *     └──discard──▶ DISCARDED
 * </pre>
 *
 * <p><b>Every transition is enforced here and again by the database.</b> The CHECK constraints on
 * {@code policy_versions} already make several illegal states unrepresentable — an APPROVED row
 * must have a bundle hash and an approval time, an IN_REVIEW row must have a submission time. This
 * class is not a substitute for those; it exists so the failure is a 409 with a sentence rather
 * than a constraint violation, and so the <em>sequencing</em> rules the database cannot express are
 * also checked.
 *
 * <p><b>Three rules that look like policy but are the whole point of the service:</b>
 *
 * <ol>
 *   <li><b>A version is editable only in DRAFT.</b> Once submitted the bytes are frozen, because a
 *       reviewer must be reviewing what gets deployed. Without this an author could approve one
 *       policy and ship another, and the audit log would show an approval of something that no
 *       longer exists.
 *   <li><b>The reviewer cannot be an author.</b> Not "cannot be the creator" — cannot be <em>any of
 *       the editors</em>. {@code policy_version_editors} records everyone who ever saved the draft,
 *       precisely so that segregation of duties survives a draft passed between people.
 *   <li><b>Approval builds and signs the bundle.</b> Signing is not a later, separate step a
 *       release process might skip: the artefact a pod will serve is produced at the moment the
 *       approval is recorded, in the same transaction, from the exact IR that was reviewed.
 * </ol>
 *
 * <p><b>The administrator override.</b> A {@code TENANT_ADMIN} can be refused by rule 2 — they
 * authored the draft — and proceed anyway, because a lender onboarding with one administrator, or a
 * break-glass fix at 2am, has to be possible. Three things make that tolerable rather than a hole
 * in the control:
 *
 * <ul>
 *   <li>it is a <b>tenant setting</b>, {@code allow_admin_sod_override}, so a lender with two
 *       people who can review turns it off and an auditor finds it unavailable rather than merely
 *       unused;
 *   <li>every use writes a {@code POLICY_VERSION.SOD_OVERRIDDEN} entry to the hash-chained audit
 *       log, naming the person and what they bypassed;
 *   <li>{@code TENANT_ADMIN} does not hold {@code POLICY_REVIEW} as a capability — the override
 *       only converts a refusal into a recorded exception, it does not grant the role.
 * </ul>
 *
 * <p>Said plainly, because it is the kind of thing that gets built once and forgotten:
 * maker-checker on credit policy is a control a regulator expects to find working. An override that
 * nobody can see is the actual risk; one that is logged, attributable and switchable is a finding
 * somebody can review.
 */
@Service
public class PolicyVersionService {

  private static final Logger log = LoggerFactory.getLogger(PolicyVersionService.class);

  private final PolicyVersionRepository repository;
  private final CompileService compiler;
  private final BundleBuilder bundleBuilder;
  private final AuditLog audit;

  /**
   * Creates the service.
   *
   * @param repository the {@code bre_ctl} policy tables
   * @param compiler translates and checks a saved draft
   * @param bundleBuilder builds and signs the bundle on approval
   * @param audit the hash-chained audit log
   */
  public PolicyVersionService(
      PolicyVersionRepository repository,
      CompileService compiler,
      BundleBuilder bundleBuilder,
      AuditLog audit) {
    this.repository = repository;
    this.compiler = compiler;
    this.bundleBuilder = bundleBuilder;
    this.audit = audit;
  }

  /** Lifecycle states, exactly 00 §3.3's. */
  public enum Status {
    /** Editable. The only state in which the IR can change. */
    DRAFT,
    /** Frozen, awaiting reviewers. */
    IN_REVIEW,
    /** Signed and deployable. */
    APPROVED,
    /** Superseded by a later approved version. */
    ARCHIVED,
    /** Abandoned in draft. */
    DISCARDED
  }

  /**
   * A saved version, as the API returns it.
   *
   * @param policyCode the policy
   * @param version the version number
   * @param status the lifecycle state
   * @param submissionRound how many times it has been submitted; a reviewer's approval is tied to a
   *     round, so a resubmission after changes does not inherit the previous round's approvals
   * @param bundleHash the signed bundle, once approved
   * @param compileOk whether the latest compile passed
   * @param findings the latest compile's findings
   * @param tests the latest test-case run
   * @param rowVersion for optimistic locking
   */
  public record SavedVersion(
      String policyCode,
      int version,
      Status status,
      int submissionRound,
      Optional<String> bundleHash,
      boolean compileOk,
      List<String> findings,
      CompileService.TestResults tests,
      long rowVersion) {

    /** Creates a saved version, copying the list. */
    public SavedVersion {
      findings = List.copyOf(findings);
    }
  }

  /** A lifecycle rule was broken. Mapped to 409, never 500. */
  public static final class LifecycleException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what rule was broken, in a sentence an author can act on
     */
    public LifecycleException(String message) {
      super(message);
    }
  }

  /**
   * Creates a new DRAFT version of a policy, or returns the open draft if one exists.
   *
   * <p>Versions are sequential per policy and a policy has <b>at most one open draft</b>. The
   * alternative — many concurrent drafts — sounds flexible and is not: two authors would each
   * approve their own, and which one a pod serves would depend on deployment order rather than on
   * anybody's decision.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param actor who is creating it
   * @return the draft
   */
  @Transactional
  public SavedVersion createDraft(String tenantId, String policyCode, String actor) {
    if (repository.findPolicy(tenantId, policyCode).isEmpty()) {
      throw new LifecycleException(
          "no policy "
              + policyCode
              + " for this tenant. A version belongs to a policy; create the policy first.");
    }

    Optional<Integer> openDraft = repository.findOpenDraftVersion(tenantId, policyCode);
    if (openDraft.isPresent()) {
      // Returning it rather than failing: "new draft" and "continue the draft" are the same
      // intent from an author's point of view, and failing would make the client guess.
      return read(tenantId, policyCode, openDraft.get());
    }

    int next = repository.nextVersion(tenantId, policyCode);
    Optional<Integer> basedOn = repository.latestApprovedVersion(tenantId, policyCode);
    repository.insertDraft(tenantId, policyCode, next, basedOn, actor);
    repository.recordEditor(tenantId, policyCode, next, actor);
    audit.record(
        tenantId, actor, "POLICY_VERSION.CREATED", "policy_version", key(policyCode, next), "{}");
    return read(tenantId, policyCode, next);
  }

  /**
   * Saves PSF source into a DRAFT, compiling and checking it (00 §3.3).
   *
   * <p>Compiling <b>on every save</b>, not at submit. An author who finds out at submission time
   * that their table has a gap has to go back and re-read work they finished an hour ago; an author
   * who finds out on save is still holding the thought. The compile report is stored per save, so
   * the submit gate reads a fact rather than re-deriving one.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version
   * @param psfYaml the policy source
   * @param actor who is saving
   * @param expectedRowVersion the row version the client last read, for optimistic locking
   * @return the saved version with its compile report
   * @throws LifecycleException if the version is not in DRAFT, or somebody else has saved since
   */
  @Transactional
  public SavedVersion save(
      String tenantId,
      String policyCode,
      int version,
      String psfYaml,
      String actor,
      Long expectedRowVersion) {

    PolicyVersionRepository.Row row = require(tenantId, policyCode, version);
    if (row.status() != Status.DRAFT) {
      throw new LifecycleException(
          "version "
              + version
              + " is "
              + row.status()
              + ", not DRAFT, so it cannot be edited. A reviewer must be reviewing the bytes that"
              + " get deployed (00 §3.3); make a new version instead.");
    }

    // Translate and check BEFORE writing. A draft that does not even parse is still worth saving —
    // an author mid-edit must not lose work — so a PSF error is returned as a finding rather than
    // thrown, and the IR is left at whatever last compiled.
    CompileService.Result compiled =
        compiler.compile(
            tenantId,
            policyCode,
            version,
            psfYaml,
            repository.testCases(tenantId, policyCode, version));

    repository.saveDraft(
        tenantId,
        policyCode,
        version,
        psfYaml,
        compiled.ir().orElse(null),
        compiled.factSchemaCode(),
        compiled.factSchemaVersion(),
        expectedRowVersion);
    repository.recordEditor(tenantId, policyCode, version, actor);
    repository.insertCompileReport(tenantId, policyCode, version, compiled);

    audit.record(
        tenantId,
        actor,
        "POLICY_VERSION.SAVED",
        "policy_version",
        key(policyCode, version),
        // Findings count and pass/fail only. The findings themselves name fact PATHS and rule
        // codes, which are safe, but the audit detail is not where a 200-entry list belongs.
        "{\"compileOk\":" + compiled.ok() + ",\"findings\":" + compiled.findings().size() + "}");

    return read(tenantId, policyCode, version);
  }

  /**
   * Submits a DRAFT for review.
   *
   * <p><b>The gate is the point of this method.</b> §2.4 M9 and 00 §3.3 both require that every
   * test case passes before a version can be submitted, and that the static checks are clean. A
   * registry that let a policy with a known decision-table gap into review would be asking a
   * reviewer to find mechanically-detectable defects by reading, which is the one thing reviewers
   * are worst at and tooling is best at.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version
   * @param actor who is submitting
   * @return the submitted version
   * @throws LifecycleException if it is not a DRAFT, does not compile, or any test case fails
   */
  @Transactional
  public SavedVersion submit(String tenantId, String policyCode, int version, String actor) {
    PolicyVersionRepository.Row row = require(tenantId, policyCode, version);
    if (row.status() != Status.DRAFT) {
      throw new LifecycleException(
          "only a DRAFT can be submitted; version " + version + " is " + row.status() + ".");
    }

    PolicyVersionRepository.CompileReport report =
        repository
            .latestCompileReport(tenantId, policyCode, version)
            .orElseThrow(
                () ->
                    new LifecycleException(
                        "version "
                            + version
                            + " has never been compiled. Save the policy source first; submitting"
                            + " something that has not been checked is what the gate exists to"
                            + " prevent."));

    if (!report.ok()) {
      throw new LifecycleException(
          "version "
              + version
              + " has "
              + report.errorCount()
              + " static-check error(s) and cannot be submitted. A reviewer should not be asked to"
              + " find a decision-table gap by reading. Findings: "
              + report.firstFindings(5));
    }
    if (report.testsFailed() > 0) {
      throw new LifecycleException(
          report.testsFailed()
              + " of "
              + report.testsTotal()
              + " test case(s) fail, so version "
              + version
              + " cannot be submitted (00 §3.3). Failing: "
              + report.firstTestFailures(5));
    }
    if (report.testsTotal() == 0) {
      throw new LifecycleException(
          "version "
              + version
              + " has no test cases. A policy with no tests has nothing asserting it still does"
              + " what its author intended, and the next author has no way to find out. Add at"
              + " least one before submitting.");
    }

    repository.submit(tenantId, policyCode, version);
    audit.record(
        tenantId,
        actor,
        "POLICY_VERSION.SUBMITTED",
        "policy_version",
        key(policyCode, version),
        "{\"submissionRound\":" + (row.submissionRound() + 1) + "}");
    log.info(
        "policy version submitted tenant={} policy={} version={} round={}",
        tenantId,
        policyCode,
        version,
        row.submissionRound() + 1);
    return read(tenantId, policyCode, version);
  }

  /**
   * Records a review decision, approving and signing when enough approvals are in.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version
   * @param principal who is reviewing
   * @param approve true to approve, false to request changes
   * @param comment the reviewer's comment
   * @return the version, APPROVED when the approval count is met
   * @throws LifecycleException if it is not IN_REVIEW, or the reviewer is one of its authors and
   *     cannot override segregation of duties
   */
  @Transactional
  public SavedVersion review(
      String tenantId,
      String policyCode,
      int version,
      Principal principal,
      boolean approve,
      String comment) {

    String reviewer = principal.subject();
    PolicyVersionRepository.Row row = require(tenantId, policyCode, version);
    if (row.status() != Status.IN_REVIEW) {
      throw new LifecycleException(
          "version " + version + " is " + row.status() + ", so there is nothing to review.");
    }

    // Segregation of duties (00 §3.3). EVERY editor, not just the creator: a draft handed between
    // two people must not become reviewable by either of them.
    if (repository.isEditor(tenantId, policyCode, version, reviewer)) {
      if (!principal.mayOverrideSegregationOfDuties()) {
        throw new LifecycleException(
            "the reviewer cannot be one of this version's authors (00 §3.3). "
                + reviewer
                + " saved this draft, so somebody else has to review it."
                + (principal.can(dev.bre.registry.auth.Role.Capability.SOD_OVERRIDE)
                    ? " This tenant has allow_admin_sod_override switched off, so an administrator"
                        + " cannot bypass it either."
                    : ""));
      }
      // Proceeding, and saying so in the one place that cannot be edited afterwards.
      audit.record(
          tenantId,
          reviewer,
          "POLICY_VERSION.SOD_OVERRIDDEN",
          "policy_version",
          key(policyCode, version),
          "{\"control\":\"REVIEWER_IS_AUTHOR\",\"authority\":\"TENANT_ADMIN\"}");
      log.warn(
          "SEGREGATION OF DUTIES OVERRIDDEN: {} is reviewing a version they authored."
              + " tenant={} policy={} version={}. Permitted by allow_admin_sod_override and"
              + " recorded in the audit log.",
          reviewer,
          tenantId,
          policyCode,
          version);
    }

    repository.recordReview(
        tenantId, policyCode, version, row.submissionRound(), reviewer, approve, comment);
    audit.record(
        tenantId,
        reviewer,
        approve ? "POLICY_VERSION.REVIEW_APPROVED" : "POLICY_VERSION.CHANGES_REQUESTED",
        "policy_version",
        key(policyCode, version),
        "{\"submissionRound\":" + row.submissionRound() + "}");

    if (!approve) {
      // Back to DRAFT, and the round number advances — so the approvals this round collected do
      // not carry over to whatever gets submitted next.
      repository.returnToDraft(tenantId, policyCode, version);
      return read(tenantId, policyCode, version);
    }

    int required = repository.minPolicyReviewers(tenantId);
    int approvals = repository.approvalCount(tenantId, policyCode, version, row.submissionRound());
    if (approvals < required) {
      log.info(
          "policy version has {} of {} approvals tenant={} policy={} version={}",
          approvals,
          required,
          tenantId,
          policyCode,
          version);
      return read(tenantId, policyCode, version);
    }

    // Approval BUILDS AND SIGNS, in this transaction, from the reviewed IR. Not a later step: an
    // artefact produced after the approval is an artefact nobody approved.
    ObjectNode ir = repository.irOf(tenantId, policyCode, version);
    BundleBuilder.Built built = bundleBuilder.build(tenantId, policyCode, version, ir);

    repository.insertBundle(tenantId, policyCode, version, built);
    repository.approve(tenantId, policyCode, version, built.bundleHash());
    // Earlier approved versions of the same policy are archived. Nothing is edited in place and
    // nothing is deleted; a superseded version stays readable, because a decision taken against it
    // last year still has to be explainable.
    int archived = repository.archiveEarlierApproved(tenantId, policyCode, version);

    audit.record(
        tenantId,
        reviewer,
        "POLICY_VERSION.APPROVED",
        "policy_version",
        key(policyCode, version),
        "{\"bundleHash\":\""
            + built.bundleHash()
            + "\",\"approvals\":"
            + approvals
            + ",\"archivedEarlier\":"
            + archived
            + "}");
    log.info(
        "policy version APPROVED and signed tenant={} policy={} version={} bundle={}",
        tenantId,
        policyCode,
        version,
        built.bundleHash());

    return read(tenantId, policyCode, version);
  }

  /**
   * Discards a DRAFT.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version
   * @param actor who is discarding
   * @return the discarded version
   * @throws LifecycleException if it is not a DRAFT
   */
  @Transactional
  public SavedVersion discard(String tenantId, String policyCode, int version, String actor) {
    PolicyVersionRepository.Row row = require(tenantId, policyCode, version);
    if (row.status() != Status.DRAFT) {
      throw new LifecycleException(
          "only a DRAFT can be discarded; version "
              + version
              + " is "
              + row.status()
              + ". An approved version is archived by the next approval, never discarded — a"
              + " decision taken against it still has to be explainable.");
    }
    repository.discard(tenantId, policyCode, version);
    audit.record(
        tenantId,
        actor,
        "POLICY_VERSION.DISCARDED",
        "policy_version",
        key(policyCode, version),
        "{}");
    return read(tenantId, policyCode, version);
  }

  /**
   * Reads a version.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version
   * @return the version with its latest compile report
   */
  @Transactional(readOnly = true)
  public SavedVersion read(String tenantId, String policyCode, int version) {
    PolicyVersionRepository.Row row = require(tenantId, policyCode, version);
    Optional<PolicyVersionRepository.CompileReport> report =
        repository.latestCompileReport(tenantId, policyCode, version);
    return new SavedVersion(
        policyCode,
        version,
        row.status(),
        row.submissionRound(),
        row.bundleHash(),
        report.map(PolicyVersionRepository.CompileReport::ok).orElse(false),
        report.map(PolicyVersionRepository.CompileReport::findingSummaries).orElse(List.of()),
        report
            .map(
                r ->
                    new CompileService.TestResults(
                        r.testsTotal(), r.testsTotal() - r.testsFailed(), r.testFailures()))
            .orElse(new CompileService.TestResults(0, 0, List.of())),
        row.rowVersion());
  }

  private PolicyVersionRepository.Row require(String tenantId, String policyCode, int version) {
    return repository
        .find(tenantId, policyCode, version)
        .orElseThrow(
            () ->
                new LifecycleException(
                    "no version " + version + " of " + policyCode + " for this tenant."));
  }

  private static String key(String policyCode, int version) {
    return policyCode + "@" + version;
  }
}
