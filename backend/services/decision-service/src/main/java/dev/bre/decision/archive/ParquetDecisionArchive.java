package dev.bre.decision.archive;

import dev.bre.decision.api.DecisionApi.RuleOutcome;
import dev.bre.decision.log.DecisionEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The WORM decision archive, in Parquet (M4-03, §6.1.5).
 *
 * <p><b>Columnar because of what reads it.</b> A back-test over a year of decisions reads two
 * columns out of fourteen — the outcome and the policy version — and a row format would read all
 * fourteen to get them. The same is true of every analytics question a lender asks of this archive,
 * which is why the specification names Parquet rather than "a file".
 *
 * <p><b>Written by DuckDB, not parquet-avro, and that is a recorded decision.</b> Parquet 1.18's
 * reader is Hadoop-free and its writer is not: the builder constructs a Hadoop configuration, then
 * reaches Hadoop's zlib enums, then Hadoop's XML parser. Taking hadoop-common properly costs 103
 * transitive artifacts — Jetty, Kerberos, Zookeeper — which is a patch obligation and an attack
 * surface in the service that decides credit, for a file format. DuckDB is one artifact with none,
 * writes Parquet that Spark, Athena, pandas and Polars all read, and brings SQL over the archive,
 * which is what a back-test wants anyway.
 *
 * <p><b>Partitioned {@code tenant/date/hour}, rolled by time or size.</b> §6.1.5 says every five
 * minutes or 256 MB. The partitioning is not tidiness: it lets a replay read one hour of one tenant
 * without scanning the archive, and lets a retention purge drop a day by deleting a prefix.
 *
 * <p><b>Written whole, then published once.</b> A Parquet file's schema and row-group index live in
 * its footer, so a file whose footer was never written is not a short file — it is an unreadable
 * one. Writing to a temporary path and publishing with {@link ArchiveStore#putOnce} means a crash
 * leaves nothing in the archive rather than something unreadable, which is the only failure an
 * immutable store cannot clean up after.
 *
 * <p><b>The facts are not archived here, and that is a stated gap rather than a decision.</b>
 * §6.1.5 sizes the archive at about 12.5 KB per decision because it assumes the sealed fact
 * envelopes travel with it. They do not reach this class — {@link DecisionEvent} carries no facts —
 * so the archive holds the decision, its reasons and its provenance, roughly a tenth of that. A
 * replay from the archive can therefore say WHAT was decided and cannot re-run the engine over the
 * original facts. Closing it needs the sealed envelopes on the topic, which needs §9.2 encryption
 * of the payload.
 */
public final class ParquetDecisionArchive implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ParquetDecisionArchive.class);

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter HOUR =
      DateTimeFormatter.ofPattern("HH").withZone(ZoneOffset.UTC);

  /**
   * The archive's columns, in order.
   *
   * <p>Written out rather than derived from {@link DecisionEvent}, for the same reason that type
   * exists: a file written today is read in ten years, and a column order that follows whatever a
   * record's components happen to be is a contract nobody agreed to. Adding a column is
   * backward-compatible; removing or retyping one is not, and has to be a new schema version.
   */
  static final String COLUMNS =
      """
      decision_id VARCHAR NOT NULL, tenant_id VARCHAR NOT NULL, request_id VARCHAR NOT NULL,
      application_ref VARCHAR, recorded_at TIMESTAMP NOT NULL, as_of DATE NOT NULL,
      decision VARCHAR NOT NULL, stp_eligible BOOLEAN NOT NULL, policy_code VARCHAR,
      policy_version INTEGER, bundle_hash VARCHAR, fact_schema VARCHAR NOT NULL,
      latency_micros BIGINT NOT NULL, fuel_used BIGINT NOT NULL, reason_codes VARCHAR
      """;

  private final ArchiveStore store;
  private final Duration rollAfter;
  private final int rollAtRows;
  private final Map<String, Open> open = new HashMap<>();

  /** One partition's buffer. */
  private static final class Open {
    private final List<DecisionEvent> rows = new ArrayList<>();
    private final Instant openedAt;

    Open(Instant openedAt) {
      this.openedAt = openedAt;
    }
  }

  /**
   * Creates the archive.
   *
   * @param store where files are put, write-once
   * @param rollAfter how long a partition stays open — §6.1.5 says five minutes
   * @param rollAtRows how many decisions before rolling; §6.1.5's 256 MB in rows, since the row
   *     size here is known and a byte estimate would only be a worse way of saying the same thing
   */
  public ParquetDecisionArchive(ArchiveStore store, Duration rollAfter, int rollAtRows) {
    this.store = store;
    this.rollAfter = rollAfter;
    this.rollAtRows = rollAtRows;
  }

  /**
   * Buffers one decision, rolling its partition if it is due.
   *
   * <p>Buffered rather than written straight through: a Parquet file of one row is all footer and
   * no benefit, and a file per decision would make the archive slower to read than the row store it
   * supplements.
   *
   * @param event the decision
   * @param now the clock, passed in so a roll is testable without waiting five minutes
   */
  public synchronized void add(DecisionEvent event, Instant now) {
    String partition = partitionOf(event);
    Open buffer = open.computeIfAbsent(partition, key -> new Open(now));
    buffer.rows.add(event);

    if (Duration.between(buffer.openedAt, now).compareTo(rollAfter) >= 0
        || buffer.rows.size() >= rollAtRows) {
      roll(partition, now);
    }
  }

  /**
   * Writes every open partition out.
   *
   * @param now the clock
   */
  public synchronized void flush(Instant now) {
    for (String partition : List.copyOf(open.keySet())) {
      roll(partition, now);
    }
  }

  private void roll(String partition, Instant now) {
    Open buffer = open.remove(partition);
    if (buffer == null || buffer.rows.isEmpty()) {
      return;
    }
    // The name carries the first decision id, so two pods rolling the same partition in the same
    // millisecond produce different names. putOnce refuses a collision rather than overwriting, so
    // even if this were wrong the loss would be loud rather than silent.
    String key =
        partition + "/" + now.toEpochMilli() + "-" + buffer.rows.get(0).decisionId() + ".parquet";

    Path staged = null;
    try {
      staged = Files.createTempFile("bre-archive-", ".parquet");
      Files.delete(staged); // DuckDB writes it.
      writeParquet(staged, buffer.rows);
      // Whole file, then published.
      Path written = staged;
      store.putOnce(key, out -> Files.copy(written, out));
      log.debug("archived {} decisions to {}", buffer.rows.size(), key);
    } catch (IOException | SQLException failed) {
      // Put the rows back. Losing them because a disk was briefly full would leave the archive
      // with a hole, and a reader cannot tell a missing decision from one never made.
      open.put(partition, buffer);
      throw new UncheckedIOException(
          "could not archive partition " + partition,
          failed instanceof IOException io ? io : new IOException(failed));
    } finally {
      if (staged != null) {
        try {
          Files.deleteIfExists(staged);
        } catch (IOException ignored) {
          log.debug("could not remove the staged archive file");
        }
      }
    }
  }

  /** Writes the rows as a Parquet file at the given path. */
  private static void writeParquet(Path target, List<DecisionEvent> rows) throws SQLException {
    // In-memory DuckDB: the database is a staging area for one file and is discarded with the
    // connection. Nothing here is a persistent store.
    try (Connection db = DriverManager.getConnection("jdbc:duckdb:");
        Statement ddl = db.createStatement()) {
      ddl.execute("CREATE TABLE decisions (" + COLUMNS + ")");

      try (PreparedStatement insert =
          db.prepareStatement(
              "INSERT INTO decisions VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
        for (DecisionEvent event : rows) {
          int i = 1;
          insert.setString(i++, event.decisionId());
          insert.setString(i++, event.tenantId());
          insert.setString(i++, event.requestId());
          insert.setString(i++, event.applicationRef());
          insert.setTimestamp(i++, java.sql.Timestamp.from(event.recordedAt()));
          insert.setObject(i++, event.response().asOf());
          insert.setString(i++, event.response().decision());
          insert.setBoolean(i++, event.response().stpEligible());
          insert.setString(i++, event.policyCode());
          insert.setObject(i++, event.policyVersion());
          insert.setString(i++, event.bundleHash());
          insert.setString(i++, event.factSchema());
          insert.setLong(i++, event.latencyMicros());
          insert.setLong(i++, event.fuelUsed());
          insert.setString(i, reasonCodesOf(event));
          insert.addBatch();
        }
        insert.executeBatch();
      }

      // ZSTD: the header columns compress well, and §6.1.5's sizing already assumes it.
      try (PreparedStatement copy =
          db.prepareStatement("COPY decisions TO ? (FORMAT PARQUET, COMPRESSION ZSTD)")) {
        copy.setString(1, target.toString());
        copy.execute();
      }
    }
  }

  /** The distinct reason codes, comma separated. */
  private static String reasonCodesOf(DecisionEvent event) {
    return String.join(
        ",",
        event.response().results().stream()
            .map(RuleOutcome::reasonCode)
            .filter(Objects::nonNull)
            .distinct()
            .toList());
  }

  private static String partitionOf(DecisionEvent event) {
    Instant at = event.recordedAt();
    return event.tenantId() + "/" + DATE.format(at) + "/" + HOUR.format(at);
  }

  @Override
  public void close() {
    flush(Instant.now());
  }
}
