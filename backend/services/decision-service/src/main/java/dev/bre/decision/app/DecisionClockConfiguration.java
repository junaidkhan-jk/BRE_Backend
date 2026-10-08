package dev.bre.decision.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the one clock in the service.
 *
 * <p>The engine never reads a clock (D8). This is the single place a "today" enters the system —
 * used only to resolve an {@code asOf} the caller omitted, after which the resolved value is stored
 * on the decision record and replayed rather than re-derived.
 *
 * <p>A bean rather than a constant so a test can replace it, and {@code @ConditionalOnMissingBean}
 * so replacing it needs no profile or exclusion.
 */
@Configuration
public class DecisionClockConfiguration {

  /** Default tenant time zone (00 §3.7a). All data stays in India (D13). */
  private static final ZoneId TENANT_ZONE = ZoneId.of("Asia/Kolkata");

  /**
   * The system clock in the tenant time zone.
   *
   * @return the clock
   */
  @Bean
  @ConditionalOnMissingBean
  public Clock decisionClock() {
    return Clock.system(TENANT_ZONE);
  }

  /**
   * The JSON codec used to store the fact snapshot on the decision record.
   *
   * <p>Declared here because Spring Boot 4 no longer exposes an {@code ObjectMapper} bean from its
   * Jackson auto-configuration. Storing the snapshot verbatim is what makes a replay exact (D7), so
   * this is part of the decision path rather than a web concern.
   *
   * @return the mapper
   */
  @Bean
  @ConditionalOnMissingBean
  public ObjectMapper decisionObjectMapper() {
    return JsonMapper.builder().build();
  }
}
