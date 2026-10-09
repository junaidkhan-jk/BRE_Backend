package dev.bre.crypto;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tenant Data Keys: one AES-256 key per tenant per hour (LLD §9.2, §9.3).
 *
 * <p>{@code tdk/<tenantId>/<yyyyMMddHH>/<n>}. Everything on the decision path seals under a TDK —
 * the results envelope, the whole facts snapshot — so this is the component the hot path actually
 * touches, and the shape of it is dictated by that.
 *
 * <p><b>Why hourly, rather than per tenant or per decision.</b> Per tenant is one key for years of
 * decisions: a single compromise exposes everything, and there is no way to limit the damage after
 * the fact. Per decision is a KMS call on every request, which puts a network round trip and
 * someone else's availability inside a 20 ms latency budget. Hourly bounds the blast radius of one
 * leaked key to one tenant's one hour while costing one KMS unwrap per tenant per hour.
 *
 * <p><b>Why the wrapped key is cached and the plaintext key is not re-fetched.</b> A decision pod
 * holds unwrapped TDKs in memory for the hours it is actively writing, which is the point — but it
 * holds them for <em>bounded</em> hours. {@link #evictOlderThan} exists so a long-lived pod does
 * not accumulate every key it has ever used; a pod up for a month with no eviction would hold 720
 * keys for one tenant, and a heap dump would then be a month-wide compromise rather than an
 * hour-wide one.
 *
 * <p><b>Reading an old decision is a different path on purpose.</b> {@link #forKid} takes the kid
 * out of the envelope and unwraps whatever that names, including a key from an hour long past and a
 * KEK version long rotated. {@link #current} only ever returns the key for <em>now</em>. Writing
 * and reading are not symmetric, and a single "get the key" method would let a write silently use
 * last hour's key after a clock adjustment.
 */
public final class TenantDataKeyProvider {

  private static final DateTimeFormatter HOUR =
      DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);

  /** The version suffix on a TDK kid. Rotation within an hour is not something we do. */
  private static final int VERSION = 1;

  private final KmsPort kms;
  private final WrappedKeyStore store;
  private final Map<String, CachedKey> cache = new ConcurrentHashMap<>();

  /**
   * A TDK and the KEK it is wrapped under.
   *
   * @param kid the TDK identifier
   * @param key the raw key
   * @param kekKid the KEK that wraps it, needed to unwrap it again later
   */
  // Error Prone's ArrayRecordComponent, suppressed deliberately. Its reasoning is that an array
  // component gives a record identity equals and hashCode and a useless toString. All three are
  // what we want here: raw key material must never be compared by value — a value comparison on a
  // secret is a timing oracle — and must never appear in a toString, which is how a key reaches a
  // log. A defensive copy would also be wrong: these are passed straight to a Cipher and then
  // zeroed, and copying them multiplies the number of places a key exists in the heap.
  @SuppressWarnings("ArrayRecordComponent")
  public record DataKey(String kid, byte[] key, String kekKid) {}

  @SuppressWarnings("ArrayRecordComponent") // See DataKey above.
  private record CachedKey(byte[] key, String kekKid) {}

  /**
   * Where wrapped TDKs live between pods.
   *
   * <p>A port, because a TDK must survive the pod that created it: two pods serving the same tenant
   * in the same hour have to seal under the <em>same</em> key, or an archive reader needs two keys
   * where it expects one. In compact mode this is a table on the same PostgreSQL the decisions go
   * to; the dev adapter keeps it in memory.
   */
  public interface WrappedKeyStore {

    /**
     * Returns a wrapped key, or stores and returns the one offered.
     *
     * <p>Atomic insert-or-get, deliberately one operation: two pods racing for the same hour must
     * end up with the same key, and a read-then-write would give them different ones. §9.2 spells
     * the same requirement for DPKs as {@code INSERT … ON CONFLICT DO NOTHING}.
     *
     * @param kid the key identifier
     * @param offered the wrapped key to store if none exists
     * @param kekKid the KEK the offered key is wrapped under
     * @return the wrapped key that is now in force, and its KEK
     */
    Wrapped putIfAbsent(String kid, byte[] offered, String kekKid);

    /**
     * Returns a wrapped key by identifier.
     *
     * @param kid the key identifier
     * @return the wrapped key, or null when this store has never held it
     */
    Wrapped get(String kid);

    /**
     * A wrapped key as stored.
     *
     * @param wrapped the wrapped bytes
     * @param kekKid the KEK that wraps it
     */
    // Error Prone's ArrayRecordComponent, suppressed deliberately. Its reasoning is that an array
    // component gives a record identity equals and hashCode and a useless toString. All three are
    // what we want here: raw key material must never be compared by value — a value comparison on a
    // secret is a timing oracle — and must never appear in a toString, which is how a key reaches a
    // log. A defensive copy would also be wrong: these are passed straight to a Cipher and then
    // zeroed, and copying them multiplies the number of places a key exists in the heap.
    @SuppressWarnings("ArrayRecordComponent")
    record Wrapped(byte[] wrapped, String kekKid) {}
  }

  /**
   * Creates a provider.
   *
   * @param kms the key-management port
   * @param store where wrapped keys live
   */
  public TenantDataKeyProvider(KmsPort kms, WrappedKeyStore store) {
    this.kms = kms;
    this.store = store;
  }

  /**
   * The TDK to seal with now.
   *
   * @param tenantId the tenant
   * @param at the instant whose hour the key belongs to — passed in, never read from a clock here,
   *     so a decision and its envelope agree about which hour they happened in
   * @return the key
   * @throws CryptoException if the tenant has no KEK or the KMS refuses
   */
  public DataKey current(String tenantId, Instant at) {
    String kid = kidFor(tenantId, at);
    CachedKey cached = cache.get(kid);
    if (cached != null) {
      return new DataKey(kid, cached.key(), cached.kekKid());
    }

    // Generated locally, wrapped immediately, and the plaintext kept only in this cache. The
    // generated key is offered to the store rather than assumed to win: another pod may already
    // have created this hour's key, and both must use the same one.
    byte[] fresh = Envelope.newDataKey();
    String kekKid = kms.currentKek(tenantId);
    byte[] wrapped;
    try {
      wrapped = kms.wrap(kekKid, fresh);
    } finally {
      Arrays.fill(fresh, (byte) 0);
    }

    WrappedKeyStore.Wrapped inForce = store.putIfAbsent(kid, wrapped, kekKid);
    byte[] key = kms.unwrap(inForce.kekKid(), inForce.wrapped());
    cache.put(kid, new CachedKey(key, inForce.kekKid()));
    return new DataKey(kid, key, inForce.kekKid());
  }

  /**
   * The TDK an existing envelope names.
   *
   * @param kid the identifier read from the envelope
   * @return the key
   * @throws CryptoException {@code UNKNOWN_KEY} when no store holds it — which for a TDK means the
   *     data is unreadable, and is <b>not</b> the same as crypto-shredding (§9.5), where the loss
   *     is deliberate and only ever applies to a DPK
   */
  public byte[] forKid(String kid) {
    CachedKey cached = cache.get(kid);
    if (cached != null) {
      return cached.key();
    }
    WrappedKeyStore.Wrapped stored = store.get(kid);
    if (stored == null) {
      throw new CryptoException(
          CryptoException.Reason.UNKNOWN_KEY,
          kid
              + " is not in this installation's key store, so an envelope sealed under it cannot"
              + " be opened here");
    }
    byte[] key = kms.unwrap(stored.kekKid(), stored.wrapped());
    cache.put(kid, new CachedKey(key, stored.kekKid()));
    return key;
  }

  /**
   * Drops cached plaintext keys for hours before a cut-off.
   *
   * <p>Call it periodically. The keys stay in the store and stay unwrappable; only this process's
   * copy of the plaintext goes.
   *
   * @param tenantId the tenant
   * @param before keys for hours strictly before this instant's hour are dropped
   * @return how many were dropped
   */
  public int evictOlderThan(String tenantId, Instant before) {
    String cutoff = kidFor(tenantId, before);
    String prefix = "tdk/" + tenantId + "/";
    int dropped = 0;
    for (String kid : cache.keySet().stream().filter(k -> k.startsWith(prefix)).toList()) {
      if (kid.compareTo(cutoff) < 0) {
        CachedKey removed = cache.remove(kid);
        if (removed != null) {
          Arrays.fill(removed.key(), (byte) 0);
          dropped++;
        }
      }
    }
    return dropped;
  }

  /**
   * The kid for a tenant and an hour (§9.3).
   *
   * @param tenantId the tenant
   * @param at the instant
   * @return {@code tdk/<tenantId>/<yyyyMMddHH>/<n>}, in UTC
   */
  public static String kidFor(String tenantId, Instant at) {
    // UTC, not the tenant zone. A kid is an identifier, not a business date: making it depend on a
    // tenant's zone would mean two tenants in different zones disagree about which hour a shared
    // instant is in, and an operator reading two kids side by side could not compare them.
    return "tdk/" + tenantId + "/" + HOUR.format(at) + "/" + VERSION;
  }

  /** How many plaintext keys this process is holding. For tests and for a gauge. */
  public int cachedKeys() {
    return cache.size();
  }
}
