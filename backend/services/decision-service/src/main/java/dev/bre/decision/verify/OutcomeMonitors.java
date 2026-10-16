package dev.bre.decision.verify;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The post-activation outcome guardrails (M4-07, §11.4, §10.1).
 *
 * <p><b>What these are for.</b> A bundle can pass every test in CI, pass its load-time self-test,
 * activate cleanly — and still be wrong, because the tests asserted what its author believed. The
 * first external evidence of that is the shape of the decisions it makes: a decline rate that
 * doubles overnight, an ERROR rate that was zero yesterday, a variable that has started arriving
 * empty. These monitors watch for exactly that, which is why §10.1 calls them the post-activation
 * guardrail rather than a dashboard.
 *
 * <p><b>Compared against the SAME HOUR of the previous seven days, not against yesterday.</b>
 * Lending volume is strongly diurnal and weekly: a branch network decides most of its applications
 * between ten and four on weekdays, so 03:00 Sunday against 15:00 Monday is a comparison that
 * alerts every night and is therefore switched off within a week. The same hour across a week is
 * the baseline that holds.
 *
 * <p><b>Three sigma, and a minimum sample.</b> Three standard deviations over seven observations is
 * a loose test, and it is meant to be: this is a guardrail against a bundle that is obviously
 * wrong, not a statistical model. The minimum sample matters more than the sigma — with four
 * decisions in an hour, any rate is noise, and an alert on it trains people to ignore alerts.
 */
public final class OutcomeMonitors {

  /**
   * How many decisions an hour must hold before its rates mean anything.
   *
   * <p>Below this the arithmetic still works and the answer is still noise. An alert nobody can act
   * on is worse than no alert, because it is the one that teaches an on-call engineer to close the
   * page without reading it.
   */
  static final int MINIMUM_SAMPLE = 30;

  /** §11.4: rule ERROR results ÷ rule evaluations, above 0.05% for ten minutes, pages. */
  static final double ERROR_RATE_PAGE = 0.0005;

  private final JdbcTemplate jdbc;

  /**
   * Creates the monitors.
   *
   * @param jdbc the hot store
   */
  public OutcomeMonitors(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Runs every monitor for one tenant over the hour just completed.
   *
   * @param tenantId the tenant
   * @return what was found, empty when nothing crossed a threshold
   */
  public List<Finding> check(String tenantId) {
    List<Finding> findings = new ArrayList<>();
    findings.addAll(decisionMix(tenantId));
    errorRate(tenantId).ifPresent(findings::add);
    return findings;
  }

  /**
   * The decision mix against the same hour over the previous seven days (§11.4).
   *
   * <p>One row per outcome, so a decline rate that doubles and an approve rate that halves are two
   * findings rather than one — they have different causes often enough that collapsing them loses
   * the signal.
   */
  private List<Finding> decisionMix(String tenantId) {
    return jdbc
        .query(
            """
        WITH recent AS (
          SELECT decision, count(*) AS n,
                 sum(count(*)) OVER () AS total
            FROM bre_dec.decisions
           WHERE tenant_id = ?
             AND completed_at >= date_trunc('hour', now()) - interval '1 hour'
             AND completed_at <  date_trunc('hour', now())
           GROUP BY decision
        ),
        -- The same hour of each of the previous seven days, as seven separate observations. A
        -- single pooled rate would hide the variance, and the variance is what decides whether a
        -- change is unusual.
        baseline AS (
          SELECT day, decision, n::numeric / nullif(sum(n) OVER (PARTITION BY day), 0) AS share
            FROM (
              SELECT date_trunc('day', completed_at) AS day, decision, count(*) AS n
                FROM bre_dec.decisions
               WHERE tenant_id = ?
                 AND completed_at >= now() - interval '8 days'
                 AND completed_at <  date_trunc('hour', now()) - interval '1 hour'
                 AND extract(hour FROM completed_at)
                     = extract(hour FROM date_trunc('hour', now()) - interval '1 hour')
               GROUP BY 1, 2
            ) per_day
        ),
        stats AS (
          SELECT decision, avg(share) AS mean, coalesce(stddev_samp(share), 0) AS sd,
                 count(*) AS days
            FROM baseline GROUP BY decision
        )
        SELECT r.decision,
               r.n::numeric / nullif(r.total, 0) AS share,
               coalesce(s.mean, 0) AS mean,
               coalesce(s.sd, 0) AS sd,
               r.total AS sample,
               coalesce(s.days, 0) AS days
          FROM recent r LEFT JOIN stats s ON s.decision = r.decision
        """,
            (rs, rowNum) -> {
              String decision = rs.getString("decision");
              double share = rs.getDouble("share");
              double mean = rs.getDouble("mean");
              double sd = rs.getDouble("sd");
              long sample = rs.getLong("sample");
              int days = rs.getInt("days");

              // No baseline yet, or too few decisions to say anything. Silence is the honest
              // answer:
              // a new tenant's first week must not page anybody.
              if (days < 3 || sample < MINIMUM_SAMPLE) {
                return null;
              }
              // A zero standard deviation means the share was identical on all seven days, which
              // with
              // real volume does not happen — and dividing by it would make every tiny change
              // infinitely many sigmas. Fall back to a flat ten percentage points.
              boolean breached =
                  sd > 0 ? Math.abs(share - mean) > 3 * sd : Math.abs(share - mean) > 0.10;
              if (!breached) {
                return null;
              }
              return new Finding(
                  "DECISION_MIX",
                  Finding.TICKET,
                  tenantId,
                  ("the %s share was %.1f%% in the last hour against a %.1f%% baseline for the same"
                          + " hour over %d days — §10.1's post-activation guardrail. If a deployment"
                          + " went out in the last hour, this is the first thing to look at.")
                      .formatted(decision, share * 100, mean * 100, days),
                  share,
                  mean);
            },
            tenantId,
            tenantId)
        .stream()
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  /**
   * The ERROR rate (§11.4): rule ERROR results over rule evaluations, paging above 0.05%.
   *
   * <p>Pages rather than tickets, and the reason is D9. A rule that ERRORs does not produce a wrong
   * answer — the engine fails closed and REFERs — so this is not a correctness alarm. It is a
   * throughput one: every ERROR is an application a human now has to look at, and a tenth of a
   * percent of a day's volume is a queue nobody staffed for.
   */
  private java.util.Optional<Finding> errorRate(String tenantId) {
    return jdbc
        .query(
            """
            SELECT coalesce(sum(error_count), 0)::numeric AS errors,
                   coalesce(sum(jsonb_array_length(results_json)), 0)::numeric AS evaluations,
                   count(*) AS decisions
              FROM bre_dec.decisions
             WHERE tenant_id = ?
               AND completed_at >= now() - interval '10 minutes'
            """,
            (rs, rowNum) -> {
              double errors = rs.getDouble("errors");
              double evaluations = rs.getDouble("evaluations");
              long decisions = rs.getLong("decisions");
              if (decisions < MINIMUM_SAMPLE || evaluations <= 0) {
                return null;
              }
              double rate = errors / evaluations;
              if (rate <= ERROR_RATE_PAGE) {
                return null;
              }
              return new Finding(
                  "ERROR_RATE",
                  Finding.PAGE,
                  tenantId,
                  ("%.3f%% of rule evaluations returned ERROR in the last ten minutes, above the"
                          + " 0.05%% threshold. The engine failed closed, so these are REFERs"
                          + " rather than wrong answers — but every one is an application somebody"
                          + " now has to review by hand.")
                      .formatted(rate * 100),
                  rate,
                  ERROR_RATE_PAGE);
            },
            tenantId)
        .stream()
        .filter(java.util.Objects::nonNull)
        .findFirst();
  }
}
