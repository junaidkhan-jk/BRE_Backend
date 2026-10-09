package dev.bre.registry.devstack;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Starts a real PostgreSQL for local development and keeps it running.
 *
 * <p><b>Why this exists.</b> The registry has no in-memory mode — a registry whose approvals are
 * not durable is not a registry — so running it locally needs a database, and this host has no
 * Docker. {@code io.zonky.test:embedded-postgres} downloads an actual PostgreSQL server binary from
 * Maven Central and runs it, which is the same thing the migration tests already use. One fewer
 * thing a developer has to install, and the same server the migrations are tested against.
 *
 * <p>Writes the connection details to a properties file so the shell script that started it can
 * pass them to the service without parsing log output. Deletes the file on shutdown, so a stale
 * file cannot point the next run at a port nothing is listening on.
 *
 * <p><b>Not for anything but local development.</b> The data directory is temporary and the server
 * is configured for convenience: trust authentication on loopback, and a superuser the migrations
 * need in order to create roles and reassign ownership. A deployed installation runs migrations as
 * an administrative role and the service as {@code bre_ctl_app}, which is what the lifecycle test
 * does.
 */
public final class EmbeddedPostgresMain {

  private EmbeddedPostgresMain() {}

  /**
   * Starts the server and blocks.
   *
   * @param args the properties file to write the connection details to
   * @throws Exception if the server cannot start
   */
  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("usage: EmbeddedPostgresMain <connection.properties>");
      System.exit(2);
    }
    Path target = Path.of(args[0]);

    EmbeddedPostgres postgres = EmbeddedPostgres.builder().start();
    String adminUrl = postgres.getJdbcUrl("postgres", "postgres");

    // The application role. V1 creates it NOLOGIN, as §5.2 specifies — a service account in
    // production authenticates by certificate or IAM rather than a password — so the migration
    // has to have run before this can be granted a login. It is done by the shell script after
    // migration, not here.
    // Both forms, because they are consumed by different tools and are not interchangeable: JDBC
    // for the service, a libpq URI for psql. Handing psql a `jdbc:` URL does not fail — it reads
    // it as a database NAME, cannot connect, and then waits for a password that will never be
    // typed, so the script that called it hangs with nothing in any log to say why. Found that way.
    Files.writeString(
        target,
        "admin.url="
            + adminUrl
            + "\npsql.uri=postgresql://postgres@localhost:"
            + postgres.getPort()
            + "/postgres"
            + "\nadmin.user=postgres\nadmin.password=\napp.user=bre_ctl_app\napp.password="
            + "devstack-only\n");

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    Files.deleteIfExists(target);
                    postgres.close();
                  } catch (Exception ignored) {
                    // Shutting down. A failure here cannot be acted on and reporting it would
                    // only obscure whatever caused the shutdown.
                  }
                }));

    try (Connection connection = postgres.getPostgresDatabase().getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("SELECT 1");
    }

    System.out.println("embedded PostgreSQL ready: " + adminUrl);
    System.out.println("connection details written to " + target);
    System.out.flush();

    // Block until killed. The script that started this owns its lifetime.
    Thread.currentThread().join();
  }
}
