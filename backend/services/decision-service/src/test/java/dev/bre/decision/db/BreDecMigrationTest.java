package dev.bre.decision.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Executes the {@code bre_dec} migrations against a real PostgreSQL (LLD §5.3).
 *
 * <p>The decision hot store had been written, reviewed and never run. It is the table an audit
 * reads a decision back out of, so a defect in it is only discovered when someone needs the record
 * — which is the worst possible moment. Executing the sibling {@code bre_ctl} DDL found a
 * tenant-owned table with no row-level security on the first attempt, so this is not a formality
 * either.
 *
 * <p>Declarative partitioning is the part an in-memory database cannot stand in for, and it is also
 * the part most likely to be subtly wrong: a partitioned table's primary key must contain the
 * partition key, and a missing default partition turns an out-of-range insert into a runtime error
 * rather than a row.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("bre_dec migrations against a real PostgreSQL")
class BreDecMigrationTest {

  private static EmbeddedPostgres postgres;
  private static DataSource dataSource;
  private static MigrateResult result;

  @BeforeAll
  void startPostgresAndMigrate() throws Exception {
    postgres = EmbeddedPostgres.builder().start();
    dataSource = postgres.getPostgresDatabase();
    result =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();
  }

  @AfterAll
  void stopPostgres() throws Exception {
    if (postgres != null) {
      postgres.close();
    }
  }

  @Test
  void everyMigrationApplies() throws Exception {
    assertThat(result.success).isTrue();
    // Counted from the files rather than hard-coded. A literal here goes stale the first time a
    // migration is added, and then fails for a reason that has nothing to do with the migration:
    // it reports "expected 3 but was 4" about a migration that applied perfectly.
    assertThat(result.migrationsExecuted)
        .as("every V__ script on the classpath must have run")
        .isEqualTo(migrationScriptCount());
  }

  private static int migrationScriptCount() throws Exception {
    var dir = java.nio.file.Path.of(BreDecMigrationTest.class.getResource("/db/migration").toURI());
    try (var files = java.nio.file.Files.list(dir)) {
      return (int) files.filter(f -> f.getFileName().toString().matches("V\\d+__.*\\.sql")).count();
    }
  }

  @Test
  void theHotStoreTablesExist() throws Exception {
    assertThat(
            query(
                """
        SELECT table_name FROM information_schema.tables
         WHERE table_schema = 'bre_dec' ORDER BY table_name
        """))
        .contains("decisions", "idempotency_keys", "outbox", "decisions_default");
  }

  @Test
  void theDecisionTableIsPartitioned() throws Exception {
    assertThat(
            query(
                """
        SELECT c.relname FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'bre_dec' AND c.relkind = 'p'
         ORDER BY c.relname
        """))
        .as("§5.3 partitions the hot store; a plain table would not prune or detach")
        .isNotEmpty();
  }

  /**
   * A partitioned table with no default partition rejects an out-of-range insert outright. V3
   * creates the defaults so that a decision on a date nobody provisioned a partition for is still
   * written — losing an acknowledged decision is the one outcome D6 exists to prevent.
   */
  @Test
  void aRowOutsideEveryProvisionedRangeStillLands() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      long before = count(statement, "SELECT count(*) FROM bre_dec.decisions");
      statement.execute(insertDecision("2099-01-01"));
      assertThat(count(statement, "SELECT count(*) FROM bre_dec.decisions"))
          .as("a far-future decision must reach the default partition rather than error")
          .isEqualTo(before + 1);
    }
  }

  /** The idempotency key is what stops a retried request producing a second decision. */
  @Test
  void theIdempotencyKeyIsUniquePerTenant() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(insertKey("t_alpha", "req-1"));

      assertThatThrownBy(() -> statement.execute(insertKey("t_alpha", "req-1")))
          .as("a repeated request id for one tenant must be refused by the database, not by hope")
          .hasMessageContaining("duplicate key");

      // The same request id for a DIFFERENT tenant is a different request and must be allowed —
      // the primary key is (tenant_id, request_id), and getting that order wrong would make one
      // lender's retry collide with another's.
      statement.execute(insertKey("t_beta", "req-1"));
    }
  }

  @Test
  void migrationsAreIdempotentOnARepeatRun() {
    MigrateResult again =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();
    assertThat(again.success).isTrue();
    assertThat(again.migrationsExecuted).isZero();
  }

  // ---------------------------------------------------------------------------------------------

  /** Column names read off the migration rather than guessed; see the §5.3 deviations in V1. */
  private static String insertDecision(String decisionDate) {
    return "INSERT INTO bre_dec.decisions (tenant_id, decision_date, decision_id, request_id,"
        + " application_ref, env_code, policy_code, policy_version, bundle_hash, engine_version,"
        + " as_of, as_of_date, time_zone, received_at, completed_at, latency_us, decision,"
        + " stp_eligible, results_json) VALUES ('t_alpha', '"
        + decisionDate
        + "', gen_random_uuid(), 'req-"
        + decisionDate
        + "', 'APP-1', 'PROD', 'P', 1, 'sha256:aa', '0.1.0', now(), '2026-10-01',"
        + " 'Asia/Kolkata', now(), now(), 1000, 'APPROVE', true, '{}'::jsonb)";
  }

  private static String insertKey(String tenant, String requestId) {
    return "INSERT INTO bre_dec.idempotency_keys (tenant_id, request_id, decision_id,"
        + " decision_date, request_fingerprint) VALUES ('"
        + tenant
        + "', '"
        + requestId
        + "', gen_random_uuid(), '2026-10-01', decode('00','hex'))";
  }

  private static List<String> query(String sql) throws Exception {
    List<String> out = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        out.add(rows.getString(1));
      }
    }
    return out;
  }

  private static long count(Statement statement, String sql) throws Exception {
    try (ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }
}
