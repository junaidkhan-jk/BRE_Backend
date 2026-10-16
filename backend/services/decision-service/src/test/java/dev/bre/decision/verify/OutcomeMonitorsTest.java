package dev.bre.decision.verify;

import static org.assertj.core.api.Assertions.assertThat;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The post-activation outcome guardrails (M4-07, §11.4, §10.1).
 *
 * <p>M4-07's exit criterion is "an injected mismatch alerts", so every test here injects the shape
 * of a real failure and asserts it is noticed — and, just as importantly, that the ordinary shapes
 * are NOT. A guardrail that fires on a quiet Sunday night is one somebody switches off before the
 * Monday it would have mattered.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutcomeMonitorsTest {

  private static final String TENANT = "t_guard";

  private static EmbeddedPostgres postgres;
  private static DataSource dataSource;
  private static JdbcTemplate jdbc;
  private OutcomeMonitors monitors;

  @BeforeAll
  void start() throws Exception {
    postgres = EmbeddedPostgres.builder().start();
    dataSource = postgres.getPostgresDatabase();
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    jdbc = new JdbcTemplate(dataSource);
  }

  @AfterAll
  void stop() throws Exception {
    if (postgres != null) {
      postgres.close();
    }
  }

  @BeforeEach
  void clear() {
    jdbc.update("DELETE FROM bre_dec.decisions WHERE tenant_id = ?", TENANT);
    monitors = new OutcomeMonitors(jdbc);
  }

  @Test
  @DisplayName("a decline rate that jumps against its own baseline is a finding")
  void aDeclineSpikeIsNoticed() {
    // Seven days of the same hour, steady at 10% declines. This is the baseline §11.4 asks for:
    // the SAME HOUR across a week, because lending volume is diurnal and weekly and comparing
    // 03:00 Sunday with 15:00 Monday alerts every night.
    for (int day = 1; day <= 7; day++) {
      Instant at = sameHourDaysAgo(day);
      decisions(at, "APPROVE", 90);
      decisions(at, "DECLINE", 10);
    }

    // The injected failure: last hour, half the applications declined. The shape of a bundle that
    // passed CI and is still wrong.
    Instant lastHour = lastCompletedHour();
    decisions(lastHour, "APPROVE", 50);
    decisions(lastHour, "DECLINE", 50);

    List<Finding> findings = monitors.check(TENANT);
    assertThat(findings)
        .as("a decline rate going from 10%% to 50%% must be noticed")
        .anySatisfy(
            finding -> {
              assertThat(finding.monitor()).isEqualTo("DECISION_MIX");
              assertThat(finding.summary()).contains("DECLINE");
              assertThat(finding.observed())
                  .isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.01));
              assertThat(finding.baseline())
                  .isCloseTo(0.1, org.assertj.core.data.Offset.offset(0.01));
            });
  }

  @Test
  @DisplayName("an ordinary hour against an ordinary baseline is silent")
  void normalTrafficIsSilent() {
    // The assertion that keeps the guardrail usable. Everything above is about noticing a real
    // change; this is about not crying wolf, which is what decides whether anybody reads the next
    // alert.
    for (int day = 1; day <= 7; day++) {
      Instant at = sameHourDaysAgo(day);
      decisions(at, "APPROVE", 88 + day);
      decisions(at, "DECLINE", 12 - day / 2);
    }
    Instant lastHour = lastCompletedHour();
    decisions(lastHour, "APPROVE", 90);
    decisions(lastHour, "DECLINE", 10);

    assertThat(monitors.check(TENANT)).isEmpty();
  }

  @Test
  @DisplayName("a new tenant's first hours do not page anybody")
  void tooLittleHistoryIsSilent() {
    // Three days of baseline is the minimum; with less there is nothing to compare against, and
    // an alert on a tenant's first morning is the one that teaches them to ignore alerts.
    Instant lastHour = lastCompletedHour();
    decisions(sameHourDaysAgo(1), "APPROVE", 100);
    decisions(lastHour, "DECLINE", 100);

    assertThat(monitors.check(TENANT)).isEmpty();
  }

  @Test
  @DisplayName("an hour with a handful of decisions is noise, not a signal")
  void tooSmallASampleIsSilent() {
    for (int day = 1; day <= 7; day++) {
      Instant at = sameHourDaysAgo(day);
      decisions(at, "APPROVE", 90);
      decisions(at, "DECLINE", 10);
    }
    // Five decisions, all declines. A 100% decline rate — and meaningless. Below the minimum
    // sample the arithmetic still works and the answer is still noise.
    decisions(lastCompletedHour(), "DECLINE", 5);

    assertThat(monitors.check(TENANT)).isEmpty();
  }

  @Test
  @DisplayName("a rule ERROR rate above 0.05% pages")
  void anErrorRateSpikePages() {
    // §11.4's threshold. Not a correctness alarm — the engine fails closed and REFERs, so these
    // are not wrong answers. It is a throughput one: every ERROR is an application a human now
    // has to look at.
    Instant recent = Instant.now().minus(2, ChronoUnit.MINUTES);
    insert(recent, "APPROVE", 0, 20);
    for (int i = 0; i < 49; i++) {
      insert(recent, "APPROVE", 0, 20);
    }
    insert(recent, "REFER", 3, 20);

    assertThat(monitors.check(TENANT))
        .anySatisfy(
            finding -> {
              assertThat(finding.monitor()).isEqualTo("ERROR_RATE");
              assertThat(finding.severity()).isEqualTo(Finding.PAGE);
              assertThat(finding.summary()).contains("failed closed");
            });
  }

  // --- plumbing --------------------------------------------------------------------------------

  /** The same hour of a day N days ago, which is what §11.4's baseline is built from. */
  private static Instant sameHourDaysAgo(int days) {
    return lastCompletedHour().minus(days, ChronoUnit.DAYS);
  }

  private static Instant lastCompletedHour() {
    return Instant.now().truncatedTo(ChronoUnit.HOURS).minus(30, ChronoUnit.MINUTES);
  }

  private void decisions(Instant at, String decision, int count) {
    for (int i = 0; i < count; i++) {
      insert(at, decision, 0, 20);
    }
  }

  /** One decision row, with the error count and the number of rule results it carried. */
  private void insert(Instant at, String decision, int errors, int ruleResults) {
    StringBuilder results = new StringBuilder("[");
    for (int i = 0; i < ruleResults; i++) {
      results.append(i > 0 ? "," : "").append("{\"code\":\"R").append(i).append("\"}");
    }
    results.append(']');

    jdbc.update(
        """
        INSERT INTO bre_dec.decisions (tenant_id, decision_date, decision_id, request_id,
            application_ref, env_code, policy_code, policy_version, bundle_hash, engine_version,
            as_of, as_of_date, time_zone, received_at, completed_at, latency_us, decision,
            stp_eligible, error_count, fuel_used, results_json)
        VALUES (?, ?::date, ?::uuid, ?, ?, 'TEST', 'P', 1, ?, '0.1.0-test',
            ?, ?::date, 'Asia/Kolkata', ?, ?, 1000, ?, false, ?, 0, ?::jsonb)
        """,
        TENANT,
        at.toString().substring(0, 10),
        UUID.randomUUID().toString(),
        "req-" + UUID.randomUUID(),
        "APP-" + UUID.randomUUID(),
        "sha256:" + "c".repeat(64),
        java.sql.Timestamp.from(at),
        at.toString().substring(0, 10),
        java.sql.Timestamp.from(at),
        java.sql.Timestamp.from(at),
        decision,
        errors,
        results.toString());
  }
}
