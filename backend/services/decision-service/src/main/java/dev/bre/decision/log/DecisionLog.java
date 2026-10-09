package dev.bre.decision.log;

import java.util.Optional;

/**
 * The durable write the response waits on (D6).
 *
 * <p>One port, two modes. Kafka-first ({@code acks=all}, RF 3, {@code min.insync.replicas=2}) gives
 * RPO 0 for every acknowledged decision at scale; compact mode writes to PostgreSQL first with a
 * transactional outbox, for small on-prem installs. Both sit behind this interface, so the service
 * layer never knows which one it is talking to.
 *
 * <p>The write happens <b>before</b> the response returns. A decision the caller has already acted
 * on but the lender cannot produce on audit is worse than a slow decision.
 */
public interface DecisionLog {

  /**
   * Appends a record durably. Returns only once the write is safe.
   *
   * @param record the record to append
   */
  void append(DecisionRecord record);

  /**
   * Finds a decision by id.
   *
   * @param tenantId the tenant, which scopes the lookup — a decision is never readable across
   *     tenants (D1)
   * @param decisionId the decision identifier
   * @return the record, or empty when there is no such decision for that tenant
   */
  Optional<DecisionRecord> find(String tenantId, String decisionId);

  /**
   * Finds an existing decision for a caller's idempotency key.
   *
   * @param tenantId the tenant
   * @param requestId the caller's idempotency key
   * @return the record, if this request was already decided
   */
  Optional<DecisionRecord> findByRequestId(String tenantId, String requestId);

  /**
   * Returns the fact snapshot a decision was taken on, for replay (D7).
   *
   * <p><b>Separate from {@link #find} on purpose.</b> An ordinary lookup must not hand back
   * personal data it does not need — §9.6's whole posture is that facts travel only where they are
   * required — so the two reads are different calls with different callers, and this one exists
   * only for the replay path.
   *
   * @param tenantId the tenant
   * @param decisionId the decision
   * @return the facts, or empty when they were never stored ({@code store-facts} off, the default)
   *     or this log does not keep them
   */
  default Optional<StoredFacts> facts(String tenantId, String decisionId) {
    return Optional.empty();
  }

  /**
   * A stored fact snapshot, as a replay needs it.
   *
   * @param facts the snapshot, with every data principal whose key still exists rejoined
   * @param shreddedParties the parties whose identifying fields are gone — an erased principal
   *     (§9.5), expected and not an error
   * @param integrityVerified whether the stored HMAC matched (§9.2); false with no shredded party
   *     means the stored bytes are not the bytes the decision was taken on
   */
  record StoredFacts(
      com.fasterxml.jackson.databind.JsonNode facts,
      java.util.List<String> shreddedParties,
      boolean integrityVerified) {

    /** Creates a stored snapshot, copying the list. */
    public StoredFacts {
      shreddedParties = java.util.List.copyOf(shreddedParties);
    }
  }

  /** Returns a short description of the mode, for the startup log. */
  String describe();
}
