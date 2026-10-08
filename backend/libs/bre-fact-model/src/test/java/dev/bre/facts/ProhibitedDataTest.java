package dev.bre.facts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Prohibited-data detection (LLD §9.1). */
class ProhibitedDataTest {

  /**
   * Builds a 12-digit number with a genuinely valid Verhoeff check digit.
   *
   * <p>Constructed rather than hard-coded on purpose: a literal "valid Aadhaar number" in a test
   * file is a real person's identifier if whoever wrote it happened to pick a real one.
   */
  private static String aadhaarShaped(String elevenDigits) {
    return elevenDigits + ProhibitedData.verhoeffCheckDigit(elevenDigits);
  }

  // --- the Verhoeff check itself ----------------------------------------------------------------

  /** The published worked example: the check digit of 236 is 3. */
  @Test
  void theCheckDigitMatchesThePublishedExample() {
    assertThat(ProhibitedData.verhoeffCheckDigit("236")).isEqualTo(3);
  }

  @Test
  void aGeneratedCheckDigitAlwaysValidates() {
    Random random = new Random(7); // Fixed seed: a failure must be reproducible.
    for (int i = 0; i < 2_000; i++) {
      StringBuilder body = new StringBuilder(11);
      for (int d = 0; d < 11; d++) {
        body.append(random.nextInt(10));
      }
      assertThat(ProhibitedData.isValidVerhoeff(aadhaarShaped(body.toString())))
          .as("generated number %s must validate", body)
          .isTrue();
    }
  }

  /**
   * The two error classes Verhoeff is chosen for, and that Luhn does not catch.
   *
   * <p>This is the test that would fail if the permutation table or the index offset were wrong in
   * a way that still happened to be self-consistent — which is the realistic way to get this wrong,
   * because a self-consistent wrong implementation passes the round-trip test above.
   */
  @Test
  void everySingleDigitErrorAndAdjacentTranspositionIsCaught() {
    Random random = new Random(11);
    int singleDigitChecked = 0;
    int transpositionsChecked = 0;

    for (int i = 0; i < 1_000; i++) {
      StringBuilder body = new StringBuilder(11);
      for (int d = 0; d < 11; d++) {
        body.append(random.nextInt(10));
      }
      String valid = aadhaarShaped(body.toString());

      for (int position = 0; position < 12; position++) {
        char original = valid.charAt(position);
        for (char replacement = '0'; replacement <= '9'; replacement++) {
          if (replacement == original) {
            continue;
          }
          String corrupted =
              valid.substring(0, position) + replacement + valid.substring(position + 1);
          assertThat(ProhibitedData.isValidVerhoeff(corrupted))
              .as("a single-digit error at %d must not validate", position)
              .isFalse();
          singleDigitChecked++;
        }
      }

      for (int position = 0; position < 11; position++) {
        if (valid.charAt(position) == valid.charAt(position + 1)) {
          continue; // Swapping equal digits is not an error.
        }
        String swapped =
            valid.substring(0, position)
                + valid.charAt(position + 1)
                + valid.charAt(position)
                + valid.substring(position + 2);
        assertThat(ProhibitedData.isValidVerhoeff(swapped))
            .as("an adjacent transposition at %d must not validate", position)
            .isFalse();
        transpositionsChecked++;
      }
    }

    assertThat(singleDigitChecked).isGreaterThan(100_000);
    assertThat(transpositionsChecked).isGreaterThan(8_000);
  }

  /**
   * The check must be specific, not merely strict. A rule that rejected any twelve digits would
   * reject legitimate account numbers and turnover figures, and a platform that rejects valid
   * applications gets switched off.
   */
  @Test
  void aRandomTwelveDigitNumberPassesAboutOneTimeInTen() {
    Random random = new Random(13);
    int passes = 0;
    int trials = 20_000;
    for (int i = 0; i < trials; i++) {
      StringBuilder digits = new StringBuilder(12);
      for (int d = 0; d < 12; d++) {
        digits.append(random.nextInt(10));
      }
      if (ProhibitedData.isValidVerhoeff(digits.toString())) {
        passes++;
      }
    }
    double rate = (double) passes / trials;
    assertThat(rate).isBetween(0.08, 0.12);
  }

  // --- the scan ---------------------------------------------------------------------------------

  @Test
  void anAadhaarShapedNumberIsFound() {
    String number = aadhaarShaped("12345678901");
    assertThat(ProhibitedData.scan(number)).isEqualTo(ProhibitedData.Kind.AADHAAR);
  }

  /** §9.1: "optional single spaces or hyphens every 4 digits". */
  @Test
  void bothPermittedSeparatorsAreRecognised() {
    String number = aadhaarShaped("12345678901");
    String spaced =
        number.substring(0, 4) + " " + number.substring(4, 8) + " " + number.substring(8);
    String hyphenated =
        number.substring(0, 4) + "-" + number.substring(4, 8) + "-" + number.substring(8);

    assertThat(ProhibitedData.containsAadhaar(spaced)).isTrue();
    assertThat(ProhibitedData.containsAadhaar(hyphenated)).isTrue();
  }

  @Test
  void aSeparatorAwayFromAGroupBoundaryIsNotAnAadhaarShape() {
    String number = aadhaarShaped("12345678901");
    String misplaced = number.substring(0, 5) + "-" + number.substring(5);
    assertThat(ProhibitedData.containsAadhaar(misplaced)).isFalse();
  }

  @Test
  void anAadhaarNumberEmbeddedInLongerTextIsStillFound() {
    String number = aadhaarShaped("98765432109");
    assertThat(ProhibitedData.containsAadhaar("ref " + number + " supplied by branch")).isTrue();
  }

  /** A longer figure is not a 12-digit one with extra digits attached. */
  @Test
  void aThirteenDigitNumberIsNotTreatedAsAadhaar() {
    String number = aadhaarShaped("12345678901");
    assertThat(ProhibitedData.containsAadhaar(number + "7")).isFalse();
    assertThat(ProhibitedData.containsAadhaar("7" + number)).isFalse();
  }

  @Test
  void ordinaryFactValuesAreNotFlagged() {
    for (String benign :
        new String[] {
          "",
          "APP-2026-000123",
          "212500.00",
          "tok_abcdefghij012345",
          "2026-10-01",
          "G-0001",
          "MFI_JLG_CREDIT_UW",
          "1234",
          "0000000000000000000000"
        }) {
      assertThat(ProhibitedData.scan(benign)).as("%s must not be flagged", benign).isNull();
    }
  }

  @Test
  void aBarePanIsFlaggedByShape() {
    assertThat(ProhibitedData.scan("ABCDE1234F")).isEqualTo(ProhibitedData.Kind.PAN);
    assertThat(ProhibitedData.looksLikePan("ABCDE1234F")).isTrue();
  }

  @Test
  void aPanTokenIsNotFlagged() {
    // §3.9: PAN is accepted as a token. The token is the compliant form and must pass.
    assertThat(ProhibitedData.scan("tok_ABCDE1234Fxxxxxxxxxxxx")).isNull();
  }

  @Test
  void panDetectionIsExactRatherThanASubstringSearch() {
    // PAN's shape is common enough as a fragment that a substring scan would reject legitimate
    // references, and unlike Aadhaar there is no check digit to make a loose test specific.
    assertThat(ProhibitedData.looksLikePan("XXABCDE1234FXX")).isFalse();
    assertThat(ProhibitedData.looksLikePan("ABCDE1234")).isFalse();
    assertThat(ProhibitedData.looksLikePan("abcde1234f")).isFalse();
  }

  @Test
  void aNullValueIsNotAFinding() {
    assertThat(ProhibitedData.scan(null)).isNull();
  }

  /** §9.1: "the value is never logged or echoed" — a finding names the path, not the value. */
  @Test
  void aFindingCarriesThePathAndNotTheValue() {
    ProhibitedData.Finding finding =
        new ProhibitedData.Finding("parties[].ext.idNumber", ProhibitedData.Kind.AADHAAR);

    assertThat(finding.path()).isEqualTo("parties[].ext.idNumber");
    assertThat(finding.toString())
        .as("the record's own toString must not be a way to leak the value")
        .doesNotContain("1234");
  }
}
