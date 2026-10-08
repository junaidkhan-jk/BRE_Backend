package dev.bre.irjson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** BRE-JCS canonicalisation, against LLD §2.5. */
class BreJcsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode json(String text) {
    try {
      return MAPPER.readTree(text);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // --- rule 2: strings and keys follow RFC 8785 --------------------------------------------------

  @Test
  void objectKeysAreSortedByUtf16CodeUnit() {
    // Deliberately not alphabetical in the input, and mixing cases: 'B' (0x42) sorts before
    // 'a' (0x61) under code-unit ordering, which is not what a case-insensitive sort would do.
    assertThat(BreJcs.canonicalize(json("{\"a\":1,\"B\":2,\"A\":3,\"b\":4}")))
        .isEqualTo("{\"A\":3,\"B\":2,\"a\":1,\"b\":4}");
  }

  @Test
  void nestedObjectsAreSortedAtEveryLevel() {
    assertThat(BreJcs.canonicalize(json("{\"z\":{\"y\":1,\"x\":2},\"a\":[{\"d\":1,\"c\":2}]}")))
        .isEqualTo("{\"a\":[{\"c\":2,\"d\":1}],\"z\":{\"x\":2,\"y\":1}}");
  }

  @Test
  void arrayOrderIsPreserved() {
    // Arrays carry meaning by position — table rows resolve FIRST by declaration order — so
    // sorting them would change what a policy does, not merely how it serialises.
    assertThat(BreJcs.canonicalize(json("[3,1,2]"))).isEqualTo("[3,1,2]");
  }

  @Test
  void escapingIsMinimalAndUsesTheShortFormsWhereTheyExist() {
    assertThat(BreJcs.canonicalize(json("{\"k\":\"a\\\"b\\\\c\\nd\\te\"}")))
        .isEqualTo("{\"k\":\"a\\\"b\\\\c\\nd\\te\"}");
  }

  @Test
  void otherControlCharactersUseTheSixCharacterEscape() {
    assertThat(BreJcs.canonicalize(json("{\"k\":\"\\u0001\\u001f\"}")))
        .isEqualTo("{\"k\":\"\\u0001\\u001f\"}");
  }

  @Test
  void nonAsciiCharactersAreNotEscaped() {
    // RFC 8785 emits them literally and lets UTF-8 carry them. Indian-language reason text and
    // the rupee sign go through here, so escaping them would be both wrong and unreadable.
    assertThat(BreJcs.canonicalize(json("{\"k\":\"वितरण ₹1\"}"))).isEqualTo("{\"k\":\"वितरण ₹1\"}");
  }

  // --- rule 1: integers only ---------------------------------------------------------------------

  @Test
  void integersAreEmittedPlainWithNoExponentOrLeadingZeros() {
    assertThat(BreJcs.canonicalize(json("{\"a\":0,\"b\":1e2,\"c\":-7}")))
        .isEqualTo("{\"a\":0,\"b\":100,\"c\":-7}");
  }

  @Test
  void anIntegerBeyondLongRangeSurvivesIntact() {
    // Via BigInteger, not long: wrapping would produce a different, valid-looking number and a
    // bundle hash that silently disagrees with the document it was computed from.
    String huge = "123456789012345678901234567890";
    assertThat(BreJcs.canonicalize(json("{\"n\":" + huge + "}"))).isEqualTo("{\"n\":" + huge + "}");
  }

  /**
   * The reason BRE-JCS exists. Under RFC 8785 this number would go through a double and come back
   * changed; here it is refused outright, because a canonical form that quietly rounds money is
   * worse than one that fails.
   */
  @Test
  void aNonIntegerNumberIsRejectedAndNamesItsPath() {
    assertThatThrownBy(
            () -> BreJcs.canonicalize(json("{\"loan\":{\"amount\":123456789012345.67}}")))
        .isInstanceOf(PsfException.class)
        .hasMessageContaining("CANON-001")
        .hasMessageContaining("loan.amount")
        .hasMessageContaining("decimals travel as strings");
  }

  @Test
  void aDecimalCarriedAsAStringPassesThroughUnchanged() {
    assertThat(BreJcs.canonicalize(json("{\"amount\":\"123456789012345.67\"}")))
        .isEqualTo("{\"amount\":\"123456789012345.67\"}");
  }

  // --- rule 3: facts canonicalisation ------------------------------------------------------------

  /**
   * §2.5 rule 3. Two Loan Origination Systems sending the same amount, one as {@code 212500.00} and
   * one as {@code 212500}, must produce the same HMAC — otherwise the difference reads as tampering
   * with an audit record.
   */
  @Test
  void schemaTypedDecimalFactsNormaliseTheirTrailingZeros() {
    Set<String> decimals = Set.of("application.requestedAmount");

    String padded =
        BreJcs.canonicalizeFacts(
            json("{\"application\":{\"requestedAmount\":\"212500.00\"}}"), decimals::contains);
    String bare =
        BreJcs.canonicalizeFacts(
            json("{\"application\":{\"requestedAmount\":212500}}"), decimals::contains);

    assertThat(padded).isEqualTo("{\"application\":{\"requestedAmount\":\"212500\"}}");
    assertThat(bare).isEqualTo(padded);
  }

  @Test
  void aDecimalFactInsideAListIsAddressedWithBrackets() {
    // The schema types a field, not a position, so every element shares one type and one path.
    String canonical =
        BreJcs.canonicalizeFacts(
            json(
                "{\"member\":{\"tradelines\":[{\"balance\":\"100.50\"},{\"balance\":\"200.00\"}]}}"),
            "member.tradelines[].balance"::equals);

    assertThat(canonical)
        .isEqualTo("{\"member\":{\"tradelines\":[{\"balance\":\"100.5\"},{\"balance\":\"200\"}]}}");
  }

  @Test
  void zeroNormalisesToASingleZeroWhateverItsScale() {
    // stripTrailingZeros leaves "0.00" as 0E-2, whose toPlainString is "0.00" again — so zero
    // needs its own case, or two spellings of nothing hash differently.
    assertThat(BreJcs.canonicalizeFacts(json("{\"a\":\"0.00\"}"), "a"::equals))
        .isEqualTo("{\"a\":\"0\"}");
    assertThat(BreJcs.canonicalizeFacts(json("{\"a\":\"0\"}"), "a"::equals))
        .isEqualTo("{\"a\":\"0\"}");
  }

  @Test
  void aNonDecimalFactKeepsIntegerRules() {
    assertThatThrownBy(() -> BreJcs.canonicalizeFacts(json("{\"score\":712.5}"), path -> false))
        .isInstanceOf(PsfException.class)
        .hasMessageContaining("CANON-001")
        .hasMessageContaining("score");
  }

  /** §9.6: a fact value must never reach a message, because messages reach logs. */
  @Test
  void aMalformedDecimalFactIsReportedWithoutItsValue() {
    assertThatThrownBy(
            () -> BreJcs.canonicalizeFacts(json("{\"pan\":\"ABCDE1234F\"}"), "pan"::equals))
        .isInstanceOf(PsfException.class)
        .hasMessageContaining("CANON-001")
        .hasMessageContaining("pan")
        .hasMessageNotContaining("ABCDE1234F");
  }

  // --- hashing -----------------------------------------------------------------------------------

  @Test
  void theHashIsStableAcrossKeyOrderAndIsPrefixed() {
    String one = BreJcs.sha256(json("{\"a\":1,\"b\":2}"));
    String other = BreJcs.sha256(json("{\"b\":2,\"a\":1}"));

    assertThat(one).isEqualTo(other).startsWith("sha256:").hasSize(7 + 64);
    assertThat(one.substring(7)).matches("[0-9a-f]{64}");
  }

  @Test
  void theHashOfTheEmptyObjectIsTheSha256OfTwoBraces() {
    // A fixed, independently checkable value: SHA-256 of the two bytes "{}".
    assertThat(BreJcs.sha256(json("{}")))
        .isEqualTo("sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a");
  }
}
