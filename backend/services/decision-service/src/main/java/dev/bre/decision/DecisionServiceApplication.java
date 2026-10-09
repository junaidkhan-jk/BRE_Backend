package dev.bre.decision;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The sync Decision API (LLD §6.2).
 *
 * <p>Virtual threads are on (ADR-012): evaluation is CPU-bound and short, and the write to the
 * decision log is the only blocking hop, so a thread-per-request model on virtual threads is the
 * simplest thing that meets the latency budget without an async programming model.
 */
@SpringBootApplication
@org.springframework.boot.context.properties.EnableConfigurationProperties(
    dev.bre.decision.policy.PolicyConfiguration.PolicyProperties.class)
public class DecisionServiceApplication {

  /**
   * Starts the service.
   *
   * @param args command-line arguments, passed through to Spring Boot
   */
  public static void main(String[] args) {
    SpringApplication.run(DecisionServiceApplication.class, args);
  }
}
