package dev.bre.decision.admit;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Sizes the CPU bulkhead (M4-05, §6.3, §6.4). */
@Configuration
public class AdmissionConfiguration {

  /**
   * The bulkhead, sized from the pod's own cores by default.
   *
   * <p>Read from the runtime rather than configured, because the right number is a property of the
   * machine and a configured one is wrong on every machine but the one it was written for — and in
   * a container it is wrong in the direction that hurts, since a pod with a 2-core limit on a
   * 64-core node would otherwise admit 128 concurrent evaluations and thrash. {@code
   * availableProcessors} respects the cgroup quota.
   *
   * @param capacity an override, or 0 to size from the cores
   * @param tenantShare how much of the pod one tenant may hold when it is contended
   * @param waitMillis how long to wait for a slot before shedding
   * @return the bulkhead
   */
  @Bean
  public FairShareBulkhead fairShareBulkhead(
      @Value("${bre.admission.capacity:0}") int capacity,
      @Value("${bre.admission.tenant-share:0.5}") double tenantShare,
      @Value("${bre.admission.wait-ms:250}") long waitMillis) {

    int sized =
        capacity > 0 ? capacity : Math.max(2, 2 * Runtime.getRuntime().availableProcessors());
    return new FairShareBulkhead(sized, tenantShare, Duration.ofMillis(waitMillis));
  }
}
