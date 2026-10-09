package dev.bre.registry.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Sets {@code bre.tenant_id} on the <b>current transaction</b>, for writes that happen before the
 * tenant is in scope.
 *
 * <p><b>Why this exists alongside {@link TenantScopedDataSource}.</b> The wrapper sets the tenant
 * when a connection is handed out, which covers every ordinary request: the filter binds the scope,
 * then the handler starts a transaction, then a connection is acquired. Two paths invert that order
 * and the wrapper cannot help them:
 *
 * <ul>
 *   <li><b>Login.</b> The transaction opens, the account is looked up, and only then is the tenant
 *       known — so the connection was acquired before there was anything to set. Binding a scope
 *       inside the transaction does nothing, because Spring has already associated the connection
 *       with it and will not acquire another.
 *   <li><b>The audit log.</b> Written from both inside and outside a request, including by the
 *       bootstrap, which has no request at all.
 * </ul>
 *
 * <p>Both write to tables under {@code FORCE ROW LEVEL SECURITY}, and the failure is not subtle:
 * {@code new row violates row-level security policy for table "audit_chain_heads"}.
 *
 * <p><b>{@code is_local = true}</b> — the PostgreSQL equivalent of {@code SET LOCAL}. The setting
 * is reverted when the transaction ends, by the database, so it cannot survive onto a pooled
 * connection and leak one tenant's identity into the next request. That is the one failure mode
 * worth engineering against here, and it is handled by the database rather than by remembering to
 * clean up.
 */
@Component
public class TenantSession {

  private final JdbcTemplate jdbc;

  /**
   * Creates the helper.
   *
   * @param jdbc the control-plane database
   */
  public TenantSession(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Binds a tenant to the current transaction.
   *
   * @param tenantId the tenant
   */
  public void bind(String tenantId) {
    // Parameterised, not concatenated. A tenant id is validated against ^[a-z][a-z0-9_]{2,31}$ at
    // every boundary, so injection is not reachable today — but `SET` takes no parameters, and a
    // setting assembled by string concatenation is one validation change away from being an
    // injection point. set_config is the parameterisable form.
    jdbc.queryForObject("SELECT set_config('bre.tenant_id', ?, true)", String.class, tenantId);
  }
}
