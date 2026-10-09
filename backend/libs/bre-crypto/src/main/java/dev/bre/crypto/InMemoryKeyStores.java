package dev.bre.crypto;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory key stores, for local work and tests.
 *
 * <p>Both stores implement the one property the real ones must have and that is easy to get wrong:
 * <b>insert-or-get is atomic</b>. §9.2 spells it as {@code INSERT … ON CONFLICT DO NOTHING}, and
 * the reason is concurrency, not tidiness — two pods sealing for the same tenant-hour, or two
 * decisions for the same person arriving together, must end up under <em>one</em> key. A
 * read-then-write would give them two, and the loser's data would be sealed under a key nothing
 * ever stores.
 *
 * <p>Not durable, so a restart loses every key and every decision written before it becomes
 * unreadable. That is the honest local behaviour: the production stores are a table on the decision
 * database (TDKs) and a separate key shard (DPKs, §9.2), with their own database roles.
 */
public final class InMemoryKeyStores {

  private InMemoryKeyStores() {}

  /**
   * Creates a TDK store.
   *
   * @return the store
   */
  public static TenantDataKeyProvider.WrappedKeyStore tenantKeys() {
    Map<String, TenantDataKeyProvider.WrappedKeyStore.Wrapped> rows = new ConcurrentHashMap<>();
    return new TenantDataKeyProvider.WrappedKeyStore() {
      @Override
      public Wrapped putIfAbsent(String kid, byte[] offered, String kekKid) {
        return rows.computeIfAbsent(kid, k -> new Wrapped(offered.clone(), kekKid));
      }

      @Override
      public Wrapped get(String kid) {
        return rows.get(kid);
      }
    };
  }

  /**
   * Creates a DPK store.
   *
   * @return the store
   */
  public static PrincipalKeyService.DpkStore principalKeys() {
    Map<String, PrincipalKeyService.DpkStore.Dpk> rows = new ConcurrentHashMap<>();
    return new PrincipalKeyService.DpkStore() {
      @Override
      public Dpk putIfAbsent(
          String tenantId, String principalRef, byte[] offeredWrapped, String pkwkKid) {
        String keyId = PrincipalKeyService.keyIdOf(tenantId, principalRef);
        return rows.computeIfAbsent(keyId, k -> new Dpk(keyId, offeredWrapped.clone(), pkwkKid));
      }

      @Override
      public Optional<Dpk> find(String tenantId, String principalRef) {
        return Optional.ofNullable(rows.get(PrincipalKeyService.keyIdOf(tenantId, principalRef)));
      }

      @Override
      public boolean delete(String tenantId, String principalRef) {
        return rows.remove(PrincipalKeyService.keyIdOf(tenantId, principalRef)) != null;
      }
    };
  }
}
