package dev.bre.decision.log;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A decision log that keeps records in memory.
 *
 * <p><b>Local development only.</b> It satisfies the {@link DecisionLog} contract's shape but not
 * its promise: nothing here is durable, so RPO 0 does not hold and a restart loses every decision.
 * {@link #describe()} says so out loud, and the service logs it at startup, so this cannot be
 * mistaken for the real thing in a deployed environment.
 *
 * <p><b>Not a {@code @Component}.</b> It was, and that made two {@code DecisionLog} beans — this
 * one and the factory in {@link DecisionLogConfiguration} — which Spring resolved only because the
 * factory method's name happens to match every injection point's parameter name. Any injection
 * point that named the parameter something else would have failed at startup with
 * NoUniqueBeanDefinitionException, and the mode switch would have been bypassed silently in the
 * meantime. The factory is now the only thing that constructs a decision log, which is also where
 * the mode decision belongs.
 *
 * <p>ponytail: this exists so the walking skeleton runs end to end on a laptop with no database —
 * the dev host has no Docker, only a psql client. The compact-mode PostgreSQL outbox is the next
 * step, tested against real PostgreSQL binaries through {@code io.zonky.test:embedded-postgres}
 * rather than Testcontainers.
 */
public class InMemoryDecisionLog implements DecisionLog {

  private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
      new com.fasterxml.jackson.databind.ObjectMapper();

  private final Map<String, DecisionRecord> byDecisionId = new ConcurrentHashMap<>();
  private final Map<String, DecisionRecord> byRequestId = new ConcurrentHashMap<>();

  @Override
  public void append(DecisionRecord record) {
    byDecisionId.put(key(record.tenantId(), record.decisionId()), record);
    byRequestId.put(key(record.tenantId(), record.requestId()), record);
  }

  @Override
  public Optional<DecisionRecord> find(String tenantId, String decisionId) {
    return Optional.ofNullable(byDecisionId.get(key(tenantId, decisionId)));
  }

  @Override
  public Optional<DecisionRecord> findByRequestId(String tenantId, String requestId) {
    return Optional.ofNullable(byRequestId.get(key(tenantId, requestId)));
  }

  /**
   * Returns the snapshot as submitted.
   *
   * <p>No sealing: this log keeps whole records in heap, so there is nothing an envelope would
   * protect them from — a process that can read the ciphertext can read the key beside it. The
   * sealed path is the PostgreSQL one, where the bytes outlive the process. Saying this plainly
   * matters because the shape of the answer is identical, and someone reading only the call site
   * could believe local development exercises the §9.2 path. It does not.
   */
  @Override
  public Optional<StoredFacts> facts(String tenantId, String decisionId) {
    return find(tenantId, decisionId)
        .map(DecisionRecord::factsJson)
        .filter(json -> json != null && !json.isBlank())
        .map(
            json -> {
              try {
                return new StoredFacts(MAPPER.readTree(json), java.util.List.of(), true);
              } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("a stored fact snapshot is not JSON");
              }
            });
  }

  @Override
  public String describe() {
    return "in-memory (NOT DURABLE - local development only; decisions are lost on restart)";
  }

  /** Tenant-scoped key. A decision is never readable across tenants (D1). */
  private static String key(String tenantId, String id) {
    return tenantId + "/" + id;
  }
}
