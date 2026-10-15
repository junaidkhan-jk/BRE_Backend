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
   * The JSON codec used to store the decision record and its outbox event.
   *
   * <p>Declared here because Spring Boot 4 no longer exposes an {@code ObjectMapper} bean from its
   * Jackson auto-configuration — it serialises HTTP with Jackson 3 instead. Storing the record
   * verbatim is what makes a replay exact (D7), so this is part of the decision path rather than a
   * web concern.
   *
   * <p><b>The JSR-310 module is registered explicitly, and that was a real failure.</b> A mapper
   * built with no modules cannot serialise a {@code java.time.LocalDate}, and a decision response
   * carries {@code asOf} — so every write in POSTGRES mode failed with "Java 8 date/time type not
   * supported by default" and the decision returned a 500 <em>after</em> deciding correctly. The
   * dependency was on the classpath; nothing had registered it. {@code findAndRegisterModules} is
   * deliberately not used: it loads whatever happens to be on the classpath, so the set of handlers
   * would change when an unrelated dependency did, and the thing being changed is the on-disk
   * format of a lender's decision record.
   *
   * @return the mapper
   */
  @Bean
  @ConditionalOnMissingBean
  public ObjectMapper decisionObjectMapper() {
    return JsonMapper.builder()
        .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
        // ISO-8601 strings rather than epoch numbers. A decision record is read years later by a
        // person and by whatever tooling exists then; "2026-10-01" survives that and 1790380800
        // needs a decoder ring.
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
  }
}
