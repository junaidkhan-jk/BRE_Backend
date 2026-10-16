package dev.bre.decision.admit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M4-05's exit criterion: a noisy neighbour keeps the other tenant within SLO (§6.4).
 *
 * <p><b>The failure this prevents.</b> A plain semaphore is first-come-first-served, so in a shared
 * cell the tenant generating the most load wins the most permits. One lender running a bulk upload
 * takes the pod, and every other lender on it breaches their latency SLO through no fault of their
 * own — a failure they cannot see, cannot fix, and are paying for. It is also the failure that
 * looks like nothing on a dashboard: the pod is at 100% CPU and serving requests, which is what a
 * healthy busy pod looks like.
 */
class NoisyNeighbourTest {

  @Test
  @DisplayName("a tenant flooding the pod cannot take more than its share")
  void aFloodCannotTakeMoreThanItsShare() throws Exception {
    // Four slots, half the pod per tenant when contended. Small numbers so the arithmetic is
    // visible: the noisy tenant may hold 2, leaving 2 for everybody else.
    FairShareBulkhead bulkhead = new FairShareBulkhead(4, 0.5, Duration.ofMillis(50));

    CountDownLatch holdThem = new CountDownLatch(1);
    CountDownLatch noisyInside = new CountDownLatch(2);
    AtomicInteger noisyAdmitted = new AtomicInteger();
    AtomicInteger noisyShed = new AtomicInteger();

    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      // The noisy neighbour: twenty concurrent requests that each hold their slot open.
      for (int i = 0; i < 20; i++) {
        pool.execute(
            () -> {
              try {
                bulkhead.call(
                    "t_noisy",
                    () -> {
                      noisyAdmitted.incrementAndGet();
                      noisyInside.countDown();
                      await(holdThem);
                      return "done";
                    });
              } catch (FairShareBulkhead.SheddingException shed) {
                noisyShed.incrementAndGet();
              }
            });
      }

      assertThat(noisyInside.await(5, TimeUnit.SECONDS))
          .as("the noisy tenant should get its share immediately")
          .isTrue();
      // Let the rest pile up and be shed.
      Thread.sleep(300);

      assertThat(noisyAdmitted.get())
          .as("one tenant must not hold the whole pod, however hard it pushes")
          .isLessThanOrEqualTo(2);
      assertThat(noisyShed.get())
          .as("the rest are shed rather than queued forever")
          .isGreaterThan(0);

      // The assertion that IS the exit criterion: the quiet tenant is served, right now, while
      // the neighbour is flooding. Not eventually — the capacity was reserved by excluding the
      // flood, so this call does not even have to wait.
      String quiet = bulkhead.call("t_quiet", () -> "served");
      assertThat(quiet).isEqualTo("served");

      holdThem.countDown();
    }
  }

  @Test
  @DisplayName("a quiet pod lets one tenant use all of it")
  void theShareOnlyAppliesUnderContention() {
    // Reserving capacity nobody wants is just a smaller pod. The cap exists for the moment permits
    // are scarce, which is the only moment fairness means anything — and a test that asserted the
    // cap always applies would be asserting a slower service.
    FairShareBulkhead bulkhead = new FairShareBulkhead(4, 0.5, Duration.ofMillis(50));
    for (int i = 0; i < 10; i++) {
      assertThat(bulkhead.call("t_alone", () -> "ok")).isEqualTo("ok");
    }
    assertThat(bulkhead.availablePermits()).isEqualTo(4);
  }

  @Test
  @DisplayName("a saturated pod sheds rather than queueing until the caller has gone")
  void saturationShedsQuickly() throws Exception {
    FairShareBulkhead bulkhead = new FairShareBulkhead(1, 1.0, Duration.ofMillis(100));
    CountDownLatch hold = new CountDownLatch(1);
    CountDownLatch inside = new CountDownLatch(1);

    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      pool.execute(
          () ->
              bulkhead.call(
                  "t_a",
                  () -> {
                    inside.countDown();
                    await(hold);
                    return "held";
                  }));
      assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

      // Shed in about the configured wait, not held until the caller's own timeout. A decision
      // delivered after the caller gave up has spent a pod's CPU to produce nothing, and made
      // every request behind it later too.
      long before = System.currentTimeMillis();
      assertThatThrownBy(() -> bulkhead.call("t_b", () -> "never"))
          .isInstanceOf(FairShareBulkhead.SheddingException.class)
          .hasMessageContaining("saturated");
      assertThat(System.currentTimeMillis() - before).isLessThan(2_000);

      hold.countDown();
    }
  }

  @Test
  @DisplayName("every permit is returned, including when the work throws")
  void permitsAreReleasedOnFailure() {
    // The leak that turns a transient error into an outage: a pod that loses one permit per failed
    // request admits nothing at all after a few hundred of them, and reports healthy throughout.
    FairShareBulkhead bulkhead = new FairShareBulkhead(2, 1.0, Duration.ofMillis(50));
    for (int i = 0; i < 50; i++) {
      assertThatThrownBy(
              () ->
                  bulkhead.call(
                      "t_a",
                      () -> {
                        throw new IllegalStateException("the policy blew up");
                      }))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThat(bulkhead.availablePermits()).isEqualTo(2);
    assertThat(bulkhead.inFlightFor("t_a")).isZero();
  }

  @Test
  @DisplayName("several tenants under load each keep a share")
  void severalTenantsShare() throws Exception {
    FairShareBulkhead bulkhead = new FairShareBulkhead(8, 0.25, Duration.ofMillis(200));
    CountDownLatch hold = new CountDownLatch(1);
    List<String> tenants = List.of("t_a", "t_b", "t_c", "t_d");

    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (String tenant : tenants) {
        for (int i = 0; i < 10; i++) {
          pool.execute(
              () -> {
                try {
                  bulkhead.call(
                      tenant,
                      () -> {
                        await(hold);
                        return "ok";
                      });
                } catch (FairShareBulkhead.SheddingException ignored) {
                  // Expected for everything over the share.
                }
              });
        }
      }
      Thread.sleep(500);

      // Nobody is starved. With four tenants each capped at a quarter of eight slots, each should
      // hold about two — and crucially none should hold zero, which is what starvation looks like.
      for (String tenant : tenants) {
        assertThat(bulkhead.inFlightFor(tenant))
            .as("%s must not be starved by the others", tenant)
            .isGreaterThan(0)
            .isLessThanOrEqualTo(2);
      }
      hold.countDown();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
