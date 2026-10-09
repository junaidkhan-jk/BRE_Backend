package dev.bre.crypto;

/**
 * The only way this system reaches a key-management service (LLD §1.1, §9.2).
 *
 * <p><b>A port, because the implementation is not ours and must not leak.</b> A deployment uses AWS
 * KMS, or an on-premises HSM, or a lender's own appliance — BD4 is still open and will be different
 * per installation. Everything above this interface works the same way regardless, and the one
 * thing that must never differ is the envelope format: a decision sealed on one installation has to
 * be openable by that installation's own archive reader years later, whoever the KMS vendor was at
 * the time.
 *
 * <p><b>The KEK never leaves the KMS.</b> That is the whole reason this interface is shaped around
 * wrapping rather than around fetching: the only operations are "wrap these bytes" and "unwrap
 * these bytes", so a compromise of a decision pod yields no key material, only the ability to ask
 * the KMS — which is logged, rate-limited and revocable. An interface with {@code getKey(kid)} on
 * it would make a heap dump a key compromise.
 *
 * <p>Data keys (TDK, DPK) are generated locally, used locally, and stored only in wrapped form.
 * That keeps the KMS off the hot path: one unwrap per tenant per hour, not one per decision.
 */
public interface KmsPort {

  /**
   * Wraps a data key under a tenant's key-encryption key.
   *
   * @param kekKid the KEK identifier, {@code kek/<tenantId>/<n>} (§9.3)
   * @param plaintextDataKey the raw data key; the caller must clear it afterwards
   * @return the wrapped key, opaque to everything but the same KMS
   * @throws CryptoException if the KEK is unknown or the KMS refuses
   */
  byte[] wrap(String kekKid, byte[] plaintextDataKey);

  /**
   * Unwraps a data key.
   *
   * @param kekKid the KEK identifier the key was wrapped under
   * @param wrappedDataKey the wrapped key
   * @return the raw data key
   * @throws CryptoException if the KEK is unknown, revoked, or the wrapping does not verify
   */
  byte[] unwrap(String kekKid, byte[] wrappedDataKey);

  /**
   * The KEK a tenant's new data keys should be wrapped under.
   *
   * <p>Separate from {@link #wrap} because rotation is asymmetric: new keys go under the current
   * KEK, while old keys keep being unwrapped under whichever KEK sealed them (§9.3 — a tenant KEK
   * rotates yearly and old versions stay available). Code that asks for "the current one" and code
   * that names a specific one are doing different things, and conflating them is how a rotation
   * makes last year's decisions unreadable.
   *
   * @param tenantId the tenant
   * @return the current KEK identifier for that tenant
   * @throws CryptoException if the tenant has no KEK
   */
  String currentKek(String tenantId);
}
