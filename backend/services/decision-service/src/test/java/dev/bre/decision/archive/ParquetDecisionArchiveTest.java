package dev.bre.decision.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bre.decision.api.DecisionApi.DecisionResponse;
import dev.bre.decision.api.DecisionApi.RuleOutcome;
import dev.bre.decision.log.DecisionEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The WORM Parquet archive (M4-03).
 *
 * <p>Reads its own files back with a real Parquet reader rather than asserting that bytes were
 * written. A Parquet file's schema and row-group index live in its FOOTER, so a file that was
 * written but never closed properly is not a short file — it is an unreadable one, and a test that
 * only checks the file exists would pass on every one of them.
 *
 * <p>It also proves the runtime claim behind the build file: Hadoop is a compile-only dependency
 * and is NOT on the test runtime classpath, so if any path here reached real Hadoop this test would
 * fail with NoClassDefFoundError rather than quietly working in development and failing in a
 * deployment.
 */
class ParquetDecisionArchiveTest {

  private static final String TENANT = "t_arch";

  @Test
  @DisplayName("decisions are written as Parquet and read back with their columns")
  void writesAndReadsBack(@TempDir Path root) throws Exception {
    ArchiveStore store = new ArchiveStore.Directory(root);
    Instant at = Instant.parse("2026-10-16T09:15:00Z");

    try (ParquetDecisionArchive archive =
        new ParquetDecisionArchive(store, Duration.ofMinutes(5), 100_000)) {
      archive.add(event("APP-1", "APPROVE", "KYC_OK", at), at);
      archive.add(event("APP-2", "DECLINE", "CR_DPD30", at), at.plusSeconds(10));
      archive.flush(at.plusSeconds(20));
    }

    List<String> keys = store.list(TENANT);
    assertThat(keys).hasSize(1);
    assertThat(keys.get(0))
        .as("partitioned tenant/date/hour, so a replay reads one hour without scanning the archive")
        .startsWith(TENANT + "/2026-10-16/09/");

    List<Map<String, Object>> rows = readAll(store.open(keys.get(0)));
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).get("decision")).isEqualTo("APPROVE");
    assertThat(rows.get(1).get("decision")).isEqualTo("DECLINE");
    assertThat(rows.get(1).get("reason_codes").toString()).contains("CR_DPD30");
    assertThat(rows.get(0).get("policy_code")).isEqualTo("MFI_JLG_CREDIT_UW");
    assertThat(rows.get(0).get("tenant_id")).isEqualTo(TENANT);
  }

  @Test
  @DisplayName("an hour boundary is a new partition, not a bigger file")
  void partitionsByHour(@TempDir Path root) throws IOException {
    ArchiveStore store = new ArchiveStore.Directory(root);
    Instant nine = Instant.parse("2026-10-16T09:59:00Z");
    Instant ten = Instant.parse("2026-10-16T10:01:00Z");

    try (ParquetDecisionArchive archive =
        new ParquetDecisionArchive(store, Duration.ofMinutes(5), 100_000)) {
      archive.add(event("APP-9", "APPROVE", "KYC_OK", nine), nine);
      archive.add(event("APP-10", "APPROVE", "KYC_OK", ten), ten);
      archive.flush(ten);
    }

    // Two files, because a retention purge drops a day or an hour by deleting a prefix — which
    // only works if nothing straddles the boundary.
    assertThat(store.list(TENANT)).hasSize(2);
    assertThat(store.list(TENANT + "/2026-10-16/09")).hasSize(1);
    assertThat(store.list(TENANT + "/2026-10-16/10")).hasSize(1);
  }

  @Test
  @DisplayName("a partition rolls when its time is up, without waiting for a flush")
  void rollsOnTime(@TempDir Path root) throws IOException {
    ArchiveStore store = new ArchiveStore.Directory(root);
    Instant at = Instant.parse("2026-10-16T09:00:00Z");

    ParquetDecisionArchive archive =
        new ParquetDecisionArchive(store, Duration.ofMinutes(5), 100_000);
    archive.add(event("APP-A", "APPROVE", "KYC_OK", at), at);
    assertThat(store.list(TENANT)).as("still open, still buffered").isEmpty();

    // §6.1.5's five minutes. The clock is a parameter precisely so this is a test and not a wait.
    archive.add(event("APP-B", "APPROVE", "KYC_OK", at), at.plusSeconds(301));
    assertThat(store.list(TENANT)).hasSize(1);
  }

  @Test
  @DisplayName("the archive never overwrites: a repeated key is refused, not replaced")
  void writeOnce(@TempDir Path root) throws IOException {
    ArchiveStore store = new ArchiveStore.Directory(root);
    store.putOnce("t/2026-10-16/09/one.parquet", out -> out.write(new byte[] {1, 2, 3}));

    // The property a regulator relies on, enforced in the code rather than left to a bucket
    // setting that can be absent on the day it matters. CREATE_NEW rather than exists-then-write,
    // so two pods racing cannot both believe they won.
    assertThatThrownBy(
            () -> store.putOnce("t/2026-10-16/09/one.parquet", out -> out.write(new byte[] {9})))
        .isInstanceOf(ArchiveStore.AlreadyArchivedException.class)
        .hasMessageContaining("never rewritten");

    assertThat(Files.readAllBytes(store.open("t/2026-10-16/09/one.parquet")))
        .containsExactly(1, 2, 3);
  }

  @Test
  @DisplayName("an archive key cannot escape the archive root")
  void keysStayInsideTheRoot(@TempDir Path root) {
    ArchiveStore store = new ArchiveStore.Directory(root);
    // A key is built from a tenant id. One that reached here unchecked could read or write another
    // tenant's partition, or anything else on the disk (D1).
    assertThatThrownBy(() -> store.putOnce("../escaped", out -> out.write(new byte[] {1})))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("inside the archive root");
  }

  @Test
  @DisplayName("no facts reach the archive")
  void theArchiveHoldsNoFacts(@TempDir Path root) throws IOException {
    // The archive is built from DecisionEvent, which carries no fact snapshot. Asserted rather
    // than assumed because the archive is the longest-lived copy of anything this system writes:
    // a mistake here is personal data on write-once storage for ten years, which is the one
    // mistake that cannot be corrected afterwards.
    assertThat(ParquetDecisionArchive.COLUMNS).doesNotContain("facts").doesNotContain("parties");
  }

  // --- plumbing --------------------------------------------------------------------------------

  /**
   * Reads the archived file back as real Parquet.
   *
   * <p>Through {@code read_parquet}, which is how anything else will read it too — the point of
   * choosing a standard format over an internal one is that the reader is not ours. If this file
   * were not valid Parquet, this query would fail rather than quietly return the rows a bespoke
   * reader happened to understand.
   */
  private static List<Map<String, Object>> readAll(Path file) throws Exception {
    List<Map<String, Object>> rows = new ArrayList<>();
    try (java.sql.Connection db = java.sql.DriverManager.getConnection("jdbc:duckdb:");
        java.sql.PreparedStatement query =
            db.prepareStatement("SELECT * FROM read_parquet(?) ORDER BY recorded_at, request_id")) {
      query.setString(1, file.toString());
      try (java.sql.ResultSet rs = query.executeQuery()) {
        while (rs.next()) {
          Map<String, Object> row = new java.util.LinkedHashMap<>();
          for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
            row.put(rs.getMetaData().getColumnName(i), rs.getObject(i));
          }
          rows.add(row);
        }
      }
    }
    return rows;
  }

  private static DecisionEvent event(
      String applicationRef, String outcome, String reasonCode, Instant at) {
    String id = dev.bre.common.UuidV7.generate(at).toString();
    DecisionResponse response =
        new DecisionResponse(
            id,
            TENANT,
            "req-" + applicationRef,
            LocalDate.of(2026, 10, 1),
            outcome,
            false,
            List.of(
                new RuleOutcome(
                    "RULE_1", "APPROVE".equals(outcome) ? "PASS" : "FAIL", reasonCode, null)));
    return new DecisionEvent(
        id,
        TENANT,
        "req-" + applicationRef,
        applicationRef,
        at,
        at,
        1200L,
        "Asia/Kolkata",
        42L,
        "MFI_JLG_CREDIT_UW",
        1,
        "sha256:" + "b".repeat(64),
        "lending-facts@1.0.0",
        response);
  }
}
