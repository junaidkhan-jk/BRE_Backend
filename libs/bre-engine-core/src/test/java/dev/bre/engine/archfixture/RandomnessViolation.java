package dev.bre.engine.archfixture;

import java.util.Random;

/**
 * DELIBERATE VIOLATION of LLD §1.2 rule 1 (determinism, D8). Champion/challenger assignment must be
 * {@code hash(tenantId, applicationRef, experimentId) mod 10000}, never random.
 */
public final class RandomnessViolation {

  private RandomnessViolation() {}

  /** Assigns a traffic bucket at random, which makes replay impossible. */
  public static int bucket() {
    return new Random().nextInt(10000);
  }
}
