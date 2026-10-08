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

  /** Returns a short description of the mode, for the startup log. */
  String describe();
}
