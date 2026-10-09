package dev.bre.facts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bre.engine.eval.Value;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fact typing, through the schema (§3.4).
 *
 * <p>Each of these was a live defect in the shape-sniffing reader this replaced, and none of them
 * failed loudly: a mistyped fact becomes Missing, Missing propagates strictly, and the application
 * is referred for missing data. The symptom is always "the integrator sent bad data".
 */
class FactReaderTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final FactReader READER = new FactReader(LendingFacts.schema());

  private static Value.RecordV read(String json) throws IOException {
    return READER.toRecord(JSON.readValue(json, new TypeReference<Map<String, Object>>() {}));
  }

  @Test
  @DisplayName("a declared date becomes a Date, not the string it arrived as")
  void declaredDateIsADate() throws IOException {
    Value.RecordV facts =
        read(
            """
        { "group": { "formedOn": "2024-02-15" } }
        """);
    assertThat(((Value.RecordV) facts.field("group")).field("formedOn"))
        .as("as Text, every ageInYears and monthsBetween in a real policy fails closed")
        .isEqualTo(new Value.DateV(LocalDate.of(2024, 2, 15)));
  }

  @Test
  @DisplayName("a bound entity types as the collection it is a view of (§3.8)")
  void boundEntityTypesAsItsCollection() throws IOException {
    // `member` is not a fact root; it is parties[] narrowed to role MEMBER. The §13 vectors and
    // every single-member fixture bind it directly, so it has to type the same way.
    Value.RecordV facts =
        read(
            """
        { "member": { "dateOfBirth": "1992-03-04", "proposedAmount": "30000.00" } }
        """);
    Value.RecordV member = (Value.RecordV) facts.field("member");
    assertThat(member.field("dateOfBirth")).isEqualTo(new Value.DateV(LocalDate.of(1992, 3, 4)));
    assertThat(member.field("proposedAmount"))
        .isEqualTo(new Value.DecimalV(new BigDecimal("30000.00")));
  }

  @Test
  @DisplayName("a digits-only Text field stays Text")
  void digitsInATextFieldStayText() throws IOException {
    // The old reader made this a Decimal, so "0012" and "12" compared equal — and a branch code,
    // an account number or a reference made of digits silently became a number.
    Value.RecordV facts =
        read(
            """
        { "application": { "branchCode": "0012" } }
        """);
    assertThat(((Value.RecordV) facts.field("application")).field("branchCode"))
        .isEqualTo(new Value.TextV("0012"));
  }

  @Test
  @DisplayName("money stays exact through a JSON string")
  void moneyIsExact() throws IOException {
    Value.RecordV facts =
        read(
            """
        { "application": { "requestedAmount": "150000.00" } }
        """);
    assertThat(((Value.RecordV) facts.field("application")).field("requestedAmount"))
        .isEqualTo(new Value.DecimalV(new BigDecimal("150000.00")));
  }

  @Test
  @DisplayName("exponent notation in a money field reads as missing, not as a guess")
  void exponentNotationIsMissing() throws IOException {
    Value.RecordV facts =
        read(
            """
        { "application": { "requestedAmount": "1.5e5" } }
        """);
    assertThat(((Value.RecordV) facts.field("application")).field("requestedAmount").isMissing())
        .as("§3.6 accepts only the plain form; failing closed beats inferring 150000")
        .isTrue();
  }

  @Test
  @DisplayName("an unparseable date reads as missing rather than throwing")
  void unparseableDateIsMissing() throws IOException {
    // One bad field must not 500 the whole application. The validator is what names the path.
    Value.RecordV facts =
        read(
            """
        { "group": { "formedOn": "2026-13-45" } }
        """);
    assertThat(((Value.RecordV) facts.field("group")).field("formedOn").isMissing()).isTrue();
  }

  @Test
  @DisplayName("an explicit null is missing, not an error")
  void explicitNullIsMissing() throws IOException {
    Value.RecordV facts =
        read(
            """
        { "application": { "approvedAmount": null } }
        """);
    assertThat(((Value.RecordV) facts.field("application")).field("approvedAmount").isMissing())
        .isTrue();
  }

  @Test
  @DisplayName("an undeclared ext field keeps its JSON shape")
  void undeclaredExtKeepsItsShape() throws IOException {
    // ext is explicitly open (§3.4), so there is no declared type to apply.
    Value.RecordV facts =
        read(
            """
        { "group": { "ext": { "note": "0012", "count": 7, "flag": true } } }
        """);
    Value.RecordV ext = (Value.RecordV) ((Value.RecordV) facts.field("group")).field("ext");
    assertThat(ext.field("note")).isEqualTo(new Value.TextV("0012"));
    assertThat(ext.field("count")).isEqualTo(new Value.IntV(7));
    assertThat(ext.field("flag")).isEqualTo(Value.BoolV.of(true));
  }

  @Test
  @DisplayName("a list element is typed by its path with [] appended")
  void listElementsAreTyped() throws IOException {
    Value.RecordV facts =
        read(
            """
        { "parties": [ { "dateOfBirth": "1990-01-01" }, { "dateOfBirth": "1985-06-30" } ] }
        """);
    Value.ListV parties = (Value.ListV) facts.field("parties");
    assertThat(parties.elements()).hasSize(2);
    assertThat(((Value.RecordV) parties.elements().get(1)).field("dateOfBirth"))
        .isEqualTo(new Value.DateV(LocalDate.of(1985, 6, 30)));
  }
}
