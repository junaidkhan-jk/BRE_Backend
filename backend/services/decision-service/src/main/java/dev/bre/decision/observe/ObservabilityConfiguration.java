package dev.bre.decision.observe;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the decision metrics (M4-08). */
@Configuration
public class ObservabilityConfiguration {

  /**
   * The decision metrics, over whatever registry is configured.
   *
   * <p>Spring Boot supplies a simple in-memory registry when no exporter is on the classpath and
   * the Prometheus one when it is, so this works in a test without a scraper and exports in a
   * deployment without a code change.
   *
   * @param registry the meter registry
   * @return the metrics
   */
  @Bean
  public DecisionMetrics decisionMetrics(MeterRegistry registry) {
    return new DecisionMetrics(registry);
  }
}
