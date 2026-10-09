package dev.bre.registry.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Appends to the hash-chained audit log (LLD §5.2, §10.4).
 *
 * <p><b>The chaining is done by the database, not here, and that is the point.</b> {@code
 * bre_ctl.tg_audit_chain} is a {@code BEFORE INSERT} trigger that assigns the sequence number,
 * stamps the time from {@code clock_timestamp()}, reads the previous hash under {@code FOR UPDATE}
 * and computes {@code sha256(prev_hash || entry)}. Two further triggers forbid {@code UPDATE},
 * {@code DELETE} and {@code TRUNCATE} on the table.
 *
 * <p>Doing it in application code would be weaker in three specific ways: the sequence and the
 * previous hash would be read outside the row lock, so two concurrent writers could chain off the
 * same head and produce a fork; the timestamp would come from an application clock that can be
 * wrong or adjusted; and an application that computes its own chain can be persuaded not to. A
 * trigger holds for every writer, including {@code psql}.
 *
 * <p><b>Detail never contains fact values</b> (§9.6). The column's own comment says so. What goes
 * in is identifiers, counts and hashes — a bundle hash, an approval count, a submission round — so
 * that an audit record remains useful without becoming a second copy of the applicant's data in a
 * table nobody can delete from.
 */
@Component
public class AuditLog {

  private final JdbcTemplate jdbc;
  private final dev.bre.registry.auth.TenantSession tenantSession;

  /**
   * Creates the log.
   *
   * @param jdbc the control-plane database
   * @param tenantSession binds the tenant to the current transaction, for the audit writes that
   *     happen before a tenant is in scope — a login, and the bootstrap
   */
  public AuditLog(JdbcTemplate jdbc, dev.bre.registry.auth.TenantSession tenantSession) {
    this.jdbc = jdbc;
    this.tenantSession = tenantSession;
  }

  /**
   * Appends an entry by a person.
   *
   * @param tenantId the tenant
   * @param actor the subject acting
   * @param action the action, e.g. {@code POLICY_VERSION.APPROVED}
   * @param subjectKind what it was done to, e.g. {@code policy_version}
   * @param subjectKey which one
   * @param detailJson identifiers, counts and hashes only — never fact values (§9.6)
   */
  public void record(
      String tenantId,
      String actor,
      String action,
      String subjectKind,
      String subjectKey,
      String detailJson) {
    append(tenantId, actor, "USER", action, subjectKind, subjectKey, detailJson);
  }

  /**
   * Appends an entry by the system itself — a scheduled activation, a supersede.
   *
   * @param tenantId the tenant
   * @param action the action
   * @param subjectKind what it was done to
   * @param subjectKey which one
   * @param detailJson identifiers and counts only
   */
  public void recordSystem(
      String tenantId, String action, String subjectKind, String subjectKey, String detailJson) {
    append(tenantId, "system", "SYSTEM", action, subjectKind, subjectKey, detailJson);
  }

  private void append(
      String tenantId,
      String actor,
      String actorType,
      String action,
      String subjectKind,
      String subjectKey,
      String detailJson) {

    // The tenant has to be in scope for the write, not merely passed as a column. `audit_log` and
    // `audit_chain_heads` are both under FORCE ROW LEVEL SECURITY, which applies to the table
    // owner too — and the chain trigger runs as that owner, so an insert with no
    // bre.tenant_id set fails with
    //
    //     ERROR: new row violates row-level security policy for table "audit_chain_heads"
    //
    // Bound on the TRANSACTION rather than by scoping a new connection, because the two callers
    // that need it cannot scope one: a login has already acquired its connection by the time it
    // discovers the tenant, and the bootstrap runs with no request at all. is_local means the
    // database reverts it at commit, so it cannot leak onto a pooled connection.
    tenantSession.bind(tenantId);
    insert(tenantId, actor, actorType, action, subjectKind, subjectKey, detailJson);
  }

  private void insert(
      String tenantId,
      String actor,
      String actorType,
      String action,
      String subjectKind,
      String subjectKey,
      String detailJson) {
    // seq, occurred_at, prev_hash and entry_hash are all assigned by the trigger. They are passed
    // as placeholders only because the columns are NOT NULL and the trigger runs BEFORE INSERT;
    // whatever is sent is overwritten. Sending a real-looking value would invite somebody to
    // believe the application chose it.
    jdbc.update(
        """
        INSERT INTO bre_ctl.audit_log (
            tenant_id, seq, occurred_at, actor, actor_type, action,
            subject_kind, subject_key, detail, prev_hash, entry_hash)
        VALUES (?, 0, now(), ?, ?, ?, ?, ?, ?::jsonb,
                decode(repeat('00', 32), 'hex'), decode(repeat('00', 32), 'hex'))
        """,
        tenantId,
        actor,
        actorType,
        action,
        subjectKind,
        subjectKey,
        detailJson);
  }
}
