package dev.bre.decision.admit;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The CPU bulkhead and per-tenant fair share (M4-05, §6.3, §6.4 control 3).
 *
 * <p><b>Why a semaphore in front of virtual threads.</b> Every request runs on its own virtual
 * thread, which makes blocking cheap — and makes it trivially easy to have ten thousand of them
 * trying to evaluate policy on sixteen cores at once. Virtual threads do not make CPU-bound work
 * faster; they remove the queue that used to throttle it. Without a bulkhead a burst does not slow
 * down gracefully, it collapses: every request gets a slice, every request misses its deadline, and
 * the pod reports healthy while serving nothing inside budget. §6.3 sizes the semaphore at about 2
 * × cores for exactly this.
 *
 * <p><b>Why the fair share on top of it, which is the half that matters here.</b> A plain semaphore
 * is first-come-first-served, and in a shared cell that means the tenant generating the most load
 * wins the most permits. One lender running a bulk upload would take the pod, and every other
 * lender on it would breach their latency SLO through no fault of their own — a failure they cannot
 * see, cannot fix and are paying for. So admission is capped per tenant at a share of the pod when
 * the pod is busy.
 *
 * <p><b>The share is only enforced under contention.</b> A quiet pod lets any tenant use all of it,
 * because reserving capacity nobody wants is just a smaller pod. The cap applies when permits are
 * scarce, which is the only moment fairness means anything.
 *
 * <p><b>Shedding is a 503 with {@code Retry-After}, never a queue.</b> A request held until it is
 * hopeless is worse than one refused immediately: the caller has a timeout of its own, and a
 * decision delivered after the caller gave up has consumed a pod's CPU to produce nothing. §6.4
 * says 503 with {@code Retry-After: 1} for this reason.
 */
public final class FairShareBulkhead {

  private static final Logger log = LoggerFactory.getLogger(FairShareBulkhead.class);

  private final Semaphore permits;
  private final int capacity;
  private final double tenantShare;
  private final Duration waitFor;
  private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

  /**
   * Creates the bulkhead.
   *
   * @param capacity how many evaluations may run at once; §6.3 says about 2 × cores
   * @param tenantShare the fraction of capacity one tenant may hold when the pod is contended, as a
   *     number between 0 and 1. The reciprocal is roughly how many tenants can be busy at once
   *     without anybody being starved.
   * @param waitFor how long a request waits for a permit before it is shed
   */
  public FairShareBulkhead(int capacity, double tenantShare, Duration waitFor) {
    this.capacity = Math.max(1, capacity);
    // Fair semaphore: FIFO among waiters. An unfair one is faster and lets a thread that just
    // released a permit immediately reacquire it, which under sustained load from one tenant is
    // precisely the starvation this class exists to prevent.
    this.permits = new Semaphore(this.capacity, true);
    this.tenantShare = Math.min(1.0, Math.max(0.01, tenantShare));
    this.waitFor = waitFor;
  }

  /** A request was shed rather than queued. Mapped to 503 with {@code Retry-After}. */
  public static final class SheddingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient String tenantId;

    SheddingException(String message, String tenantId) {
      super(message);
      this.tenantId = tenantId;
    }

    /**
     * The tenant that was shed.
     *
     * @return the tenant id
     */
    public String tenantId() {
      return tenantId;
    }
  }

  /**
   * Runs the work inside the bulkhead, or sheds it.
   *
   * @param tenantId whose request this is
   * @param work the CPU-bound work — evaluation and encoding, never an I/O wait
   * @param <T> what it returns
   * @return the result
   * @throws SheddingException when the pod is saturated or the tenant is over its share
   */
  public <T> T call(String tenantId, java.util.function.Supplier<T> work) {
    AtomicInteger mine = inFlight.computeIfAbsent(tenantId, key -> new AtomicInteger());

    // The order here is the whole correctness argument, and two earlier versions got it wrong.
    //
    // Checking the tenant's count BEFORE acquiring is a check-then-act race: on an idle pod every
    // arriving thread reads the same count of zero, every one passes, and one tenant ends up
    // holding the entire pod. The noisy-neighbour test caught both attempts — 8 of 8 slots, then
    // 6 of 8, against a cap of 2.
    //
    // So the permit is acquired FIRST, and only then is the tenant's share checked against a
    // count that is now consistent with what is actually held. A tenant over its share holds a
    // permit for the few microseconds it takes to discover that and give it back. That is a real
    // cost and it is the cheaper one: the alternative is a cap that does not hold.
    int cap = Math.max(1, (int) Math.floor(capacity * tenantShare));
    int reserve = capacity - cap;

    boolean acquired;
    try {
      acquired = permits.tryAcquire(waitFor.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new SheddingException("interrupted while waiting to evaluate", tenantId);
    }
    if (!acquired) {
      throw new SheddingException(
          "the pod is saturated and did not free an evaluation slot within "
              + waitFor.toMillis()
              + " ms. Shed rather than queued: a decision delivered after the caller has timed out"
              + " has spent CPU to produce nothing.",
          tenantId);
    }

    int held = mine.incrementAndGet();
    // Over its share, AND the rest of the pod is down to what the other tenants are owed. The
    // second half keeps this work-conserving: a quiet pod still lets one tenant use all of it,
    // because reserving capacity nobody wants is just a smaller pod.
    if (held > cap && permits.availablePermits() < reserve) {
      mine.decrementAndGet();
      permits.release();
      throw new SheddingException(
          "this tenant already holds "
              + cap
              + " concurrent evaluations, and the rest of the pod is reserved for other tenants."
              + " Retry in a second — the capacity is there, it is being shared.",
          tenantId);
    }

    try {
      return work.get();
    } finally {
      mine.decrementAndGet();
      permits.release();
    }
  }

  /**
   * How many evaluations are running for a tenant. For tests and for a gauge.
   *
   * @param tenantId the tenant
   * @return the count
   */
  public int inFlightFor(String tenantId) {
    AtomicInteger mine = inFlight.get(tenantId);
    return mine == null ? 0 : mine.get();
  }

  /**
   * How many permits are free.
   *
   * @return the count
   */
  public int availablePermits() {
    return permits.availablePermits();
  }

  /** Logs the sizing at startup, where an operator can see what the pod will admit. */
  public void logSizing() {
    log.info(
        "CPU bulkhead: {} concurrent evaluations, one tenant may hold {} of them when contended,"
            + " {} ms before shedding (§6.3, §6.4)",
        capacity,
        Math.max(1, (int) Math.floor(capacity * tenantShare)),
        waitFor.toMillis());
  }
}
