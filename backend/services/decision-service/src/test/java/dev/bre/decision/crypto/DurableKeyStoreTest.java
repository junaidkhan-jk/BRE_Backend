package dev.bre.decision.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.bre.crypto.LocalDevKms;
import dev.bre.crypto.PrincipalKeyService;
import dev.bre.crypto.TenantDataKeyProvider;
import dev.bre.decision.api.DecisionApi.DecisionResponse;
import dev.bre.decision.log.DecisionRecord;
import dev.bre.decision.log.OutboxDecisionLog;
import dev.bre.facts.LendingFacts;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.charset.StandardCharsets;
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
 * Keys that survive the process, and an erasure that erases (§9.2, §9.5, M4-04).
 *
 * <p>The defect this exists for is not a crash. Before M4-04 the key stores were {@code HashMap}s
 * in production as well as in tests, so every sealed fact row became permanently unreadable on
 * restart — silently, irreversibly, and discovered the first time somebody replayed a decision. A
 * test that seals and reads back inside one process cannot see that, which is why every assertion
 * below goes through a <b>second</b> set of objects over the same database: the stand-in for a
 * restart.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DurableKeyStoreTest {

  private static final String TENANT = "t_keys";
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private static final String FACTS =
      """
      { "application": { "applicationRef": "APP-77", "requestedAmount": "60000.00" },
        "parties": [ { "partyRef": "P1", "panToken": "tok_A1B2C3D4E5F6G7H8",
                       "dateOfBirth": "1990-04-02", "gender": "F",
                       "informalNickname": "Lakshmi" } ] }
      """;

  private static EmbeddedPostgres postgres;
  private static DataSource dataSource;

  /**
   * ONE KMS across every "restart" below, and that is not a shortcut.
   *
   * <p>Each {@code new LocalDevKms()} generates its own key-encryption keys, so a second instance
   * cannot unwrap what the first wrapped — which is precisely what a real KMS does NOT do, and
   * precisely what the dev KMS already warns about at startup: its KEKs live in process memory and
   * die with the process.
   *
   * <p>So this test's model is the production one: the WRAPPED key is in the database and survives
   * the pod; the KEK is in the KMS, which is a different system that also survives. Sharing the
   * instance stands in for that second system. It is worth being explicit that durable key storage
   * is necessary and NOT sufficient — with the dev KMS a restart still loses everything, and the
   * table added in V7 does not change that.
   */
  private static final LocalDevKms KMS = new LocalDevKms();

  @BeforeAll
  void start() throws Exception {
    postgres = EmbeddedPostgres.builder().start();
    dataSource = postgres.getPostgresDatabase();
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
  }

  @AfterAll
  void stop() throws Exception {
    if (postgres != null) {
      postgres.close();
    }
  }

  @Test
  @DisplayName("a fact snapshot is still readable after a restart")
  void keysOutliveTheProcess() {
    DecisionRecord record = record("REQ-RESTART");
    log().append(record);

    // A SECOND set of key objects over the same database — a new provider, a new key service, a
    // new in-process cache. This is the restart, and it is the whole test: with the HashMap stores
    // the keys would be gone here and the snapshot unreadable for ever.
    Optional<dev.bre.decision.log.DecisionLog.StoredFacts> afterRestart =
        log().facts(TENANT, record.decisionId());

    assertThat(afterRestart).isPresent();
    assertThat(afterRestart.get().integrityVerified())
        .as("the stored bytes are the bytes the decision was taken on (§9.2)")
        .isTrue();
    assertThat(afterRestart.get().facts().get("parties").get(0).get("panToken").asText())
        .isEqualTo("tok_A1B2C3D4E5F6G7H8");
    assertThat(afterRestart.get().shreddedParties()).isEmpty();
  }

  @Test
  @DisplayName("two pods sealing for the same principal at once end up under ONE key")
  void concurrentSealsShareAKey() {
    // Insert-or-get in one statement, not read-then-write. With a read-then-write two pods get
    // two keys, the second overwrites the first, and every envelope under the first becomes
    // unreadable — the same loss as the HashMap with a narrower window, and so much harder to find.
    PrincipalKeyService first = principalKeys();
    PrincipalKeyService second = principalKeys();

    String keyIdA = first.forWriting(TENANT, "P-RACE").kid();
    String keyIdB = second.forWriting(TENANT, "P-RACE").kid();
    assertThat(keyIdA).isEqualTo(keyIdB);

    assertThat(first.forPrincipal(TENANT, "P-RACE"))
        .isPresent()
        .get()
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.BYTE_ARRAY)
        .isEqualTo(second.forPrincipal(TENANT, "P-RACE").orElseThrow());
  }

  @Test
  @DisplayName("erasure destroys the key: the person is gone, the decision remains")
  void erasureUnreadsTheFactsAndKeepsTheDecision() {
    DecisionRecord record = record("REQ-ERASE");
    log().append(record);
    assertThat(log().facts(TENANT, record.decisionId())).isPresent();

    boolean erased = principalKeys().shred(TENANT, "P1");
    assertThat(erased).isTrue();

    // What a regulator still gets: the decision, its outcome, its date, its policy version.
    Optional<DecisionRecord> decision = log().find(TENANT, record.decisionId());
    assertThat(decision).isPresent();
    assertThat(decision.get().response().decision()).isEqualTo("APPROVE");

    // What is gone: who it was about. The party is reported as shredded rather than the read
    // failing — an erased principal is an expected state, not corruption, and the two must not
    // look alike to an operator.
    Optional<dev.bre.decision.log.DecisionLog.StoredFacts> facts =
        log().facts(TENANT, record.decisionId());
    assertThat(facts).isPresent();
    assertThat(facts.get().shreddedParties()).contains("P1");
    assertThat(facts.get().facts().toString())
        .as("no identifying field of an erased principal survives anywhere in the snapshot")
        .doesNotContain("tok_A1B2C3D4E5F6G7H8");

    // The field NOBODY DECLARED, and the reason this assertion exists.
    //
    // The schema sets additionalProperties: true, so a caller can send a field the schema has
    // never heard of — and the obvious way is a near miss on a real one: the PAN field is
    // panToken, and "pan" passes validation. FieldGroupSplitter used to treat an unknown field as
    // NON_IDENTIFYING, which sealed it under the TENANT key, outside the erasure mechanism
    // entirely. Destroying the principal's key then left it perfectly readable while the party was
    // reported as shredded and the lender believed the person was erased. Fail-open, on the one
    // operation that cannot be repeated once it is believed done.
    assertThat(facts.get().facts().toString())
        .as("an undeclared field is sealed under the principal's key too, or erasure is a lie")
        .doesNotContain("Lakshmi");

    // Irreversible, and idempotent. A second erasure succeeds and reports that nothing was there,
    // because "never had a key" and "already erased" must be the same answer — distinguishing them
    // would make this an oracle for whether somebody was ever assessed.
    assertThat(principalKeys().shred(TENANT, "P1")).isFalse();
    assertThat(log().facts(TENANT, record.decisionId()).orElseThrow().shreddedParties())
        .contains("P1");
  }

  @Test
  @DisplayName("only wrapped key material is in the database")
  void theDatabaseHoldsNoPlaintextKey() {
    log().append(record("REQ-WRAPPED"));
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    // The property the whole envelope design exists for: a dump of this schema — a backup tape, a
    // replica, a developer's copy — is worth nothing without the KMS. Asserted by checking that
    // every stored key is accompanied by the KEK that wraps it, and that none of them is the
    // 32-byte plaintext an AES key would be.
    List<Integer> tenantKeyLengths =
        jdbc.queryForList("SELECT length(wrapped) FROM bre_dec.tenant_data_keys", Integer.class);
    assertThat(tenantKeyLengths).isNotEmpty();
    assertThat(tenantKeyLengths)
        .as("a wrapped AES-256 key is longer than the 32 raw bytes, because of the nonce and tag")
        .allSatisfy(length -> assertThat(length).isGreaterThan(32));

    assertThat(jdbc.queryForList("SELECT kek_kid FROM bre_dec.tenant_data_keys", String.class))
        .allSatisfy(kek -> assertThat(kek).isNotBlank());
    assertThat(jdbc.queryForList("SELECT pkwk_kid FROM bre_dec.principal_data_keys", String.class))
        .isNotEmpty()
        .allSatisfy(pkwk -> assertThat(pkwk).isNotBlank());
  }

  // --- a fresh set of objects over the same database, every time: the restart ---------------

  private static OutboxDecisionLog log() {
    FactsSealer sealer =
        new FactsSealer(tenantKeys(), principalKeys(), tenantId -> root(), LendingFacts.schema());
    return new OutboxDecisionLog(
        new JdbcTemplate(dataSource), JSON, "0.1.0-TEST", "TEST", true, sealer);
  }

  private static TenantDataKeyProvider tenantKeys() {
    return new TenantDataKeyProvider(KMS, JdbcKeyStores.tenantKeys(new JdbcTemplate(dataSource)));
  }

  private static PrincipalKeyService principalKeys() {
    return new PrincipalKeyService(KMS, JdbcKeyStores.principalKeys(new JdbcTemplate(dataSource)));
  }

  private static byte[] root() {
    // Fixed, so the integrity HMAC verifies across the "restart". In a deployment this comes from
    // the configured root secret, which is the same value for the life of the installation.
    return "a-fixed-test-root-secret".getBytes(StandardCharsets.UTF_8);
  }

  private static DecisionRecord record(String requestId) {
    Instant at = Instant.parse("2026-10-16T09:00:00Z");
    String decisionId = dev.bre.common.UuidV7.generate(at).toString();
    DecisionResponse response =
        new DecisionResponse(
            decisionId, TENANT, requestId, LocalDate.of(2026, 10, 1), "APPROVE", true, List.of());
    return new DecisionRecord(
        decisionId,
        TENANT,
        requestId,
        Optional.of("APP-77"),
        at,
        at.plusMillis(3),
        3000L,
        "Asia/Kolkata",
        0L,
        Optional.of("MFI_JLG_CREDIT_UW"),
        Optional.of(1),
        Optional.of("sha256:" + "0".repeat(64)),
        "lending-facts@1.0.0",
        response,
        FACTS);
  }
}
