package dev.bre.registry.api;

import dev.bre.registry.audit.AuditQuery;
import dev.bre.registry.auth.Principal;
import dev.bre.registry.auth.Role;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading the audit trail (FR-AUD-005, M3-10).
 *
 * <p><b>Read-only, and there is no write endpoint anywhere.</b> Entries are written by the services
 * that make the changes, and sealed by a database trigger that computes the hash chain; the table
 * also carries triggers forbidding UPDATE, DELETE and TRUNCATE. An HTTP route that could append an
 * entry would let a caller write history, which is the one thing an audit log must not permit.
 *
 * <p><b>Verification is its own endpoint, and it recomputes.</b> {@code /verify} walks the chain
 * and recomputes every entry hash from the previous one — it does not read a stored "valid" flag,
 * because a flag is exactly what somebody tampering would set. The answer is useful precisely
 * because nobody can ask the database to lie about it cheaply.
 *
 * <p><b>AUDIT_READ, not POLICY_READ.</b> The audit trail names who did what and when, across
 * policies, deployments and user administration. An author with POLICY_READ can see policies; the
 * record of their colleagues' actions is a separate grant, held by AUDITOR and the two
 * administrator roles.
 */
@RestController
@RequestMapping("/v1/audit")
public class AuditController {

  private final AuditQuery audit;

  /**
   * Creates the controller.
   *
   * @param audit the audit queries
   */
  public AuditController(AuditQuery audit) {
    this.audit = audit;
  }

  /**
   * The audit trail, newest first.
   *
   * @param request the request
   * @param action an action prefix to filter on, e.g. {@code POLICY_VERSION} or {@code
   *     DEPLOYMENT.APPROVED}
   * @param subjectKind the subject kind, e.g. {@code policy_version}
   * @param subjectKey the subject key, e.g. {@code MFI_JLG_V1/3}
   * @param actor who acted
   * @param from the earliest entry to return
   * @param to the latest
   * @param limit how many, capped
   * @return the entries
   */
  @GetMapping
  public List<AuditQuery.Entry> list(
      HttpServletRequest request,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String subjectKind,
      @RequestParam(required = false) String subjectKey,
      @RequestParam(required = false) String actor,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to,
      @RequestParam(defaultValue = "100") int limit) {
    Principal caller = Authorisation.require(request, Role.Capability.AUDIT_READ);
    return audit.list(
        caller.tenantId(),
        new AuditQuery.Filter(action, subjectKind, subjectKey, actor, from, to, limit));
  }

  /**
   * Verifies the hash chain.
   *
   * @param request the request
   * @return what the walk established
   */
  @GetMapping("/verify")
  public AuditQuery.Verification verify(HttpServletRequest request) {
    Principal caller = Authorisation.require(request, Role.Capability.AUDIT_READ);
    return audit.verify(caller.tenantId());
  }
}
