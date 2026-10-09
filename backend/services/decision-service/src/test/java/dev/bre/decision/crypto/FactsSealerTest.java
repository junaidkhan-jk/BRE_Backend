package dev.bre.decision.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bre.crypto.CryptoException;
import dev.bre.crypto.InMemoryKeyStores;
import dev.bre.crypto.LocalDevKms;
import dev.bre.crypto.PrincipalKeyService;
import dev.bre.crypto.TenantDataKeyProvider;
import dev.bre.facts.LendingFacts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The §9.2 pipeline end to end: seal, open, and what erasure does to it (§9.5). */
class FactsSealerTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Instant AT = Instant.parse("2026-10-09T12:30:00Z");
  private static final String TENANT = "t_acme";
  private static final String DECISION = "0199aa00-0000-7000-8000-000000000001";

  private static final String FACTS =
      """
      { "application": { "requestedAmount": "150000.00", "tenureMonths": 24 },
        "parties": [
          { "partyRef": "P-0001", "principalRef": "PR-alice", "dateOfBirth": "1992-03-04",
            "gender": "F", "income": { "monthlyNet": "14000.00", "verified": true } },
          { "partyRef": "P-0002", "principalRef": "PR-bob", "dateOfBirth": "1988-11-20",
            "gender": "M", "income": { "monthlyNet": "11000.00", "verified": true } }
        ],
        "group": { "type": "JLG", "cycleNumber": 2, "villageRiskCategory": "A" } }
      """;

  private record Fixture(FactsSealer sealer, PrincipalKeyService principalKeys) {}

  private static Fixture fixture() {
    LocalDevKms kms = new LocalDevKms();
    var principalKeys = new PrincipalKeyService(kms, InMemoryKeyStores.principalKeys());
    byte[] root = "a-fixed-test-root-secret".getBytes(StandardCharsets.UTF_8);
    FactsSealer sealer =
        new FactsSealer(
            new TenantDataKeyProvider(kms, InMemoryKeyStores.tenantKeys()),
            principalKeys,
            tenantId -> root,
            LendingFacts.schema());
    return new Fixture(sealer, principalKeys);
  }

  private static JsonNode facts() throws IOException {
    return JSON.readTree(FACTS);
  }

  @Test
  @DisplayName("a snapshot seals, opens, and comes back with its integrity verified")
  void roundTrip() throws IOException {
    Fixture f = fixture();
    FactsSealer.Sealed sealed = f.sealer().seal(TENANT, DECISION, facts(), AT);

    assertThat(sealed.factsKid()).isEqualTo("tdk/t_acme/2026100912/1");
    assertThat(sealed.principalEnvelopes())
        .as("one envelope per party, so one can be lost without losing the others")
        .containsOnlyKeys("P-0001", "P-0002");
    assertThat(sealed.factsHmac()).hasSize(64);

    FactsSealer.Opened opened = open(f, sealed);

    assertThat(opened.integrityVerified()).isTrue();
    assertThat(opened.shreddedParties()).isEmpty();
    JsonNode alice = opened.facts().get("parties").get(0);
    assertThat(alice.get("dateOfBirth").asText()).isEqualTo("1992-03-04");
    assertThat(alice.get("income").get("monthlyNet").asText()).isEqualTo("14000.00");
    assertThat(alice.get("income").get("verified").asBoolean()).isTrue();
    assertThat(opened.facts().get("group").get("villageRiskCategory").asText()).isEqualTo("A");
  }

  @Test
  @DisplayName("the sealed bytes do not contain the applicant's data")
  void sealedBytesRevealNothing() throws IOException {
    FactsSealer.Sealed sealed = fixture().sealer().seal(TENANT, DECISION, facts(), AT);
    String nonIdentifying = new String(sealed.factsEnvelope(), StandardCharsets.ISO_8859_1);
    String identifying = String.join("", sealed.principalEnvelopes().values());

    assertThat(nonIdentifying).doesNotContain("1992-03-04", "150000");
    assertThat(identifying).doesNotContain("1992-03-04");
  }

  @Test
  @DisplayName("erasing one member leaves the other members and the decision intact")
  void erasureIsTargeted() throws IOException {
    // §9.5's whole promise, and the reason for two envelopes rather than one: the lending decision
    // must survive — a lender is required to retain it — while one person's identity does not.
    Fixture f = fixture();
    FactsSealer.Sealed sealed = f.sealer().seal(TENANT, DECISION, facts(), AT);

    assertThat(f.principalKeys().shred(TENANT, "PR-alice")).isTrue();

    FactsSealer.Opened opened = open(f, sealed);

    assertThat(opened.shreddedParties()).containsExactly("P-0001");
    JsonNode alice = opened.facts().get("parties").get(0);
    assertThat(alice.has("dateOfBirth")).isFalse();
    assertThat(alice.get("partyRef").asText())
        .as("the party is still there and still identified within the application")
        .isEqualTo("P-0001");

    JsonNode bob = opened.facts().get("parties").get(1);
    assertThat(bob.get("dateOfBirth").asText()).isEqualTo("1988-11-20");
    assertThat(opened.facts().get("application").get("requestedAmount").asText())
        .as("the decision's own data is untouched")
        .isEqualTo("150000.00");

    assertThat(opened.integrityVerified())
        .as("the snapshot genuinely is no longer what was decided on; the caller reports SHREDDED")
        .isFalse();
  }

  @Test
  @DisplayName("altered stored bytes are caught by the HMAC, not silently replayed")
  void integrityFailureIsDetected() throws IOException {
    Fixture f = fixture();
    FactsSealer.Sealed sealed = f.sealer().seal(TENANT, DECISION, facts(), AT);

    // The threat is not forgery — the envelope is authenticated. It is that years later these are
    // not the bytes the decision was taken on: a restore from the wrong backup, a migration that
    // re-serialised them. Simulated by re-sealing DIFFERENT facts and keeping the original HMAC.
    JsonNode tampered = facts();
    ((com.fasterxml.jackson.databind.node.ObjectNode) tampered.get("application"))
        .put("requestedAmount", "950000.00");
    FactsSealer.Sealed reSealed = f.sealer().seal(TENANT, DECISION, tampered, AT);

    FactsSealer.Opened opened =
        f.sealer()
            .open(
                TENANT,
                DECISION,
                reSealed.factsEnvelope(),
                reSealed.principalEnvelopes(),
                sealed.factsHmac());

    assertThat(opened.integrityVerified())
        .as("§9.2: a mismatch makes a replay report matched=false with reason INTEGRITY")
        .isFalse();
    assertThat(opened.facts().get("application").get("requestedAmount").asText())
        .isEqualTo("950000.00");
  }

  @Test
  @DisplayName("a trailing zero on an amount does not break the HMAC")
  void canonicalisationNormalisesDecimals() throws IOException {
    // 212500.00 and 212500 are the same amount (§6.3.3 rule 3). Without the facts profile of
    // BRE-JCS, a caller who sends a trailing zero would get a different HMAC for identical facts
    // and every replay of their decisions would report tampering.
    Fixture f = fixture();
    JsonNode withZeros = JSON.readTree("{\"application\":{\"requestedAmount\":\"212500.00\"}}");
    JsonNode without = JSON.readTree("{\"application\":{\"requestedAmount\":\"212500\"}}");

    assertThat(f.sealer().seal(TENANT, DECISION, withZeros, AT).factsHmac())
        .isEqualTo(f.sealer().seal(TENANT, DECISION, without, AT).factsHmac());
  }

  @Test
  @DisplayName("one decision's envelope cannot be opened as another's")
  void envelopesAreBoundToTheirDecision() throws IOException {
    Fixture f = fixture();
    FactsSealer.Sealed sealed = f.sealer().seal(TENANT, DECISION, facts(), AT);

    assertThatThrownBy(
            () ->
                f.sealer()
                    .open(
                        TENANT,
                        "0199aa00-0000-7000-8000-000000000002",
                        sealed.factsEnvelope(),
                        sealed.principalEnvelopes(),
                        sealed.factsHmac()))
        .isInstanceOf(CryptoException.class)
        .satisfies(
            e ->
                assertThat(((CryptoException) e).reason())
                    .isEqualTo(CryptoException.Reason.NOT_AUTHENTIC));
  }

  @Test
  @DisplayName("one tenant's envelope cannot be opened as another's (D1)")
  void envelopesAreBoundToTheirTenant() throws IOException {
    Fixture f = fixture();
    FactsSealer.Sealed sealed = f.sealer().seal(TENANT, DECISION, facts(), AT);

    assertThatThrownBy(
            () ->
                f.sealer()
                    .open(
                        "t_other",
                        DECISION,
                        sealed.factsEnvelope(),
                        sealed.principalEnvelopes(),
                        sealed.factsHmac()))
        .isInstanceOf(CryptoException.class);
  }

  @Test
  @DisplayName("identifying fields with no party are reported, and not stored anywhere")
  void unassignableFieldsAreReported() throws IOException {
    // application.applicationRef is tagged identifying and belongs to no party, so there is no DPK
    // to key it to. Storing it under the tenant key would make it unerasable; it is dropped, and
    // the drop is reported so it cannot pass unnoticed.
    Fixture f = fixture();
    JsonNode withAppRef =
        JSON.readTree(
            "{\"application\":{\"applicationRef\":\"APP-1\",\"requestedAmount\":\"1000.00\"}}");
    FactsSealer.Sealed sealed = f.sealer().seal(TENANT, DECISION, withAppRef, AT);

    assertThat(sealed.excludedPaths()).containsExactly("application.applicationRef");

    FactsSealer.Opened opened = open(f, sealed);
    assertThat(opened.facts().get("application").has("applicationRef")).isFalse();
    assertThat(opened.facts().get("application").get("requestedAmount").asText())
        .isEqualTo("1000.00");
  }

  @Test
  @DisplayName("a snapshot sealed in one hour opens after the key has rolled over")
  void opensAcrossAKeyRollover() throws IOException {
    Fixture f = fixture();
    FactsSealer.Sealed sealed = f.sealer().seal(TENANT, DECISION, facts(), AT);

    // An hour later the service seals under a new TDK. The old snapshot must still open — the
    // envelope names its own key, and that is why the kid is inside it.
    f.sealer().seal(TENANT, "other", facts(), AT.plusSeconds(3600));

    assertThat(open(f, sealed).integrityVerified()).isTrue();
  }

  private static FactsSealer.Opened open(Fixture f, FactsSealer.Sealed sealed) {
    Map<String, String> envelopes = new HashMap<>(sealed.principalEnvelopes());
    return f.sealer().open(TENANT, DECISION, sealed.factsEnvelope(), envelopes, sealed.factsHmac());
  }
}
