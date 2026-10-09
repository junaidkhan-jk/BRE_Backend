package dev.bre.registry.db;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Executes the {@code bre_ctl} migrations against a real PostgreSQL (LLD §5.2).
 *
 * <p><b>Why this test is the point of the task, not an extra.</b> The control-plane DDL is 760
 * lines — 34 tables, 25 triggers, 9 functions, four GiST exclusion constraints, row-level security
 * on thirty tables — and no database had ever parsed a line of it. A schema that has only been read
 * is a draft. Flyway running it is the first thing that can say whether it is a schema.
 *
 * <p>A real PostgreSQL rather than an in-memory stand-in, because almost everything load-bearing
 * here is PostgreSQL-specific: {@code btree_gist}, exclusion constraints, {@code FORCE ROW LEVEL
 * SECURITY}, PL/pgSQL triggers, {@code current_setting(..., true)}. An H2 compatibility mode would
 * prove nothing about any of it. This host has no Docker, so embedded-postgres downloads and runs
 * an actual server.
 *
 * <p>The suite also asserts the two things the DDL exists to guarantee, because "the migration
 * applied" is a much weaker claim than "the isolation works":
 *
 * <ul>
 *   <li>row-level security is enabled <em>and forced</em> on every tenant-owned table, so even the
 *       table's owner cannot read across tenants;
 *   <li>a session that never set {@code bre.tenant_id} sees <b>no rows</b> rather than all of them
 *       (§5.1) — the fail-closed half of D1.
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("bre_ctl migrations against a real PostgreSQL")
class BreCtlMigrationTest {

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
            // The migrations create roles and an extension, which are cluster-level objects, so
            // they need the superuser the embedded server starts with. A managed PostgreSQL would
            // have the DBA create the roles out of band; V1 is written to tolerate both.
            .load()
            .migrate();
  }

  @AfterAll
  void stopPostgres() throws Exception {
    if (postgres != null) {
      postgres.close();
    }
  }

  private static int migrationScriptCount() throws Exception {
    var dir = java.nio.file.Path.of(BreCtlMigrationTest.class.getResource("/db/migration").toURI());
    try (var files = java.nio.file.Files.list(dir)) {
      return (int) files.filter(f -> f.getFileName().toString().matches("V\\d+__.*\\.sql")).count();
    }
  }

  @Test
  void everyMigrationApplies() throws Exception {
    assertThat(result.success).isTrue();
    // Counted from the files, not hard-coded. A literal goes stale the first time a migration is
    // added and then fails with "expected 7 but was 12" about migrations that applied perfectly.
    assertThat(result.migrationsExecuted)
        .as("every V__ script on the classpath must have run")
        .isEqualTo(migrationScriptCount());
  }

  @Test
  void theSchemaHoldsTheTablesTheSpecificationDeclares() throws Exception {
    List<String> tables =
        query(
            """
        SELECT table_name FROM information_schema.tables
         WHERE table_schema = 'bre_ctl' AND table_type = 'BASE TABLE'
         ORDER BY table_name
        """);
    assertThat(tables)
        .hasSizeGreaterThanOrEqualTo(30)
        .contains(
            "tenants",
            "policies",
            "policy_versions",
            "bundles",
            "deployments",
            "audit_log",
            "asset_versions",
            "authority_entries",
            "fact_schemas",
            "resolution_index");
  }

  @Test
  void theTriggersAndFunctionsExist() throws Exception {
    assertThat(
            query(
                """
        SELECT routine_name FROM information_schema.routines
         WHERE routine_schema = 'bre_ctl' ORDER BY routine_name
        """))
        .contains("tg_touch", "tg_forbid");

    assertThat(
            query(
                """
        SELECT DISTINCT trigger_name FROM information_schema.triggers
         WHERE trigger_schema = 'bre_ctl'
        """))
        .isNotEmpty();
  }

  /** The exclusion constraints are the ones that stop two overlapping effective windows. */
  @Test
  void theGistExclusionConstraintsExist() throws Exception {
    assertThat(
            query(
                """
        SELECT conname FROM pg_constraint c
          JOIN pg_namespace n ON n.oid = c.connamespace
         WHERE n.nspname = 'bre_ctl' AND c.contype = 'x'
        """))
        .as("§5.2 uses four GiST exclusion constraints; btree_gist must be installed for them")
        .hasSizeGreaterThanOrEqualTo(1);
  }

  /**
   * Enabled is not enough. Without FORCE, row-level security does not apply to the table's owner,
   * and the application connects as a role that owns its tables in plenty of deployments.
   */
  @Test
  void rowLevelSecurityIsEnabledAndForcedOnEveryTenantOwnedTable() throws Exception {
    List<String> unprotected =
        query(
            """
        SELECT c.relname FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'bre_ctl' AND c.relkind = 'r'
           AND EXISTS (SELECT 1 FROM information_schema.columns col
                        WHERE col.table_schema = 'bre_ctl' AND col.table_name = c.relname
                          AND col.column_name = 'tenant_id')
           AND NOT (c.relrowsecurity AND c.relforcerowsecurity)
         ORDER BY c.relname
        """);
    assertThat(unprotected)
        .as("every table with a tenant_id must have RLS enabled AND forced")
        .isEmpty();
  }

  @Test
  void everyTenantOwnedTableHasAnIsolationPolicy() throws Exception {
    List<String> withoutPolicy =
        query(
            """
        SELECT c.relname FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'bre_ctl' AND c.relkind = 'r'
           AND c.relrowsecurity
           AND NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid)
         ORDER BY c.relname
        """);
    assertThat(withoutPolicy)
        .as("RLS with no policy denies everything, which is safe but not what §5.2 writes")
        .isEmpty();
  }

  /**
   * §5.1's fail-closed claim, executed rather than read: "If the setting is missing,
   * current_setting(..., true) returns NULL and the session sees no rows".
   *
   * <p>This is the single most important property of the schema. It is asserted as a non-superuser,
   * because a superuser bypasses RLS entirely and would make the test pass for the wrong reason.
   */
  @Test
  void aSessionWithNoTenantSeesNoRows() throws Exception {
    try (Connection admin = dataSource.getConnection();
        Statement statement = admin.createStatement()) {
      statement.execute("CREATE ROLE rls_probe LOGIN PASSWORD 'probe' NOBYPASSRLS");
      statement.execute("GRANT USAGE ON SCHEMA bre_ctl TO rls_probe");
      statement.execute("GRANT SELECT, INSERT ON bre_ctl.tenants TO rls_probe");

      // A cell first: tenants.cell_id is NOT NULL and references it.
      statement.execute(
          "INSERT INTO bre_ctl.cells (cell_id, region_pair, status, hot_store_shards) "
              + "VALUES (1, 'ap-south-1/ap-southeast-1', 'ACTIVE', 4) ON CONFLICT DO NOTHING");

      // Two tenants' rows, inserted by the superuser so RLS does not block the setup.
      // display_name, not name — the column the §5.2 DDL actually declares.
      statement.execute(
          "INSERT INTO bre_ctl.tenants (tenant_id, display_name, status, cell_id) "
              + "VALUES ('t_alpha', 'Alpha', 'ACTIVE', 1), ('t_beta', 'Beta', 'ACTIVE', 1) "
              + "ON CONFLICT DO NOTHING");
    }

    try (Connection probe = postgres.getDatabase("rls_probe", "postgres").getConnection();
        Statement statement = probe.createStatement()) {

      // No bre.tenant_id set at all.
      assertThat(count(statement, "SELECT count(*) FROM bre_ctl.tenants"))
          .as("an unset tenant must see nothing, not everything")
          .isZero();

      statement.execute("SELECT set_config('bre.tenant_id', 't_alpha', false)");
      assertThat(count(statement, "SELECT count(*) FROM bre_ctl.tenants"))
          .as("with a tenant set, that tenant's row and only that row")
          .isEqualTo(1);

      statement.execute("SELECT set_config('bre.tenant_id', 't_beta', false)");
      assertThat(count(statement, "SELECT count(*) FROM bre_ctl.tenants")).isEqualTo(1);

      statement.execute("SELECT set_config('bre.tenant_id', 't_nonexistent', false)");
      assertThat(count(statement, "SELECT count(*) FROM bre_ctl.tenants")).isZero();
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
    assertThat(again.migrationsExecuted)
        .as("a second run must apply nothing, not fail and not re-apply")
        .isZero();
  }

  // ---------------------------------------------------------------------------------------------

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
