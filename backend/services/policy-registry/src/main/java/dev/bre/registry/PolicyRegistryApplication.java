package dev.bre.registry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The policy registry: where a policy is written, reviewed, approved, signed and deployed (M2-03).
 *
 * <p><b>This is the control plane, and it is a different kind of service from the decision
 * service.</b> The decision service is a pure function with a durable write; this one holds the
 * lifecycle, the approvals and the signing keys. Three consequences follow, and they are why the
 * two are separate deployables rather than one:
 *
 * <ul>
 *   <li><b>Only this service signs.</b> A decision pod holds public keys and verifies (§8.6); the
 *       private bundle and manifest keys live here, behind the KMS. A compromised decision pod can
 *       refuse policies but cannot mint one.
 *   <li><b>This service is not on the decision path.</b> It can be down for an hour and every
 *       decision pod keeps serving the manifest it already holds (§8.5). That is deliberate: the
 *       thing that changes policy must not be able to stop policy being applied.
 *   <li><b>It is write-heavy and low-volume.</b> A lender edits policies a few times a week and
 *       takes decisions thousands of times an hour, so the two scale on different axes and have
 *       entirely different latency budgets.
 * </ul>
 */
@SpringBootApplication
public class PolicyRegistryApplication {

  /**
   * Entry point.
   *
   * @param args the command line
   */
  public static void main(String[] args) {
    SpringApplication.run(PolicyRegistryApplication.class, args);
  }
}
