package dev.bre.decision.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.bre.bundle.Bundle;
import dev.bre.bundle.BundleSigner;
import dev.bre.bundle.BundleVerifier;
import dev.bre.bundle.TrustStore;
import dev.bre.common.UuidV7;
import dev.bre.crypto.InMemoryKeyStores;
import dev.bre.crypto.LocalDevKms;
import dev.bre.crypto.PrincipalKeyService;
import dev.bre.crypto.TenantDataKeyProvider;
import dev.bre.decision.api.DecisionApi.DecisionResponse;
import dev.bre.decision.api.DecisionApi.RuleOutcome;
import dev.bre.decision.crypto.FactsSealer;
import dev.bre.decision.explain.ReplayService;
import dev.bre.decision.log.DecisionRecord;
import dev.bre.decision.log.OutboxDecisionLog;
import dev.bre.decision.policy.BundleCache;
import dev.bre.decision.policy.FileBundleStore;
import dev.bre.facts.LendingFacts;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The portfolio back-test, over a real hot store (M5-05).
 *
 * <p>Against embedded PostgreSQL rather than the in-memory log, because the sampling is SQL — a
 * back-test that worked against a map and not against the partitioned table would be discovered by
 * the first risk analyst to run one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackTestJobTest {

  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private static final KeyPair KEY = BundleSigner.generateKeyPair();
  private static final String TENANT = "t_acme";

  @TempDir static Path tmp;

  private static EmbeddedPostgres postgres;
  private static DataSource dataSource;
  private static OutboxDecisionLog log;
  private static BackTestJob job;
  private static String champion;
  private static String candidate;

  @BeforeAll
  void start() throws Exception {
    postgres = EmbeddedPostgres.builder().start();
    dataSource = postgres.getPostgresDatabase();
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

    FileBundleStore store = new FileBundleStore(tmp.resolve("bundles"));
    // The champion allows up to 60,000; the candidate tightens it to 40,000.
    champion = signRuleAt(store, 60000);
    candidate = signRuleAt(store, 40000);

    LocalDevKms kms = new LocalDevKms();
    byte[] root = "a-fixed-test-root-secret".getBytes(StandardCharsets.UTF_8);
    FactsSealer sealer =
        new FactsSealer(
            new TenantDataKeyProvider(kms, InMemoryKeyStores.tenantKeys()),
            new PrincipalKeyService(kms, InMemoryKeyStores.principalKeys()),
            tenantId -> root,
            LendingFacts.schema());

    log =
        new OutboxDecisionLog(
            new JdbcTemplate(dataSource), JSON, "0.1.0-TEST", "TEST", true, sealer);

    BundleCache cache =
        new BundleCache(
            store,
            new BundleVerifier(TrustStore.builder().ownBundleKey("k", KEY.getPublic()).build()));
    job = new BackTestJob(new JdbcTemplate(dataSource), log, new ReplayService(cache, log));

    // Seeded HERE rather than in the first test. Three applications sit between the two cut-offs —
    // approved at 650, declined at 700 — and one is well above both. Seeding inside a test makes
    // every other test depend on it having run first, and JUnit's order is not a contract: the
    // first version of this class seeded in transitionsAreGroupedByCause, and when that test failed
    // the other two failed with it — two false failures hiding the one real defect.
    decided(45000);
    decided(50000);
    decided(55000);
    decided(30000);
  }

  @AfterAll
  void stop() throws Exception {
    if (postgres != null) {
      postgres.close();
    }
  }

  /**
   * A one-rule policy that declines an amount above {@code cap}.
   *
   * <p>A scalar fact and an APPLICATION scope, deliberately. An earlier version read a bureau score
   * out of a collection with {@code member.bureauReports[*].score} — which is not the filter syntax
   * (§3.2 uses {@code [it.field = …]}) and evaluated to something that passed every cut-off, so the
   * back-test reported zero transitions and looked broken. What is under test here is the sampling,
   * the comparison and the grouping; an expression elaborate enough to be wrong makes a failure
   * ambiguous between this class and the engine.
   */
  private static String signRuleAt(FileBundleStore store, int cap) throws IOException {
    ObjectNode ir = JSON.createObjectNode();
    ir.put("irVersion", 1);
    ObjectNode rule = ir.putArray("rules").addObject();
    rule.put("code", "AFF_AMOUNT_CAP")
        .put("name", "AFF_AMOUNT_CAP")
        .put("scope", "APPLICATION")
        .put("check", "application.requestedAmount <= " + cap);
    rule.putObject("onFail").put("status", "FAIL").put("reasonCode", "AFF_OVER_CAP");
    rule.putObject("onMissing").put("policy", "REFER");
    ObjectNode stage = ir.putObject("flow").putArray("stages").addObject();
    stage.put("name", "CREDIT");
    // No `aggregate`: the IR's default is WORST, and it is an OBJECT with a `kind` when present
    // rather than a string. Writing "aggregate": "WORST" fails with "the IR is missing the
    // required field 'kind'", several frames from anything that mentions aggregation.
    stage.putArray("steps").addObject().put("ref", "AFF_AMOUNT_CAP");

    Bundle bundle =
        BundleSigner.sign(
            JSON.createObjectNode(),
            ir,
            JSON.createObjectNode(),
            "k",
            Bundle.Role.ORIGIN,
            KEY.getPrivate());
    store.put(bundle.bundleHash(), bundle.toJson());
    return bundle.bundleHash();
  }

  /** An application for {@code amount}, with the blocks the schema requires. */
  private static String facts(int amount) {
    return """
        { "application": { "requestedAmount": "%d.00", "tenureMonths": 12 },
          "parties": [
            { "partyRef": "P-0001", "principalRef": "PR-one", "dateOfBirth": "1990-01-01" }
          ],
          "group": { "groupRef": "GRP-1", "type": "JLG", "cycleNumber": 2 } }
        """
        .formatted(amount);
  }

  /**
   * Records an APPROVE taken under the champion, with its facts sealed.
   *
   * <p>Always an approve, because every seeded score passes the champion's 650 — a record claiming
   * a decision its own bundle would not reach is a record that cannot be back-tested meaningfully,
   * and would make the transition counts measure the fixture rather than the candidate.
   */
  private static String decided(int amount) {
    Instant at = Instant.parse("2026-10-09T12:30:00Z");
    String id = UuidV7.generate(at).toString();
    DecisionResponse response =
        new DecisionResponse(
            id,
            TENANT,
            "REQ-" + id,
            LocalDate.of(2026, 10, 1),
            "APPROVE",
            true,
            List.of(new RuleOutcome("AFF_AMOUNT_CAP", "PASS", null, null)));
    log.append(
        new DecisionRecord(
            id,
            TENANT,
            "REQ-" + id,
            Optional.of("APP-" + id),
            at,
            at.plusMillis(2),
            2000L,
            "Asia/Kolkata",
            0L,
            Optional.of("MFI_JLG_CREDIT_UW"),
            Optional.of(1),
            Optional.of(champion),
            "lending-facts@1.0.0",
            response,
            facts(amount)));
    return id;
  }

  @Test
  @DisplayName("the candidate's transitions are reported, grouped by cause")
  void transitionsAreGroupedByCause() {
    BackTestJob.Result result = job.run(TENANT, candidate, 100);

    assertThat(result.meaningful()).isTrue();
    assertThat(result.backTested()).isEqualTo(4);
    assertThat(result.changed()).isEqualTo(3);
    assertThat(result.unchanged()).isEqualTo(1);
    assertThat(result.changedPercent()).isEqualTo(75.0);

    // ONE row, not three. A threshold move flips every application near it, and a committee reading
    // a list with one row per application reads nothing.
    assertThat(result.transitions()).hasSize(1);
    BackTestJob.Transition transition = result.transitions().get(0);
    assertThat(transition.from()).isEqualTo("APPROVE");
    assertThat(transition.to()).isEqualTo("DECLINE");
    assertThat(transition.decisions()).isEqualTo(3);
    assertThat(transition.decisiveStep())
        .as("the step that declined, so the committee knows which rule to argue about")
        .isEqualTo("AFF_AMOUNT_CAP");
    assertThat(transition.reasonCode()).isEqualTo("AFF_OVER_CAP");
    assertThat(transition.exampleDecisionIds())
        .as("enough to look one up in the access-controlled record, not a dump")
        .hasSize(3);
  }

  @Test
  @DisplayName("a candidate that changes nothing reports no transitions, and says it checked")
  void anIdenticalCandidateIsHonestAboutIt() {
    BackTestJob.Result result = job.run(TENANT, champion, 100);

    assertThat(result.changed()).isZero();
    assertThat(result.transitions()).isEmpty();
    assertThat(result.meaningful())
        .as("zero transitions out of a real sample is a result; out of none it is not")
        .isTrue();
  }

  @Test
  @DisplayName("no transition carries a fact value (§9.6)")
  void noFactValuesInTheResult() {
    BackTestJob.Result result = job.run(TENANT, candidate, 100);

    // A back-test is the largest concentration of personal data this system produces in one
    // response — thousands of real applications at once — and its output goes to a committee.
    // The score that moved each decision is exactly the field that must not travel with it.
    String rendered = result.transitions().toString();
    assertThat(rendered).doesNotContain("45000").doesNotContain("50000").doesNotContain("55000");
    assertThat(rendered).contains("AFF_AMOUNT_CAP").contains("AFF_OVER_CAP");
  }

  @Test
  @DisplayName("a tenant with no decisions is not reported as 'no impact'")
  void anEmptySampleIsNotAPass() {
    BackTestJob.Result result = job.run("t_nobody", candidate, 100);

    assertThat(result.sampled()).isZero();
    assertThat(result.transitions()).isEmpty();
    // The assertion that matters. "changed: 0" with meaningful() false means NOTHING WAS CHECKED,
    // and an approver reading only the first number would take it for a clean impact assessment.
    assertThat(result.meaningful()).isFalse();
  }
}
