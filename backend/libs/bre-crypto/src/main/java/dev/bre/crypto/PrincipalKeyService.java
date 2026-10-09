package dev.bre.crypto;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Data Principal Keys: one key per person, and deleting it is how erasure works (LLD §9.2, §9.5).
 *
 * <p>Every identifying field of an applicant is sealed under that applicant's own DPK. Destroying
 * the key makes every copy of their identifying data undecryptable at once — in the hot store, in
 * the WORM archive, in backups older than the key's own backup window — without rewriting a single
 * stored byte. That is the whole mechanism behind §9.5, and it is the only way erasure can work
 * against an append-only archive: you cannot delete a row from WORM storage, and you cannot find
 * every copy of a person's data by searching for it.
 *
 * <p><b>A DPK is never rotated.</b> Every other key here rotates; this one must not. Rotation means
 * re-encrypting under a new key, and re-encrypting means reading the old plaintext and writing a
 * new object — into immutable archive storage, for data whose entire purpose is to become
 * irrecoverable on request. §9.3 says it plainly: "never rotated; deleted to shred".
 *
 * <p><b>A missing DPK is a normal, expected answer.</b> {@link #forPrincipal} returns an empty
 * optional rather than throwing, and callers render a shredded marker and report a replay as not
 * matched with reason SHREDDED — which §9.5 says explicitly raises no alert. A system that treated
 * a shredded principal as an error would page somebody every time it honoured an erasure request,
 * and the on-call response would eventually be to stop honouring them.
 */
public final class PrincipalKeyService {

  private final KmsPort kms;
  private final DpkStore store;
  private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

  /**
   * Where DPKs live, wrapped under the tenant's PKWK.
   *
   * <p>§9.2 puts these on a <b>key shard</b>, separate from the decision store, and §9.5 gives
   * their deletion its own database role ({@code bre_dec_erasure}). Both matter: the account that
   * writes decisions must not be able to destroy keys, and the account that destroys keys must not
   * be able to read decisions.
   */
  public interface DpkStore {

    /**
     * Returns the wrapped DPK for a principal, creating it from the offered key if absent.
     *
     * <p>§9.2 specifies {@code INSERT … ON CONFLICT DO NOTHING}: two decisions for the same person
     * arriving at once must end up under one key, not two.
     *
     * @param tenantId the tenant
     * @param principalRef the data principal
     * @param offeredWrapped a freshly generated, wrapped key to use if none exists
     * @param pkwkKid the PKWK the offered key is wrapped under
     * @return the key now in force
     */
    Dpk putIfAbsent(String tenantId, String principalRef, byte[] offeredWrapped, String pkwkKid);

    /**
     * Returns an existing wrapped DPK.
     *
     * @param tenantId the tenant
     * @param principalRef the data principal
     * @return the key, or empty when there is none — never created, and erased, are the same answer
     *     here: both mean no identifying data for this person can be read
     */
    Optional<Dpk> find(String tenantId, String principalRef);

    /**
     * Destroys a principal's key. This is the erasure (§9.5).
     *
     * @param tenantId the tenant
     * @param principalRef the data principal
     * @return true when a key was deleted, false when there was none
     */
    boolean delete(String tenantId, String principalRef);

    /**
     * A wrapped DPK.
     *
     * @param keyId the key's own identifier, recorded in every envelope sealed under it
     * @param wrapped the wrapped key
     * @param pkwkKid the PKWK that wraps it
     */
    // Error Prone's ArrayRecordComponent, suppressed deliberately. Its reasoning is that an array
    // component gives a record identity equals and hashCode and a useless toString. All three are
    // what we want here: raw key material must never be compared by value — a value comparison on a
    // secret is a timing oracle — and must never appear in a toString, which is how a key reaches a
    // log. A defensive copy would also be wrong: these are passed straight to a Cipher and then
    // zeroed, and copying them multiplies the number of places a key exists in the heap.
    @SuppressWarnings("ArrayRecordComponent")
    record Dpk(String keyId, byte[] wrapped, String pkwkKid) {}
  }

  /**
   * Creates the service.
   *
   * @param kms the key-management port
   * @param store the key shard
   */
  public PrincipalKeyService(KmsPort kms, DpkStore store) {
    this.kms = kms;
    this.store = store;
  }

  /**
   * The DPK to seal a principal's identifying fields under, creating it on first use.
   *
   * @param tenantId the tenant
   * @param principalRef the data principal — {@code principalRef}, falling back to {@code partyRef}
   *     (§9.2); the caller decides which, because only the caller has the facts
   * @return the key and its identifier
   */
  public Key forWriting(String tenantId, String principalRef) {
    String cacheKey = tenantId + " " + principalRef;
    byte[] cached = cache.get(cacheKey);
    if (cached != null) {
      return new Key(keyIdOf(tenantId, principalRef), cached);
    }

    byte[] fresh = Envelope.newDataKey();
    String pkwkKid = kms.currentKek(tenantId);
    byte[] wrapped;
    try {
      wrapped = kms.wrap(pkwkKid, fresh);
    } finally {
      Arrays.fill(fresh, (byte) 0);
    }

    DpkStore.Dpk inForce = store.putIfAbsent(tenantId, principalRef, wrapped, pkwkKid);
    byte[] key = kms.unwrap(inForce.pkwkKid(), inForce.wrapped());
    cache.put(cacheKey, key);
    return new Key(inForce.keyId(), key);
  }

  /**
   * The DPK for reading a principal's identifying fields, if it still exists.
   *
   * @param tenantId the tenant
   * @param principalRef the data principal
   * @return the key, or empty when the principal has been erased (§9.5) — an expected outcome
   */
  public Optional<byte[]> forPrincipal(String tenantId, String principalRef) {
    String cacheKey = tenantId + " " + principalRef;
    byte[] cached = cache.get(cacheKey);
    if (cached != null) {
      return Optional.of(cached);
    }
    return store
        .find(tenantId, principalRef)
        .map(
            dpk -> {
              byte[] key = kms.unwrap(dpk.pkwkKid(), dpk.wrapped());
              cache.put(cacheKey, key);
              return key;
            });
  }

  /**
   * Destroys a principal's key, making their identifying data unreadable everywhere (§9.5).
   *
   * <p>The in-process cache is cleared too. Without that, a pod that had just sealed a decision for
   * this person would keep serving their unmasked data from memory until it restarted — an erasure
   * that had been performed and was not yet in effect, which is the worst of both states.
   *
   * @param tenantId the tenant
   * @param principalRef the data principal
   * @return true when a key was destroyed
   */
  public boolean shred(String tenantId, String principalRef) {
    byte[] cached = cache.remove(tenantId + " " + principalRef);
    if (cached != null) {
      Arrays.fill(cached, (byte) 0);
    }
    return store.delete(tenantId, principalRef);
  }

  /**
   * The identifier recorded in envelopes sealed under a principal's key.
   *
   * <p>Derived rather than random, so an archive reader holding only the envelope can find the row
   * — and so that a shredded key leaves a kid that still says who it belonged to, which is what
   * lets an explanation say the value was shredded rather than simply failing.
   *
   * @param tenantId the tenant
   * @param principalRef the data principal
   * @return {@code dpk/<tenantId>/<principalRef>}
   */
  public static String keyIdOf(String tenantId, String principalRef) {
    return "dpk/" + tenantId + "/" + principalRef;
  }

  /**
   * A DPK.
   *
   * @param kid its identifier
   * @param key the raw key
   */
  // Error Prone's ArrayRecordComponent, suppressed deliberately. Its reasoning is that an array
  // component gives a record identity equals and hashCode and a useless toString. All three are
  // what we want here: raw key material must never be compared by value — a value comparison on a
  // secret is a timing oracle — and must never appear in a toString, which is how a key reaches a
  // log. A defensive copy would also be wrong: these are passed straight to a Cipher and then
  // zeroed, and copying them multiplies the number of places a key exists in the heap.
  @SuppressWarnings("ArrayRecordComponent")
  public record Key(String kid, byte[] key) {}
}
