package dev.bre.registry.auth;

import dev.bre.common.TenantContext;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Sets {@code bre.tenant_id} on every connection handed out, so row-level security can do its job.
 *
 * <p>§5.2's policies compare {@code tenant_id} against {@code current_setting('bre.tenant_id',
 * true)}. Without something doing this, the application role sees <b>no rows at all</b> — and the
 * reason that is not immediately obvious is that it only happens under the restricted role: a
 * superuser bypasses row-level security entirely, so a test that migrates and queries as a
 * superuser passes while the deployed service returns empty results. This project had precisely
 * that shape of false green twice before, so the lifecycle test connects as {@code bre_ctl_app}.
 *
 * <p><b>Why a DataSource wrapper and not a call at the top of each service method.</b> There are
 * three ways to set a session variable per request, and the other two leak:
 *
 * <ul>
 *   <li>{@code SET LOCAL} inside each transaction works, but needs a call in every transactional
 *       method, and the one that is forgotten fails closed <em>silently</em> — an empty list, not
 *       an error. A missing call is invisible in review.
 *   <li>{@code SET} at the start of a request leaves the value on a <b>pooled</b> connection after
 *       the request ends, so the next request to borrow it inherits the previous tenant. That is
 *       the worst possible failure: cross-tenant data, intermittently.
 *   <li>Here: set on acquire, reset on close, with the reset in the proxy's {@code close} so it
 *       cannot be skipped by an early return or an exception.
 * </ul>
 *
 * <p><b>Why a proxy rather than a subclass of Connection.</b> A JDBC {@code Connection} has upwards
 * of fifty methods and the driver's implementation changes between versions; a dynamic proxy
 * intercepts exactly one — {@code close} — and forwards everything else untouched, so a driver
 * upgrade cannot leave a method unimplemented.
 */
public class TenantScopedDataSource extends DelegatingDataSource {

  private static final Logger log = LoggerFactory.getLogger(TenantScopedDataSource.class);

  /**
   * Wraps a data source.
   *
   * @param delegate the real data source
   */
  public TenantScopedDataSource(DataSource delegate) {
    super(delegate);
  }

  @Override
  public Connection getConnection() throws SQLException {
    return scoped(super.getConnection());
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return scoped(super.getConnection(username, password));
  }

  private Connection scoped(Connection connection) throws SQLException {
    java.util.Optional<String> tenant = TenantContext.current();
    if (tenant.isEmpty()) {
      // No tenant in scope. This is normal and is NOT an error: the authentication path runs
      // before any tenant is established, and it reaches the database only through the narrow
      // SECURITY DEFINER functions V11 defines. Any ordinary tenant-owned query on such a
      // connection correctly returns nothing.
      return connection;
    }

    String tenantId = tenant.get();
    // set_config as a parameterised call, not string concatenation into SET. A tenant id is
    // checked against ^[a-z][a-z0-9_]{2,31}$ at every boundary, so injection is not reachable
    // today — but `SET bre.tenant_id = '<value>'` has no parameter form, and a setting assembled
    // by concatenation is one validation change away from being an injection point.
    try (var prepared =
        connection.prepareStatement("SELECT set_config('bre.tenant_id', ?, false)")) {
      prepared.setString(1, tenantId);
      prepared.execute();
    }
    return proxy(connection, tenantId);
  }

  private static Connection proxy(Connection connection, String tenantId) {
    InvocationHandler handler =
        (unusedProxy, method, args) -> {
          if ("close".equals(method.getName())) {
            reset(connection, tenantId);
            connection.close();
            return null;
          }
          try {
            return method.invoke(connection, args);
          } catch (InvocationTargetException e) {
            throw e.getCause();
          }
        };
    return (Connection)
        Proxy.newProxyInstance(
            TenantScopedDataSource.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            handler);
  }

  private static void reset(Connection connection, String tenantId) {
    // Best effort, and deliberately not fatal: a connection being returned to the pool while
    // broken must not turn into an exception from close(). The pool discards a connection it
    // cannot validate, and a discarded connection carries no setting anywhere.
    try (Statement statement = connection.createStatement()) {
      statement.execute("RESET bre.tenant_id");
    } catch (SQLException e) {
      log.warn(
          "could not reset bre.tenant_id on a connection being returned to the pool (tenant was"
              + " {}). The pool will validate or discard it; a stale setting on a reused"
              + " connection is the one failure this reset exists to prevent, so this is worth"
              + " investigating if it recurs.",
          tenantId,
          e);
    }
  }
}
