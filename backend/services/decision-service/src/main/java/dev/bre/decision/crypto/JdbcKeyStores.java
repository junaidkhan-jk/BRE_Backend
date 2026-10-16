package dev.bre.decision.crypto;

import dev.bre.crypto.PrincipalKeyService;
import dev.bre.crypto.TenantDataKeyProvider;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The key stores, on PostgreSQL (§9.2, M4-04).
 *
 * <p><b>The adapters the ports were written for.</b> {@code WrappedKeyStore} says "in compact mode
 * this is a table on the same PostgreSQL the decisions go to; the dev adapter keeps it in memory",
 * and until now only the dev adapter existed — in production as well as in tests. The crypto was
 * correct and the keys were in a {@code HashMap}, so a restart made every sealed fact row
 * permanently unreadable. Silent, irreversible, and discovered the first time somebody replayed a
 * decision.
 *
 * <p><b>Both writes are insert-or-get in one statement.</b> {@code ON CONFLICT DO NOTHING} followed
 * by a read, never read-then-write: two pods deciding for the same person in the same millisecond
 * must end under one key. With a read-then-write they get two, the second overwrites the first, and
 * every envelope sealed under the first becomes unreadable — the same loss as the HashMap, with a
 * narrower window and so much harder to find.
 *
 * <p>Only wrapped material crosses this boundary. Nothing here can unwrap anything; that needs the
 * KMS, which this database has never seen.
 */
public final class JdbcKeyStores {

  private JdbcKeyStores() {}

  /**
   * The tenant data-key store.
   *
   * @param jdbc the decision-log database
   * @return the store
   */
  public static TenantDataKeyProvider.WrappedKeyStore tenantKeys(JdbcTemplate jdbc) {
    return new TenantDataKeyProvider.WrappedKeyStore() {

      @Override
      public Wrapped putIfAbsent(String kid, byte[] offered, String kekKid) {
        jdbc.update(
            """
            INSERT INTO bre_dec.tenant_data_keys (kid, tenant_id, wrapped, kek_kid)
            VALUES (?, ?, ?, ?) ON CONFLICT (kid) DO NOTHING
            """,
            kid,
            // The tenant is the kid's own prefix, which is how §9.2 shapes it. Parsed rather than
            // passed, because the port has no tenant parameter and inventing one would change a
            // contract the in-memory adapter also implements.
            tenantOf(kid),
            offered,
            kekKid);
        Wrapped stored = get(kid);
        if (stored == null) {
          // The insert either stored the offered key or lost the race to an identical one, so a
          // read finding nothing means the row was deleted between the two statements. Failing is
          // right: sealing under a key this store cannot produce again is the loss this class
          // exists to prevent.
          throw new IllegalStateException(
              "the tenant data key " + kid + " vanished between its insert and its read");
        }
        return stored;
      }

      @Override
      public Wrapped get(String kid) {
        try {
          return jdbc.queryForObject(
              "SELECT wrapped, kek_kid FROM bre_dec.tenant_data_keys WHERE kid = ?",
              (rs, rowNum) -> new Wrapped(rs.getBytes("wrapped"), rs.getString("kek_kid")),
              kid);
        } catch (EmptyResultDataAccessException never) {
          // Null, not an exception: the port's contract is "null when this store has never held
          // it", and a caller reading an archived decision whose key was evicted relies on it.
          return null;
        }
      }
    };
  }

  /**
   * The data-principal key store, whose delete is the erasure (§9.5).
   *
   * @param jdbc the decision-log database
   * @return the store
   */
  public static PrincipalKeyService.DpkStore principalKeys(JdbcTemplate jdbc) {
    return new PrincipalKeyService.DpkStore() {

      @Override
      public Dpk putIfAbsent(
          String tenantId, String principalRef, byte[] offeredWrapped, String pkwkKid) {
        String keyId = PrincipalKeyService.keyIdOf(tenantId, principalRef);
        jdbc.update(
            """
            INSERT INTO bre_dec.principal_data_keys
                (tenant_id, principal_ref, key_id, wrapped, pkwk_kid)
            VALUES (?, ?, ?, ?, ?) ON CONFLICT (tenant_id, principal_ref) DO NOTHING
            """,
            tenantId,
            principalRef,
            keyId,
            offeredWrapped,
            pkwkKid);
        return find(tenantId, principalRef)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "the data principal key vanished between its insert and its read"));
      }

      @Override
      public Optional<Dpk> find(String tenantId, String principalRef) {
        return jdbc
            .query(
                """
                SELECT key_id, wrapped, pkwk_kid FROM bre_dec.principal_data_keys
                 WHERE tenant_id = ? AND principal_ref = ?
                """,
                (rs, rowNum) ->
                    new Dpk(
                        rs.getString("key_id"), rs.getBytes("wrapped"), rs.getString("pkwk_kid")),
                tenantId,
                principalRef)
            .stream()
            .findFirst();
      }

      @Override
      public boolean delete(String tenantId, String principalRef) {
        // A real DELETE. No soft delete, no tombstone, no "erased" flag — a key that still exists
        // has not been destroyed, and an erasure that leaves the key behind is a lie told to a
        // data principal and to a regulator.
        return jdbc.update(
                """
                DELETE FROM bre_dec.principal_data_keys
                 WHERE tenant_id = ? AND principal_ref = ?
                """,
                tenantId,
                principalRef)
            > 0;
      }
    };
  }

  /**
   * The tenant a TDK belongs to, out of its {@code tdk/<tenantId>/<yyyyMMddHH>/<n>} kid.
   *
   * <p>Parsed rather than passed because the port has no tenant parameter, and adding one would
   * change a contract the in-memory adapter also implements. The column it fills is for operators —
   * "how many keys does this tenant hold", "evict this tenant's old keys" — and never for lookup,
   * which goes by the kid alone. A kid that does not match the shape yields the whole string, so a
   * malformed one is visible in the column instead of being silently attributed to the wrong
   * tenant.
   */
  private static String tenantOf(String kid) {
    String[] parts = kid.split("/", -1);
    return parts.length >= 2 && "tdk".equals(parts[0]) ? parts[1] : kid;
  }
}
