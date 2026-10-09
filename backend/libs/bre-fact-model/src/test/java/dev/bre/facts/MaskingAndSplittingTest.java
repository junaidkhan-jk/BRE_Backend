package dev.bre.facts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The masked view (§9.4) and the identifying/non-identifying split (§9.2). */
class MaskingAndSplittingTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final FactSchema SCHEMA = LendingFacts.schema();
  private static final LocalDate AS_OF = LocalDate.of(2026, 10, 1);

  private static final String FACTS =
      """
      { "application": { "applicationRef": "APP-1", "requestedAmount": "150000.00",
                         "branchCode": "0012" },
        "parties": [
          { "partyRef": "P-0001", "principalRef": "PR-alice", "dateOfBirth": "1992-03-04",
            "gender": "F", "panToken": "tok_9f8e7d6c5b4a3210",
            "income": { "monthlyNet": "14000.00", "verified": true },
            "bureauReports": [ { "bureau": "HIGHMARK", "score": 720,
                                 "tradelines": [ { "currentBalance": "18000.00",
                                                   "dpdHistory": [ { "month": "2026-09-01",
                                                                     "dpd": 0 } ] } ] } ] },
          { "partyRef": "P-0002", "principalRef": "PR-bob", "dateOfBirth": "1968-07-19",
            "gender": "M", "income": { "monthlyNet": "9000.00" } }
        ],
        "group": { "groupRef": "G-1", "type": "JLG", "cycleNumber": 2 },
        "households": [ { "householdRef": "H-1", "annualIncome": "180000.00" } ] }
      """;

  private static JsonNode facts() throws IOException {
    return JSON.readTree(FACTS);
  }

  // --- masking ---------------------------------------------------------------------------

  @Test
  @DisplayName("each of the six rules does what §9.4 says")
  void eachRuleApplies() throws IOException {
    JsonNode masked = new Masker(SCHEMA).mask(facts(), AS_OF);
    JsonNode alice = masked.get("parties").get(0);

    assertThat(alice.get("dateOfBirth").asText())
        .as("AGE_BAND: born 1992, so 34 at asOf")
        .isEqualTo("26-35");
    assertThat(alice.get("panToken").asText())
        .as("TOKEN_TAIL4 keeps the type prefix and the last four")
        .isEqualTo("tok_…3210");
    assertThat(alice.get("income").get("monthlyNet").asText()).as("ROUND_1000").isEqualTo("14000");
    assertThat(
            alice
                .get("bureauReports")
                .get(0)
                .get("tradelines")
                .get(0)
                .get("dpdHistory")
                .get(0)
                .get("month")
                .asText())
        .as("MONTH_ONLY")
        .isEqualTo("2026-09");
    assertThat(masked.get("group").get("type").asText())
        .as("NONE: a product type is not personal data")
        .isEqualTo("JLG");
  }

  @Test
  @DisplayName("the age band is taken at asOf, not today")
  void ageBandUsesAsOf() {
    // A masked view of a decision must show the age the decision was taken on. Reading a clock
    // here would make an old decision's explanation change every birthday.
    assertThat(Masker.maskValue("1968-07-19", FactSchema.Mask.AGE_BAND, LocalDate.of(2026, 10, 1)))
        .isEqualTo("56-65");
    assertThat(Masker.maskValue("1968-07-19", FactSchema.Mask.AGE_BAND, LocalDate.of(2010, 10, 1)))
        .isEqualTo("36-45");
  }

  @Test
  @DisplayName("an undeclared field is hidden, because ext is open and may hold anything")
  void undeclaredIsHidden() throws IOException {
    // The side to be wrong on. `ext` is explicitly open (§3.4), so a tenant can put an applicant's
    // name in it; there is no mask rule for it, and passing it through would publish it.
    JsonNode withExt =
        JSON.readTree("{\"group\":{\"ext\":{\"contactName\":\"Lakshmi D\"},\"type\":\"JLG\"}}");
    JsonNode masked = new Masker(SCHEMA).mask(withExt, AS_OF);
    assertThat(masked.get("group").get("ext").get("contactName").asText()).isEqualTo(Masker.HIDDEN);
    assertThat(masked.get("group").get("type").asText()).isEqualTo("JLG");
  }

  @Test
  @DisplayName("a value that cannot be masked by its rule is hidden, not passed through")
  void unmaskableIsHidden() {
    assertThat(Masker.maskValue("not-a-date", FactSchema.Mask.AGE_BAND, AS_OF))
        .isEqualTo(Masker.HIDDEN);
    assertThat(Masker.maskValue("abc", FactSchema.Mask.ROUND_1000, AS_OF)).isEqualTo(Masker.HIDDEN);
    assertThat(Masker.maskValue("tok_12", FactSchema.Mask.TOKEN_TAIL4, AS_OF))
        .as("a token too short to keep a tail from must not be shown whole")
        .isEqualTo("tok_" + Masker.HIDDEN);
  }

  @Test
  @DisplayName("masking does not modify the document it was given")
  void maskingIsPure() throws IOException {
    JsonNode original = facts();
    String before = original.toString();
    new Masker(SCHEMA).mask(original, AS_OF);
    assertThat(original.toString()).isEqualTo(before);
  }

  // --- splitting -------------------------------------------------------------------------

  @Test
  @DisplayName("identifying fields leave the non-identifying half, grouped per party")
  void splitSeparatesByGroup() throws IOException {
    FieldGroupSplitter.Split split = new FieldGroupSplitter(SCHEMA).split(facts());

    JsonNode alice = split.nonIdentifying().get("parties").get(0);
    assertThat(alice.has("dateOfBirth")).isFalse();
    assertThat(alice.has("gender")).isFalse();
    assertThat(alice.has("principalRef")).isFalse();
    assertThat(alice.get("income").has("monthlyNet"))
        .as("income is identifying under §9.1, even though it reads as a number")
        .isFalse();
    assertThat(alice.get("income").get("verified").asBoolean())
        .as("but the non-identifying sibling stays")
        .isTrue();
    assertThat(alice.get("bureauReports").get(0).get("score").asInt())
        .as("a bureau score is regulated but not identifying: it stays under the TDK")
        .isEqualTo(720);

    assertThat(split.identifying()).containsOnlyKeys("P-0001", "P-0002");
    assertThat(split.identifying().get("P-0001").get("dateOfBirth").asText())
        .isEqualTo("1992-03-04");
    assertThat(split.principalOf())
        .as("the group is keyed by partyRef; the KEY it is sealed under is the principal")
        .containsEntry("P-0001", "PR-alice")
        .containsEntry("P-0002", "PR-bob");
  }

  @Test
  @DisplayName("partyRef stays in the non-identifying half, or the halves cannot be rejoined")
  void partyRefIsTheJoinKey() throws IOException {
    FieldGroupSplitter.Split split = new FieldGroupSplitter(SCHEMA).split(facts());
    assertThat(split.nonIdentifying().get("parties").get(0).get("partyRef").asText())
        .isEqualTo("P-0001");
  }

  @Test
  @DisplayName("the two halves rejoin into the original facts")
  void splitThenRejoin() throws IOException {
    FieldGroupSplitter splitter = new FieldGroupSplitter(SCHEMA);
    FieldGroupSplitter.Split split = splitter.split(facts());

    JsonNode rejoined = splitter.rejoin(split.nonIdentifying(), split.identifying());
    JsonNode alice = rejoined.get("parties").get(0);
    assertThat(alice.get("dateOfBirth").asText()).isEqualTo("1992-03-04");
    assertThat(alice.get("income").get("monthlyNet").asText()).isEqualTo("14000.00");
    assertThat(alice.get("income").get("verified").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("an erased principal rejoins with their fields simply absent")
  void rejoinWithoutAnErasedPrincipal() throws IOException {
    // §9.5: this is the normal path after an erasure, not an error path. The decision is still
    // fully readable; one person's identity is not.
    FieldGroupSplitter splitter = new FieldGroupSplitter(SCHEMA);
    FieldGroupSplitter.Split split = splitter.split(facts());

    Map<String, JsonNode> onlyBob = Map.of("P-0002", split.identifying().get("P-0002"));
    JsonNode rejoined = splitter.rejoin(split.nonIdentifying(), onlyBob);

    assertThat(rejoined.get("parties").get(0).has("dateOfBirth")).isFalse();
    assertThat(rejoined.get("parties").get(0).get("partyRef").asText()).isEqualTo("P-0001");
    assertThat(rejoined.get("parties").get(1).get("dateOfBirth").asText()).isEqualTo("1968-07-19");
  }

  @Test
  @DisplayName("an identifying field with no party is reported, not silently kept")
  void unassignableIsReported() throws IOException {
    // application.applicationRef and households[].annualIncome are tagged identifying but belong
    // to no party. Keeping them under the TDK would mean personal data under a key that is never
    // destroyed, so they are reported for the caller to refuse.
    FieldGroupSplitter.Split split = new FieldGroupSplitter(SCHEMA).split(facts());
    assertThat(split.unassignable())
        .contains("application.applicationRef", "households[].annualIncome");
    assertThat(split.nonIdentifying().get("application").has("applicationRef")).isFalse();
  }

  @Test
  @DisplayName("two parties who are the same person share one key")
  void jointApplicantsShareAPrincipal() throws IOException {
    JsonNode joint =
        JSON.readTree(
            """
            { "parties": [ { "partyRef": "P-1", "principalRef": "PR-x", "gender": "F" },
                           { "partyRef": "P-2", "principalRef": "PR-x", "gender": "M" } ] }
            """);
    FieldGroupSplitter.Split split = new FieldGroupSplitter(SCHEMA).split(joint);
    assertThat(split.identifying()).containsOnlyKeys("P-1", "P-2");
    assertThat(split.principalOf().values())
        .as("erasing PR-x must erase both, which only works if both seal under one key")
        .containsOnly("PR-x");
  }

  @Test
  @DisplayName("a party with no principalRef falls back to its partyRef")
  void fallsBackToPartyRef() throws IOException {
    JsonNode noPrincipal = JSON.readTree("{\"parties\":[{\"partyRef\":\"P-1\",\"gender\":\"F\"}]}");
    FieldGroupSplitter.Split split = new FieldGroupSplitter(SCHEMA).split(noPrincipal);
    assertThat(split.principalOf())
        .as("without the fallback every such applicant would share one key")
        .containsEntry("P-1", "P-1");
  }
}
