package dev.bre.common;

import java.util.Optional;

/**
 * The tenant the current unit of work belongs to (D1, LLD §5.1).
 *
 * <p><b>Fail-closed by construction, and that is the whole design.</b> There is no "default
 * tenant", no fallback and no system-wide context. {@link #require()} throws when nothing is set,
 * because the alternative — returning some default — is the bug that serves one lender's data to
 * another. Every tenant-owned table has {@code tenant_id} as the leading column of its primary key
 * with row-level security forced, and a session that has not set {@code bre.tenant_id} sees no rows
 * at all (§5.1). This class is the application-side half of that same posture: an unset context is
 * an error, not an empty result.
 *
 * <p><b>Scoped, not assigned.</b> The only way to set a tenant is {@link #runIn}, which restores
 * whatever was there before in a {@code finally}. A plain setter would leak a tenant onto a pooled
 * thread and give the next request someone else's data — the single most likely way a multi-tenant
 * platform loses its isolation, and it leaks silently.
 *
 * <p><b>A ScopedValue, not a ThreadLocal.</b> The services run on virtual threads (ADR-012), and a
 * ScopedValue is inherited by a structured-concurrency fork while a ThreadLocal is not. Fanning out
 * to fetch two bureau reports in parallel must not drop the tenant, and with a ThreadLocal it
 * would. A ScopedValue is also immutable within its scope, so nothing can reassign it mid-request.
 *
 * <p><b>Whose assertion is this?</b> The context holds the tenant the request has been
 * <em>authenticated</em> as, never a value a caller supplied. An {@code X-Tenant-Id} header is a
 * claim, not an identity; it may only reach this class after the authentication layer has verified
 * the credential actually grants that tenant. Until M3-09 exists there is no such layer, which is
 * why the decision service must not be exposed to an untrusted network.
 */
public final class TenantContext {

  private static final ScopedValue<String> CURRENT = ScopedValue.newInstance();

  private TenantContext() {}

  /**
   * Runs an action with the tenant bound, restoring the previous binding afterwards.
   *
   * @param tenantId the authenticated tenant
   * @param action the work to run
   * @param <T> what the action returns
   * @return the action's result
   */
  public static <T> T runIn(String tenantId, java.util.concurrent.Callable<T> action) {
    requireValid(tenantId);
    try {
      return ScopedValue.where(CURRENT, tenantId).call(action::call);
    } catch (RuntimeException | Error direct) {
      throw direct;
    } catch (Exception checked) {
      throw new IllegalStateException("the action failed inside tenant scope", checked);
    }
  }

  /**
   * Runs an action with the tenant bound.
   *
   * @param tenantId the authenticated tenant
   * @param action the work to run
   */
  public static void runIn(String tenantId, Runnable action) {
    requireValid(tenantId);
    ScopedValue.where(CURRENT, tenantId).run(action);
  }

  /**
   * The current tenant, or empty when none is bound.
   *
   * <p>Prefer {@link #require()} on any path that touches tenant data. This exists for
   * cross-cutting code — a log appender, a metric label — that must work both inside and outside a
   * request, and that has nothing to leak if the answer is empty.
   *
   * @return the tenant, or empty
   */
  public static Optional<String> current() {
    return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
  }

  /**
   * The current tenant, or an exception.
   *
   * <p>The method to use before reading or writing tenant data. It throws rather than defaulting
   * because a missing tenant is a programming error, and the failure mode of guessing is serving
   * one lender's data to another.
   *
   * @return the tenant
   * @throws IllegalStateException when no tenant is bound
   */
  public static String require() {
    if (!CURRENT.isBound()) {
      throw new IllegalStateException(
          "no tenant is bound; tenant-scoped work must run inside TenantContext.runIn");
    }
    return CURRENT.get();
  }

  /**
   * Whether a tenant is bound.
   *
   * @return true when one is
   */
  public static boolean isBound() {
    return CURRENT.isBound();
  }

  /**
   * Validates a tenant identifier's shape (§2.2 {@code $defs/tenantId}).
   *
   * <p>Checked at the boundary because this value is interpolated into {@code set_config(
   * 'bre.tenant_id', …)} and used as a partition key. Validating the shape here means no caller has
   * to remember to.
   */
  private static void requireValid(String tenantId) {
    if (tenantId == null || tenantId.isBlank()) {
      throw new IllegalArgumentException("a tenant id is required");
    }
    if (!tenantId.matches("^t_[a-z0-9_]{1,30}$")) {
      // The value is echoed because a tenant id is not personal data — it names an institution,
      // and a rejected one is almost always a configuration mistake somebody has to find.
      throw new IllegalArgumentException(
          "'"
              + tenantId
              + "' is not a valid tenant id; expected t_ followed by lower-case"
              + " alphanumerics or underscores");
    }
  }
}
