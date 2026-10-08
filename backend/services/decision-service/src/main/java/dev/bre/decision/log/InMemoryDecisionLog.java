package dev.bre.decision.log;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * A decision log that keeps records in memory.
 *
 * <p><b>Local development only.</b> It satisfies the {@link DecisionLog} contract's shape but not
 * its promise: nothing here is durable, so RPO 0 does not hold and a restart loses every decision.
 * {@link #describe()} says so out loud, and the service logs it at startup, so this cannot be
 * mistaken for the real thing in a deployed environment.
 *
 * <p>ponytail: this exists so the walking skeleton runs end to end on a laptop with no database —
 * the dev host has no Docker, only a psql client. The compact-mode PostgreSQL outbox is the next
 * step, tested against real PostgreSQL binaries through {@code io.zonky.test:embedded-postgres}
 * rather than Testcontainers.
 */
@Component
public class InMemoryDecisionLog implements DecisionLog {

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

  @Override
  public String describe() {
    return "in-memory (NOT DURABLE - local development only; decisions are lost on restart)";
  }

  /** Tenant-scoped key. A decision is never readable across tenants (D1). */
  private static String key(String tenantId, String id) {
    return tenantId + "/" + id;
  }
}
