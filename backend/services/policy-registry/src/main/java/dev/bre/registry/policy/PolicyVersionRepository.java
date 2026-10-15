package dev.bre.registry.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.bre.registry.sign.BundleBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The {@code bre_ctl} policy, version, editor, compile-report and bundle tables (§5.2). */
@Repository
public class PolicyVersionRepository {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final JdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc the control-plane database
   */
  public PolicyVersionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * A policy-version row.
   *
   * @param status the lifecycle state
   * @param submissionRound how many times it has been submitted
   * @param bundleHash the signed bundle, once approved
   * @param rowVersion for optimistic locking
   */
  public record Row(
      PolicyVersionService.Status status,
      int submissionRound,
      Optional<String> bundleHash,
      Optional<String> changeNote,
      Optional<String> committeeReference,
      long rowVersion) {}

  /**
   * The latest compile report for a version.
   *
   * @param ok whether it may be submitted
   * @param errorCount how many findings are errors
   * @param findingSummaries one line per finding
   * @param testsTotal how many test cases ran
   * @param testsFailed how many failed
   * @param testFailures one line per failure
   * @param coverage what the test cases exercised, or empty for a report written before V13
   */
  public record CompileReport(
      boolean ok,
      long errorCount,
      List<String> findingSummaries,
      int testsTotal,
      int testsFailed,
      List<String> testFailures,
      Optional<CoverageView> coverage) {

    /** Creates a report, copying the lists. */
    public CompileReport {
      findingSummaries = List.copyOf(findingSummaries);
      testFailures = List.copyOf(testFailures);
    }

    /**
     * The figure the submit gate compares against the threshold.
     *
     * <p>Zero when the report predates V13 and carries no coverage object. Refusing the submission
     * is the right reading: the version has not been measured, and a re-save measures it.
     */
    public java.math.BigDecimal coveragePercent() {
      return coverage.map(CoverageView::overallPercent).orElse(java.math.BigDecimal.ZERO);
    }

    /** Every item no test case reached, across all three kinds. */
    public List<String> uncovered() {
      return coverage
          .map(
              c -> {
                List<String> all = new ArrayList<>(c.uncoveredRuleStatuses());
                all.addAll(c.uncoveredRows());
                all.addAll(c.uncoveredBins());
                return all;
              })
          .orElse(List.of());
    }

    /** The first few uncovered items, for a refusal message. */
    public String firstUncovered(int limit) {
      return String.join(", ", uncovered().stream().limit(limit).toList());
    }

    /** The first few findings, for a refusal message. */
    public String firstFindings(int limit) {
      return String.join("; ", findingSummaries.stream().limit(limit).toList());
    }

    /** The first few test failures, for a refusal message. */
    public String firstTestFailures(int limit) {
      return String.join("; ", testFailures.stream().limit(limit).toList());
    }
  }

  /**
   * Finds a policy.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @return the policy's name, if it exists
   */
  public Optional<String> findPolicy(String tenantId, String policyCode) {
    return jdbc
        .query(
            "SELECT name FROM bre_ctl.policies WHERE tenant_id = ? AND policy_code = ?",
            (rs, rowNum) -> rs.getString(1),
            tenantId,
            policyCode)
        .stream()
        .findFirst();
  }

  /**
   * Creates a policy.
   *
   * @param tenantId the tenant
   * @param policyCode the code
   * @param name the display name
   * @param stage the decision stage, e.g. {@code CREDIT_UW}
   * @param segment the segment, or null for all
   * @param createdBy who created it
   */
  public void insertPolicy(
      String tenantId,
      String policyCode,
      String name,
      String stage,
      String segment,
      String createdBy) {
    jdbc.update(
        """
        INSERT INTO bre_ctl.policies (tenant_id, policy_code, name, stage, segment, created_by)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        tenantId,
        policyCode,
        name,
        stage,
        segment,
        createdBy);
  }

  /**
   * Lists a tenant's policies.
   *
   * @param tenantId the tenant
   * @return code, name and stage per policy
   */
  public List<String[]> listPolicies(String tenantId) {
    return jdbc.query(
        """
        SELECT policy_code, name, stage, status FROM bre_ctl.policies
         WHERE tenant_id = ? ORDER BY policy_code
        """,
        (rs, rowNum) ->
            new String[] {
              rs.getString("policy_code"),
              rs.getString("name"),
              rs.getString("stage"),
              rs.getString("status")
            },
        tenantId);
  }

  /**
   * Finds a version.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version
   * @return the row, if it exists
   */
  public Optional<Row> find(String tenantId, String policyCode, int version) {
    return jdbc
        .query(
            """
            SELECT status, submission_round, bundle_hash, change_note, committee_reference,
                   row_version
              FROM bre_ctl.policy_versions
             WHERE tenant_id = ? AND policy_code = ? AND version = ?
            """,
            (rs, rowNum) ->
                new Row(
                    PolicyVersionService.Status.valueOf(rs.getString("status")),
                    rs.getInt("submission_round"),
                    Optional.ofNullable(rs.getString("bundle_hash")),
                    Optional.ofNullable(rs.getString("change_note")),
                    Optional.ofNullable(rs.getString("committee_reference")),
                    rs.getLong("row_version")),
            tenantId,
            policyCode,
            version)
        .stream()
        .findFirst();
  }

  /**
   * Lists a policy's versions, newest first.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @return version, status and bundle hash per row
   */
  public List<HistoryEntry> listVersions(String tenantId, String policyCode) {
    return jdbc.query(
        """
        SELECT v.version, v.status, v.bundle_hash, v.based_on_version, v.imported,
               v.submission_round, v.change_note, v.committee_reference, v.created_by,
               to_char(v.created_at,   'YYYY-MM-DD"T"HH24:MI:SSZ') AS created_at,
               to_char(v.submitted_at, 'YYYY-MM-DD"T"HH24:MI:SSZ') AS submitted_at,
               to_char(v.approved_at,  'YYYY-MM-DD"T"HH24:MI:SSZ') AS approved_at,
               (SELECT array_agg(e.editor ORDER BY e.first_edit_at)
                  FROM bre_ctl.policy_version_editors e
                 WHERE e.tenant_id = v.tenant_id AND e.policy_code = v.policy_code
                   AND e.policy_version = v.version) AS editors,
               (SELECT array_agg(r.reviewer ORDER BY r.created_at)
                  FROM bre_ctl.policy_version_reviews r
                 WHERE r.tenant_id = v.tenant_id AND r.policy_code = v.policy_code
                   AND r.policy_version = v.version AND r.decision = 'APPROVE') AS approvers
          FROM bre_ctl.policy_versions v
         WHERE v.tenant_id = ? AND v.policy_code = ? ORDER BY v.version DESC
        """,
        (rs, rowNum) ->
            new HistoryEntry(
                rs.getInt("version"),
                rs.getString("status"),
                Optional.ofNullable(rs.getString("bundle_hash")),
                Optional.ofNullable((Integer) rs.getObject("based_on_version")),
                rs.getBoolean("imported"),
                rs.getInt("submission_round"),
                Optional.ofNullable(rs.getString("change_note")),
                Optional.ofNullable(rs.getString("committee_reference")),
                rs.getString("created_by"),
                rs.getString("created_at"),
                Optional.ofNullable(rs.getString("submitted_at")),
                Optional.ofNullable(rs.getString("approved_at")),
                array(rs.getArray("editors")),
                array(rs.getArray("approvers"))),
        tenantId,
        policyCode);
  }

  /**
   * One version, as the history reads it (M5-04).
   *
   * <p><b>Editors and approvers are both lists, and both are the point.</b> A version's history is
   * not "who created it" — a draft handed between two people has two editors, and segregation of
   * duties is enforced against all of them. Showing only the creator would make the reviewer look
   * independent when they are not.
   *
   * @param version the version number
   * @param status its lifecycle state
   * @param bundleHash the signed bundle, once approved
   * @param basedOnVersion the version this one was opened from
   * @param imported whether it arrived from another installation (§2.6)
   * @param submissionRound how many times it has been submitted
   * @param changeNote what the author said changed, and why
   * @param committeeReference the governance record behind the change
   * @param createdBy who opened the draft
   * @param createdAt when
   * @param submittedAt when it went for review
   * @param approvedAt when it was approved
   * @param editors everyone who saved it, in the order they first did
   * @param approvers everyone who approved it
   */
  public record HistoryEntry(
      int version,
      String status,
      Optional<String> bundleHash,
      Optional<Integer> basedOnVersion,
      boolean imported,
      int submissionRound,
      Optional<String> changeNote,
      Optional<String> committeeReference,
      String createdBy,
      String createdAt,
      Optional<String> submittedAt,
      Optional<String> approvedAt,
      List<String> editors,
      List<String> approvers) {

    /** Creates an entry, copying both lists. */
    public HistoryEntry {
      editors = List.copyOf(editors);
      approvers = List.copyOf(approvers);
    }
  }

  private static List<String> array(java.sql.Array array) throws java.sql.SQLException {
    if (array == null) {
      return List.of();
    }
    return List.of((String[]) array.getArray());
  }

  /**
   * The open DRAFT version of a policy, if there is one.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @return the version number
   */
  public Optional<Integer> findOpenDraftVersion(String tenantId, String policyCode) {
    return jdbc
        .query(
            """
            SELECT version FROM bre_ctl.policy_versions
             WHERE tenant_id = ? AND policy_code = ? AND status IN ('DRAFT','IN_REVIEW')
             ORDER BY version DESC
            """,
            (rs, rowNum) -> rs.getInt(1),
            tenantId,
            policyCode)
        .stream()
        .findFirst();
  }

  /**
   * The next version number for a policy.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @return one more than the highest existing version, or 1
   */
  public Integer nextVersion(String tenantId, String policyCode) {
    return jdbc.queryForObject(
        """
        SELECT coalesce(max(version), 0) + 1 FROM bre_ctl.policy_versions
         WHERE tenant_id = ? AND policy_code = ?
        """,
        Integer.class,
        tenantId,
        policyCode);
  }

  /**
   * The latest APPROVED version of a policy.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @return the version, if any has been approved
   */
  public Optional<Integer> latestApprovedVersion(String tenantId, String policyCode) {
    return jdbc
        .query(
            """
            SELECT version FROM bre_ctl.policy_versions
             WHERE tenant_id = ? AND policy_code = ? AND status = 'APPROVED'
             ORDER BY version DESC
            """,
            (rs, rowNum) -> rs.getInt(1),
            tenantId,
            policyCode)
        .stream()
        .findFirst();
  }

  /**
   * Inserts a DRAFT.
   *
   * <p>The IR starts as an empty object: the column is {@code NOT NULL} and a brand-new draft has
   * no source yet. An empty IR is also what makes an un-saved draft fail its own submit gate, which
   * is the correct outcome.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the new version number
   * @param basedOn the version this one started from, if any
   * @param actor who created it
   */
  public void insertDraft(
      String tenantId, String policyCode, int version, Optional<Integer> basedOn, String actor) {
    insertDraft(tenantId, policyCode, version, basedOn, actor, false);
  }

  /**
   * Inserts an empty DRAFT, recording whether it arrived from elsewhere.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version number
   * @param basedOn the version it was opened from
   * @param actor who opened it
   * @param imported true when the source came from another installation (§2.6)
   */
  public void insertDraft(
      String tenantId,
      String policyCode,
      int version,
      Optional<Integer> basedOn,
      String actor,
      boolean imported) {
    jdbc.update(
        """
        INSERT INTO bre_ctl.policy_versions (
            tenant_id, policy_code, version, status, ir, fact_schema_code, fact_schema_version,
            based_on_version, imported, created_by)
        VALUES (?, ?, ?, 'DRAFT', '{}'::jsonb, ?, ?, ?, ?, ?)
        """,
        tenantId,
        policyCode,
        version,
        dev.bre.facts.LendingFacts.CODE,
        dev.bre.facts.LendingFacts.VERSION,
        basedOn.orElse(null),
        imported,
        actor);
  }

  /**
   * Saves draft source and its translated IR, under optimistic locking.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version
   * @param psfYaml the source
   * @param ir the translated IR, or null when it did not translate
   * @param factSchemaCode the declared fact schema
   * @param factSchemaVersion its version
   * @param expectedRowVersion the row version the client last read, or null to skip the check
   * @throws OptimisticLockingFailureException when somebody else has saved since
   */
  public void saveDraft(
      String tenantId,
      String policyCode,
      int version,
      String psfYaml,
      ObjectNode ir,
      String factSchemaCode,
      String factSchemaVersion,
      Long expectedRowVersion) {

    // Optimistic locking, because two authors in one draft is a real situation and silently
    // keeping the last write is the wrong answer: the loser's edits vanish with no error, and
    // they find out when the policy behaves unexpectedly.
    int updated =
        jdbc.update(
            """
            UPDATE bre_ctl.policy_versions
               SET psf_source = ?,
                   ir = coalesce(?::jsonb, ir),
                   fact_schema_code = coalesce(nullif(?, ''), fact_schema_code),
                   fact_schema_version = coalesce(nullif(?, ''), fact_schema_version),
                   row_version = row_version + 1,
                   updated_at = now()
             WHERE tenant_id = ? AND policy_code = ? AND version = ? AND status = 'DRAFT'
               -- Cast required: PostgreSQL cannot infer the type of a bare parameter in
               -- `? IS NULL`, and fails at PREPARE with "could not determine data type of
               -- parameter" — before any value is looked at, so it fails for a supplied row
               -- version as well as for an absent one.
               AND (?::bigint IS NULL OR row_version = ?::bigint)
            """,
            psfYaml,
            ir == null ? null : ir.toString(),
            factSchemaCode,
            factSchemaVersion,
            tenantId,
            policyCode,
            version,
            expectedRowVersion,
            expectedRowVersion);

    if (updated == 0) {
      throw new OptimisticLockingFailureException(
          "version "
              + version
              + " of "
              + policyCode
              + " changed since you last read it, or is no longer a DRAFT. Re-read it and apply"
              + " your edit again — overwriting would discard somebody else's work without"
              + " telling either of you.");
    }
  }

  /**
   * Records that somebody edited a draft (§3.3 segregation of duties).
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version
   * @param editor who saved
   */
  public void recordEditor(String tenantId, String policyCode, int version, String editor) {
    jdbc.update(
        """
        INSERT INTO bre_ctl.policy_version_editors (tenant_id, policy_code, policy_version, editor)
        VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
        """,
        tenantId,
        policyCode,
        version,
        editor);
  }

  /**
   * Whether somebody is one of a version's editors.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version
   * @param subject the person
   * @return true when they ever saved this draft
   */
  public boolean isEditor(String tenantId, String policyCode, int version, String subject) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM bre_ctl.policy_version_editors
             WHERE tenant_id = ? AND policy_code = ? AND policy_version = ? AND editor = ?
            """,
            Integer.class,
            tenantId,
            policyCode,
            version,
            subject);
    return count != null && count > 0;
  }

  /**
   * Writes a compile report.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version
   * @param result the compile result
   */
  public void insertCompileReport(
      String tenantId, String policyCode, int version, CompileService.Result result) {
    jdbc.update(
        """
        INSERT INTO bre_ctl.compile_reports (
            tenant_id, policy_code, policy_version, report_no, engine_version, ok, findings,
            tests, coverage)
        VALUES (?, ?, ?,
                (SELECT coalesce(max(report_no), 0) + 1 FROM bre_ctl.compile_reports
                  WHERE tenant_id = ? AND policy_code = ? AND policy_version = ?),
                ?, ?, ?::jsonb, ?::jsonb, ?::jsonb)
        """,
        tenantId,
        policyCode,
        version,
        tenantId,
        policyCode,
        version,
        "0.1.0-SNAPSHOT",
        result.ok(),
        writeJson(result.findings()),
        writeJson(result.tests()),
        writeJson(CoverageView.of(result.coverage())));
  }

  /**
   * The latest compile report for a version.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version
   * @return the report, if the version has ever been compiled
   */
  public Optional<CompileReport> latestCompileReport(
      String tenantId, String policyCode, int version) {
    return jdbc
        .query(
            """
            SELECT ok, findings, tests, coverage FROM bre_ctl.compile_reports
             WHERE tenant_id = ? AND policy_code = ? AND policy_version = ?
             ORDER BY report_no DESC LIMIT 1
            """,
            (rs, rowNum) -> {
              JsonNode findings = readJson(rs.getString("findings"));
              JsonNode tests = readJson(rs.getString("tests"));
              List<String> summaries = new ArrayList<>();
              long errors = 0;
              for (JsonNode finding : findings) {
                summaries.add(
                    finding.path("code").asText()
                        + " "
                        + finding.path("severity").asText()
                        + " "
                        + finding.path("path").asText()
                        + ": "
                        + finding.path("message").asText());
                if ("ERROR".equals(finding.path("severity").asText())) {
                  errors++;
                }
              }
              List<String> failures = new ArrayList<>();
              tests.path("failures").forEach(f -> failures.add(f.asText()));

              // A report written before V13 has no coverage object at all, so this is empty
              // rather than zeroed: "not measured" and "measured at 0%" are different facts, and
              // only the first one is fixed by saving the draft again.
              JsonNode coverage = readJson(rs.getString("coverage"));
              Optional<CoverageView> view =
                  coverage.hasNonNull("overallPercent")
                      ? Optional.of(JSON.convertValue(coverage, CoverageView.class))
                      : Optional.empty();
              return new CompileReport(
                  rs.getBoolean("ok"),
                  errors,
                  summaries,
                  tests.path("total").asInt(),
                  tests.path("total").asInt() - tests.path("passed").asInt(),
                  failures,
                  view);
            },
            tenantId,
            policyCode,
            version)
        .stream()
        .findFirst();
  }

  /**
   * The IR of a version.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version
   * @return the IR
   */
  public ObjectNode irOf(String tenantId, String policyCode, int version) {
    String ir =
        jdbc.queryForObject(
            """
            SELECT ir::text FROM bre_ctl.policy_versions
             WHERE tenant_id = ? AND policy_code = ? AND version = ?
            """,
            String.class,
            tenantId,
            policyCode,
            version);
    return (ObjectNode) readJson(ir);
  }

  /**
   * The PSF source of a version.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version
   * @return the source, or empty when the draft has never been saved
   */
  public Optional<String> sourceOf(String tenantId, String policyCode, int version) {
    return jdbc
        .query(
            """
            SELECT psf_source FROM bre_ctl.policy_versions
             WHERE tenant_id = ? AND policy_code = ? AND version = ?
            """,
            (rs, rowNum) -> rs.getString(1),
            tenantId,
            policyCode,
            version)
        .stream()
        .filter(java.util.Objects::nonNull)
        .findFirst();
  }

  /**
   * Moves a DRAFT to IN_REVIEW, recording the submit evidence (FR-LIFE-002).
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version
   * @param changeNote what changed and why
   * @param committeeReference the governance record that agreed to it
   */
  public void submit(
      String tenantId,
      String policyCode,
      int version,
      String changeNote,
      String committeeReference) {
    jdbc.update(
        """
        UPDATE bre_ctl.policy_versions
           SET status = 'IN_REVIEW', submitted_at = now(),
               submission_round = submission_round + 1,
               change_note = ?, committee_reference = ?,
               row_version = row_version + 1, updated_at = now()
         WHERE tenant_id = ? AND policy_code = ? AND version = ? AND status = 'DRAFT'
        """,
        changeNote,
        committeeReference,
        tenantId,
        policyCode,
        version);
  }

  /**
   * This tenant's minimum coverage for submit (FR-TEST-003).
   *
   * @param tenantId the tenant
   * @return the threshold as a percentage
   */
  public java.math.BigDecimal minCoveragePercent(String tenantId) {
    return jdbc
        .query(
            "SELECT min_coverage_percent FROM bre_ctl.tenants WHERE tenant_id = ?",
            (rs, rowNum) -> rs.getBigDecimal(1),
            tenantId)
        .stream()
        .findFirst()
        // A tenant row that cannot be read is not a reason to wave a submission through. The
        // query runs under RLS as the application role, so an empty result means the caller's
        // tenant scope does not match — fail closed on the specification's own default.
        .orElse(new java.math.BigDecimal("80.0"));
  }

  /**
   * Returns an IN_REVIEW version to DRAFT.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version
   */
  public void returnToDraft(String tenantId, String policyCode, int version) {
    jdbc.update(
        """
        UPDATE bre_ctl.policy_versions
           SET status = 'DRAFT', submitted_at = NULL,
               row_version = row_version + 1, updated_at = now()
         WHERE tenant_id = ? AND policy_code = ? AND version = ? AND status = 'IN_REVIEW'
        """,
        tenantId,
        policyCode,
        version);
  }

  /**
   * Records a review decision.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version
   * @param round the submission round the review belongs to
   * @param reviewer who reviewed
   * @param approve whether they approved
   * @param comment their comment
   */
  public void recordReview(
      String tenantId,
      String policyCode,
      int version,
      int round,
      String reviewer,
      boolean approve,
      String comment) {
    jdbc.update(
        """
        INSERT INTO bre_ctl.policy_version_reviews (
            tenant_id, policy_code, policy_version, submission_round, reviewer, decision, comment)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (tenant_id, policy_code, policy_version, submission_round, reviewer)
        DO UPDATE SET decision = excluded.decision, comment = excluded.comment
        """,
        tenantId,
        policyCode,
        version,
        round,
        reviewer,
        approve ? "APPROVE" : "REQUEST_CHANGES",
        comment);
  }

  /**
   * How many distinct reviewers approved this submission round.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version
   * @param round the round
   * @return the count
   */
  public int approvalCount(String tenantId, String policyCode, int version, int round) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM bre_ctl.policy_version_reviews
             WHERE tenant_id = ? AND policy_code = ? AND policy_version = ?
               AND submission_round = ? AND decision = 'APPROVE'
            """,
            Integer.class,
            tenantId,
            policyCode,
            version,
            round);
    return count == null ? 0 : count;
  }

  /**
   * How many approvals a version needs in this tenant.
   *
   * @param tenantId the tenant
   * @return {@code min_policy_reviewers}, default 1
   */
  public int minPolicyReviewers(String tenantId) {
    Integer value =
        jdbc
            .query(
                "SELECT min_policy_reviewers FROM bre_ctl.tenants WHERE tenant_id = ?",
                (rs, rowNum) -> rs.getInt(1),
                tenantId)
            .stream()
            .findFirst()
            .orElse(1);
    return value;
  }

  /**
   * Marks a version APPROVED against its signed bundle.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version
   * @param bundleHash the signed bundle
   */
  public void approve(String tenantId, String policyCode, int version, String bundleHash) {
    jdbc.update(
        """
        UPDATE bre_ctl.policy_versions
           SET status = 'APPROVED', approved_at = now(), bundle_hash = ?,
               row_version = row_version + 1, updated_at = now()
         WHERE tenant_id = ? AND policy_code = ? AND version = ? AND status = 'IN_REVIEW'
        """,
        bundleHash,
        tenantId,
        policyCode,
        version);
  }

  /**
   * Archives every earlier APPROVED version of the same policy.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version that supersedes them
   * @return how many were archived
   */
  public int archiveEarlierApproved(String tenantId, String policyCode, int version) {
    return jdbc.update(
        """
        UPDATE bre_ctl.policy_versions
           SET status = 'ARCHIVED', row_version = row_version + 1, updated_at = now()
         WHERE tenant_id = ? AND policy_code = ? AND version < ? AND status = 'APPROVED'
        """,
        tenantId,
        policyCode,
        version);
  }

  /**
   * Discards a DRAFT.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version
   */
  public void discard(String tenantId, String policyCode, int version) {
    jdbc.update(
        """
        UPDATE bre_ctl.policy_versions
           SET status = 'DISCARDED', row_version = row_version + 1, updated_at = now()
         WHERE tenant_id = ? AND policy_code = ? AND version = ? AND status = 'DRAFT'
        """,
        tenantId,
        policyCode,
        version);
  }

  /**
   * Records a built bundle.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version
   * @param built the signed bundle
   */
  public void insertBundle(
      String tenantId, String policyCode, int version, BundleBuilder.Built built) {
    jdbc.update(
        """
        INSERT INTO bre_ctl.bundles (
            tenant_id, bundle_hash, policy_code, policy_version, origin, object_key,
            size_bytes, ir_version, engine_min_version, signatures)
        VALUES (?, ?, ?, ?, 'BUILT', ?, ?, ?, ?, ?::jsonb)
        ON CONFLICT (tenant_id, bundle_hash) DO NOTHING
        """,
        tenantId,
        built.bundleHash(),
        policyCode,
        version,
        "bundles/" + tenantId + "/" + built.bundleHash().replace(':', '-'),
        built.sizeBytes(),
        built.irVersion(),
        built.engineMinVersion(),
        built.signaturesJson());
  }

  // --- test cases -------------------------------------------------------------------------

  /**
   * A version's test cases.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version
   * @return the cases
   */
  public List<CompileService.TestCase> testCases(String tenantId, String policyCode, int version) {
    return jdbc.query(
        """
        SELECT test_name, as_of, facts::text AS facts, expect::text AS expect
          FROM bre_ctl.test_cases
         WHERE tenant_id = ? AND policy_code = ? AND policy_version = ?
         ORDER BY test_name
        """,
        (rs, rowNum) ->
            new CompileService.TestCase(
                rs.getString("test_name"),
                rs.getDate("as_of").toLocalDate(),
                readJson(rs.getString("facts")),
                readJson(rs.getString("expect"))),
        tenantId,
        policyCode,
        version);
  }

  /**
   * Creates or replaces a test case.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version
   * @param name the case name
   * @param asOf the business date
   * @param factsJson the facts — synthetic only (§9.7)
   * @param expectJson the expectations
   */
  public void upsertTestCase(
      String tenantId,
      String policyCode,
      int version,
      String name,
      java.time.LocalDate asOf,
      String factsJson,
      String expectJson) {
    jdbc.update(
        """
        INSERT INTO bre_ctl.test_cases (
            tenant_id, policy_code, policy_version, test_name, as_of, facts, expect)
        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
        ON CONFLICT (tenant_id, policy_code, policy_version, test_name)
        DO UPDATE SET as_of = excluded.as_of, facts = excluded.facts, expect = excluded.expect
        """,
        tenantId,
        policyCode,
        version,
        name,
        java.sql.Date.valueOf(asOf),
        factsJson,
        expectJson);
  }

  /**
   * Deletes a test case.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param version the version
   * @param name the case name
   * @return true when one was deleted
   */
  public boolean deleteTestCase(String tenantId, String policyCode, int version, String name) {
    return jdbc.update(
            """
            DELETE FROM bre_ctl.test_cases
             WHERE tenant_id = ? AND policy_code = ? AND policy_version = ? AND test_name = ?
            """,
            tenantId,
            policyCode,
            version,
            name)
        > 0;
  }

  // ---------------------------------------------------------------------------------------------

  private static String writeJson(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("a compile report could not be serialised", e);
    }
  }

  private static JsonNode readJson(String json) {
    try {
      return JSON.readTree(json == null || json.isBlank() ? "{}" : json);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("a stored JSON column is not valid JSON", e);
    }
  }
}
