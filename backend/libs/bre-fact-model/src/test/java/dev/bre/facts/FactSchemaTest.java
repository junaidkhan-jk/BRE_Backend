package dev.bre.facts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The fact schema's reading of the §9.1 vendor keywords. */
class FactSchemaTest {

  private final FactSchema schema = LendingFacts.schema();

  @Test
  void theCanonicalSchemaLoadsAndIsNotEmpty() {
    assertThat(schema.code()).isEqualTo("lending-facts");
    assertThat(schema.version()).isEqualTo("1.0.0");
    assertThat(schema.fields()).hasSizeGreaterThan(50);
  }

  /** A list element is {@code []}: the schema types a field, not a position. */
  @Test
  void listElementsSharePathsWithTheirSiblings() {
    assertThat(schema.declares("parties[].bureauReports[].tradelines[].currentBalance")).isTrue();
    assertThat(schema.declares("parties[].bureauReports[].tradelines[0].currentBalance"))
        .as("a path addresses the field, not the index")
        .isFalse();
  }

  @Test
  void moneyIsADecimalEvenThoughItTravelsAsAString() {
    // §2.1 rule 4: decimals are JSON strings. The type checker must still see Decimal, or every
    // arithmetic comparison on an amount would be a type error.
    assertThat(schema.field("application.requestedAmount").orElseThrow().belType())
        .isEqualTo("Decimal");
    assertThat(schema.isDecimal("parties[].income.monthlyNet")).isTrue();
    assertThat(schema.isDecimal("application.tenureMonths"))
        .as("a tenure in months is an Integer, not money")
        .isFalse();
  }

  @Test
  void jsonSchemaTypesAndFormatsMapToBelTypes() {
    assertThat(schema.field("application.tenureMonths").orElseThrow().belType())
        .isEqualTo("Integer");
    assertThat(schema.field("parties[].income.verified").orElseThrow().belType())
        .isEqualTo("Boolean");
    assertThat(schema.field("parties[].dateOfBirth").orElseThrow().belType()).isEqualTo("Date");
    assertThat(schema.field("parties[].kyc.verifiedAt").orElseThrow().belType())
        .isEqualTo("DateTime");
    assertThat(schema.field("application.purpose").orElseThrow().belType()).isEqualTo("Text");
    assertThat(schema.field("parties[].bureauReports[].score").orElseThrow().belType())
        .isEqualTo("Integer");
  }

  @Test
  void enumValuesAreCarriedForBel011() {
    FactSchema.Field role = schema.field("parties[].role").orElseThrow();
    assertThat(role.belType()).isEqualTo("Text");
    assertThat(role.enumValues())
        .containsExactlyInAnyOrder(
            "APPLICANT", "CO_APPLICANT", "GUARANTOR", "PROMOTER", "DIRECTOR", "MEMBER");
  }

  /**
   * The fields that make a lending decision discriminatory. Reading one from rule logic is BEL-017
   * without an approved governance override.
   */
  @Test
  void theProtectedFieldsAreTaggedProtected() {
    for (String path : new String[] {"parties[].gender", "parties[].religion", "parties[].caste"}) {
      assertThat(schema.field(path).orElseThrow().isProtected())
          .as("%s must be protected", path)
          .isTrue();
    }
    assertThat(schema.field("parties[].income.monthlyNet").orElseThrow().isProtected())
        .as("income is sensitive but not a protected characteristic")
        .isFalse();
  }

  /** BEL-026, and there is no override for it. */
  @Test
  void theAadhaarReferenceIsTaggedAadhaar() {
    FactSchema.Field field = schema.field("parties[].aadhaarVaultRef").orElseThrow();
    assertThat(field.isAadhaar()).isTrue();
    assertThat(field.tags()).contains(FactSchema.Tag.PII, FactSchema.Tag.REGULATED);
  }

  @Test
  void identifyingFieldsAreGroupedForPerPrincipalEncryption() {
    assertThat(schema.field("parties[].principalRef").orElseThrow().group())
        .isEqualTo(FactSchema.Group.IDENTIFYING);
    assertThat(schema.field("application.tenureMonths").orElseThrow().group())
        .as("the default is non-identifying")
        .isEqualTo(FactSchema.Group.NON_IDENTIFYING);
  }

  @Test
  void masksAreReadFromTheSchema() {
    assertThat(schema.field("parties[].dateOfBirth").orElseThrow().mask())
        .isEqualTo(FactSchema.Mask.AGE_BAND);
    assertThat(schema.field("parties[].panToken").orElseThrow().mask())
        .isEqualTo(FactSchema.Mask.TOKEN_TAIL4);
    assertThat(schema.field("parties[].gender").orElseThrow().mask())
        .isEqualTo(FactSchema.Mask.HIDE);
    assertThat(schema.field("application.requestedAmount").orElseThrow().mask())
        .isEqualTo(FactSchema.Mask.ROUND_1000);
    assertThat(schema.field("application.purpose").orElseThrow().mask())
        .as("the default is no mask")
        .isEqualTo(FactSchema.Mask.NONE);
  }

  @Test
  void piiAndRegulatedFieldsReportThemselvesAsMasked() {
    assertThat(schema.field("parties[].dateOfBirth").orElseThrow().isMasked()).isTrue();
    assertThat(schema.field("parties[].bureauReports[].score").orElseThrow().isMasked()).isTrue();
    assertThat(schema.field("application.tenureMonths").orElseThrow().isMasked()).isFalse();
  }

  /** An unknown path is BEL-019's business, and must not read as a silent permission. */
  @Test
  void anUnknownPathIsAbsentRatherThanPermissive() {
    assertThat(schema.declares("application.noSuchField")).isFalse();
    assertThat(schema.field("application.noSuchField")).isEmpty();
  }

  /** An unknown mask keyword fails to the restrictive side. */
  @Test
  void anUnrecognisedMaskHidesTheField() {
    FactSchema custom =
        FactSchema.parse(
            "custom",
            "1.0.0",
            """
            { "type": "object", "properties": {
                "a": { "type": "string", "x-bre-mask": "SOME_FUTURE_RULE" } } }
            """);
    assertThat(custom.field("a").orElseThrow().mask())
        .as(
            "a field the schema wanted masked must not be shown in full by a build that does not"
                + " know the rule yet")
        .isEqualTo(FactSchema.Mask.HIDE);
  }

  /** An unrecognised tag is ignored: every tag restricts, so ignoring one cannot grant anything. */
  @Test
  void anUnrecognisedTagIsIgnored() {
    FactSchema custom =
        FactSchema.parse(
            "custom",
            "1.0.0",
            """
            { "type": "object", "properties": {
                "a": { "type": "string", "x-bre-tags": ["pii", "some-future-tag"] } } }
            """);
    assertThat(custom.field("a").orElseThrow().tags()).containsExactly(FactSchema.Tag.PII);
  }
}
