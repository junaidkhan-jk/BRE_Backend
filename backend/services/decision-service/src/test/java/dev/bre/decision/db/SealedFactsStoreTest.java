package dev.bre.decision.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bre.common.UuidV7;
import dev.bre.crypto.InMemoryKeyStores;
import dev.bre.crypto.LocalDevKms;
import dev.bre.crypto.PrincipalKeyService;
import dev.bre.crypto.TenantDataKeyProvider;
import dev.bre.decision.api.DecisionApi.DecisionResponse;
import dev.bre.decision.crypto.FactsSealer;
import dev.bre.decision.log.DecisionLog;
import dev.bre.decision.log.DecisionRecord;
import dev.bre.decision.log.OutboxDecisionLog;
import dev.bre.facts.LendingFacts;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Sealed fact snapshots, written to and read from a real PostgreSQL (M2-06).
 *
 * <p>Against embedded PostgreSQL rather than a mock, for the reason this project has already been
 * caught by once: the decision-log SQL was written, reviewed and believed correct for weeks, and
 * the first run against an actual database found a table with no row-level security and a wrong
 * identifier pattern. Sealed bytes in a {@code bytea} and a base64 map in a {@code jsonb} are
 * exactly the sort of thing that round-trips in a unit test and does not survive a driver.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SealedFactsStoreTest {

  // findAndRegisterModules, because the record carries a LocalDate and a bare ObjectMapper cannot
  // write one. Spring supplies a configured mapper in the application; a test that constructs its
  // own has to do the same thing, or it fails on serialisation rather than on anything it is about.
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private static final String TENANT = "t_acme";

  private static final String FACTS =
      """
      { "application": { "requestedAmount": "150000.00", "tenureMonths": 24 },
        "parties": [
          { "partyRef": "P-0001", "principalRef": "PR-alice", "dateOfBirth": "1992-03-04",
            "gender": "F", "income": { "monthlyNet": "14000.00", "verified": true } },
          { "partyRef": "P-0002", "principalRef": "PR-bob", "dateOfBirth": "1988-11-20",
            "gender": "M", "income": { "monthlyNet": "11000.00", "verified": true } }
        ],
        "group": { "type": "JLG", "cycleNumber": 2 } }
      """;

  private EmbeddedPostgres postgres;
  private DataSource dataSource;
  private PrincipalKeyService principalKeys;
  private OutboxDecisionLog log;

  @BeforeAll
  void startPostgres() throws Exception {
    postgres = EmbeddedPostgres.builder().start();
    dataSource = postgres.getPostgresDatabase();
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

    LocalDevKms kms = new LocalDevKms();
    principalKeys = new PrincipalKeyService(kms, InMemoryKeyStores.principalKeys());
    byte[] root = "a-fixed-test-root-secret".getBytes(StandardCharsets.UTF_8);
    FactsSealer sealer =
        new FactsSealer(
            new TenantDataKeyProvider(kms, InMemoryKeyStores.tenantKeys()),
            principalKeys,
            tenantId -> root,
            LendingFacts.schema());

    log =
        new OutboxDecisionLog(
            new JdbcTemplate(dataSource), JSON, "0.1.0-TEST", "TEST", true, sealer);
  }

  @AfterAll
  void stopPostgres() throws Exception {
    if (postgres != null) {
      postgres.close();
    }
  }

  @Test
  @DisplayName("a sealed snapshot survives a round trip through PostgreSQL")
  void roundTripThroughPostgres() {
    DecisionRecord record = record("REQ-SEAL-1");
    log.append(record);

    Optional<DecisionLog.StoredFacts> read = log.facts(TENANT, record.decisionId());

    assertThat(read).isPresent();
    assertThat(read.orElseThrow().integrityVerified())
        .as("the HMAC must verify after a trip through bytea and jsonb")
        .isTrue();
    assertThat(read.orElseThrow().shreddedParties()).isEmpty();
    assertThat(read.orElseThrow().facts().get("parties").get(0).get("dateOfBirth").asText())
        .isEqualTo("1992-03-04");
    assertThat(read.orElseThrow().facts().get("application").get("requestedAmount").asText())
        .isEqualTo("150000.00");
  }

  @Test
  @DisplayName("the stored row holds no readable personal data")
  void theStoredRowIsOpaque() throws Exception {
    DecisionRecord record = record("REQ-SEAL-2");
    log.append(record);

    // Read as the database sees it: bytes and base64, with no key in sight.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                """
                SELECT facts_kid, facts_hmac,
                       encode(facts_envelope, 'escape') AS envelope,
                       principal_envelopes::text       AS principals
                  FROM bre_dec.decision_facts
                """)) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("facts_kid")).startsWith("tdk/t_acme/");
      assertThat(rs.getString("facts_hmac")).hasSize(64);
      assertThat(rs.getString("envelope")).doesNotContain("150000", "1992-03-04");
      assertThat(rs.getString("principals"))
          .as("one entry per party, each opaque")
          .contains("P-0001", "P-0002")
          .doesNotContain("1992-03-04");
    }
  }

  @Test
  @DisplayName("erasing a principal makes their stored fields unreadable, and nobody else's")
  void erasureAffectsOnlyThatPrincipal() {
    DecisionRecord record = record("REQ-SEAL-3");
    log.append(record);

    assertThat(principalKeys.shred(TENANT, "PR-alice")).isTrue();

    DecisionLog.StoredFacts read = log.facts(TENANT, record.decisionId()).orElseThrow();

    assertThat(read.shreddedParties()).containsExactly("P-0001");
    assertThat(read.facts().get("parties").get(0).has("dateOfBirth")).isFalse();
    assertThat(read.facts().get("parties").get(1).get("dateOfBirth").asText())
        .as("§9.5 is targeted: one person, not one decision")
        .isEqualTo("1988-11-20");
    assertThat(read.facts().get("application").get("requestedAmount").asText())
        .as("the lending decision itself must survive — a lender is required to retain it")
        .isEqualTo("150000.00");
  }

  @Test
  @DisplayName("a read gives back the bundle hash, so the decision can be replayed")
  void readBackCarriesTheBundleHash() {
    DecisionRecord record = record("REQ-SEAL-5");
    log.append(record);

    // The regression this exists for. The read query selected everything a response needs and NOT
    // bundle_hash, and readBack defaulted it to empty — so every explanation against PostgreSQL
    // threw "has no bundleHash" for every decision, from the day the endpoint shipped. The
    // in-memory log returns whole records, so nothing in the suite noticed.
    assertThat(log.find(TENANT, record.decisionId()).orElseThrow().bundleHash())
        .as("a replay pins to the bundle on the RECORD; without it there is nothing to pin to")
        .contains("sha256:" + "0".repeat(64));

    assertThat(log.findByRequestId(TENANT, "REQ-SEAL-5").orElseThrow().bundleHash())
        .as("the idempotent path reads the same row and must answer the same")
        .contains("sha256:" + "0".repeat(64));
  }

  @Test
  @DisplayName("a decision taken with no bundle reads back as having none, not as 'UNKNOWN'")
  void theUnknownSentinelDoesNotLeakOut() {
    DecisionRecord record = recordWithoutBundle("REQ-SEAL-6");
    log.append(record);

    // bundle_hash is NOT NULL, so the write substitutes a sentinel. Handing that back as if it were
    // a content address would send a replay looking for a bundle called UNKNOWN and fail inside the
    // cache, instead of saying plainly that this decision cannot be pinned.
    assertThat(log.find(TENANT, record.decisionId()).orElseThrow().bundleHash()).isEmpty();
  }

  @Test
  @DisplayName("the facts row is keyed to one tenant (D1)")
  void anotherTenantSeesNothing() {
    DecisionRecord record = record("REQ-SEAL-4");
    log.append(record);
    assertThat(log.facts("t_other", record.decisionId())).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------

  private static DecisionRecord recordWithoutBundle(String requestId) {
    return withBundle(requestId, Optional.empty());
  }

  private static DecisionRecord record(String requestId) {
    return withBundle(requestId, Optional.of("sha256:" + "0".repeat(64)));
  }

  private static DecisionRecord withBundle(String requestId, Optional<String> bundleHash) {
    Instant at = Instant.parse("2026-10-09T12:30:00Z");
    String decisionId = UuidV7.generate(at).toString();
    DecisionResponse response =
        new DecisionResponse(
            decisionId, TENANT, requestId, LocalDate.of(2026, 10, 1), "APPROVE", true, List.of());
    return new DecisionRecord(
        decisionId,
        TENANT,
        requestId,
        Optional.of("APP-1"),
        at,
        at.plusMillis(3),
        3000L,
        "Asia/Kolkata",
        0L,
        Optional.of("MFI_JLG_CREDIT_UW"),
        Optional.of(1),
        bundleHash,
        "lending-facts@1.0.0",
        response,
        FACTS);
  }
}
