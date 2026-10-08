package dev.bre.facts;

/**
 * Prohibited-data detection over fact strings (LLD §9.1).
 *
 * <p>Raw Aadhaar numbers are prohibited in facts (00 §3.9): a caller sends a vault reference or a
 * token instead. This is the scan that enforces it, and it runs on <b>every</b> string in the
 * facts, {@code ext} included — an open extension map is exactly where an unvalidated identifier
 * ends up.
 *
 * <p>The test is specified precisely, and the precision is the point: "any 12-digit sequence, with
 * optional single spaces or hyphens every 4 digits, whose Verhoeff check digit is valid". A looser
 * rule — any twelve digits — would reject legitimate account numbers, loan references and GST
 * turnover figures, and a platform that rejects valid applications is one that gets switched off.
 * The Verhoeff digit makes the test specific: a random 12-digit number passes it about one time in
 * ten, and a real Aadhaar number always does.
 *
 * <p><b>A hit is never logged or echoed.</b> {@link Finding} carries the fact path and the kind of
 * match, never the value — the whole purpose is to stop that number existing anywhere in the
 * system, and a rejection message quoting it would write it to a log instead.
 *
 * <p>PAN is handled differently and deliberately so: §3.9 says PAN "is accepted only as a token or
 * in encrypted form", so a bare PAN is also a finding, but by shape alone. PAN has no check digit
 * that can be verified offline.
 *
 * <p>Single linear pass, no regular expression. A regex with optional separators over untrusted
 * input is a backtracking risk, and this runs on every string of every request.
 */
public final class ProhibitedData {

  /** What kind of prohibited value was found. */
  public enum Kind {
    /** A 12-digit sequence with a valid Verhoeff check digit. */
    AADHAAR,
    /** Ten characters in PAN shape: five letters, four digits, a letter. */
    PAN
  }

  /**
   * One prohibited value, identified by where it was rather than by what it was.
   *
   * @param path the dotted fact path the value was found at
   * @param kind what it looked like
   */
  public record Finding(String path, Kind kind) {}

  /**
   * The Verhoeff multiplication table {@code d}.
   *
   * <p>Verhoeff rather than Luhn, and the distinction matters: Verhoeff is the scheme the UIDAI
   * actually uses, and it catches all single-digit errors and all adjacent transpositions, which
   * Luhn does not. Getting this wrong in the permissive direction lets a real Aadhaar number
   * through; in the strict direction it rejects valid applications.
   */
  private static final int[][] D = {
    {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
    {1, 2, 3, 4, 0, 6, 7, 8, 9, 5},
    {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
    {3, 4, 0, 1, 2, 8, 9, 5, 6, 7},
    {4, 0, 1, 2, 3, 9, 5, 6, 7, 8},
    {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
    {6, 5, 9, 8, 7, 1, 0, 4, 3, 2},
    {7, 6, 5, 9, 8, 2, 1, 0, 4, 3},
    {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
    {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}
  };

  /** The Verhoeff permutation table {@code p}. */
  private static final int[][] P = {
    {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
    {1, 5, 7, 6, 2, 8, 3, 0, 9, 4},
    {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
    {8, 9, 1, 6, 0, 4, 3, 5, 2, 7},
    {9, 4, 5, 3, 1, 2, 6, 8, 7, 0},
    {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
    {2, 7, 9, 3, 8, 0, 6, 4, 1, 5},
    {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}
  };

  /** The Verhoeff inverse table {@code inv}. Unused by validation, kept for generating a digit. */
  private static final int[] INV = {0, 4, 3, 2, 1, 5, 6, 7, 8, 9};

  private static final int AADHAAR_DIGITS = 12;

  private ProhibitedData() {}

  /**
   * Whether a 12-digit string has a valid Verhoeff check digit.
   *
   * @param digits exactly 12 characters, all digits
   * @return true when the check digit is valid
   */
  public static boolean isValidVerhoeff(String digits) {
    if (digits.length() != AADHAAR_DIGITS) {
      return false;
    }
    int checksum = 0;
    // Verhoeff processes the number from the RIGHTMOST digit, which is position 0.
    for (int i = 0; i < digits.length(); i++) {
      char c = digits.charAt(digits.length() - 1 - i);
      if (c < '0' || c > '9') {
        return false;
      }
      checksum = D[checksum][P[i % 8][c - '0']];
    }
    return checksum == 0;
  }

  /**
   * Computes the Verhoeff check digit for a body of digits.
   *
   * <p>Exists so a test can construct a number that genuinely passes the check rather than
   * hard-coding one — a hard-coded "valid Aadhaar" in a test file is a real identifier if someone
   * ever picked a real one.
   *
   * @param body the digits without a check digit
   * @return the check digit
   */
  public static int verhoeffCheckDigit(String body) {
    int checksum = 0;
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(body.length() - 1 - i);
      if (c < '0' || c > '9') {
        throw new IllegalArgumentException("the body must be digits only");
      }
      checksum = D[checksum][P[(i + 1) % 8][c - '0']];
    }
    return INV[checksum];
  }

  /**
   * Scans one string for a prohibited value.
   *
   * <p>§9.1's shape: twelve digits, optionally separated into groups of four by a <em>single</em>
   * space or hyphen. The separator must be consistent with the grouping — "1234-5678 9012" is
   * accepted because both forms are permitted, but a separator anywhere other than a group boundary
   * means this is not an Aadhaar-shaped number.
   *
   * @param value the string to scan
   * @return the kind found, or null when the string holds neither
   */
  public static Kind scan(String value) {
    if (value == null) {
      return null;
    }
    if (looksLikePan(value)) {
      return Kind.PAN;
    }
    return containsAadhaar(value) ? Kind.AADHAAR : null;
  }

  /**
   * Whether a string contains an Aadhaar-shaped number with a valid check digit.
   *
   * @param value the string to scan
   * @return true when one is present
   */
  public static boolean containsAadhaar(String value) {
    int length = value.length();
    for (int start = 0; start < length; start++) {
      if (!isDigit(value.charAt(start))) {
        continue;
      }
      // A match must not begin mid-number: a 13-digit figure is not a 12-digit one with a stray
      // digit in front, and treating it as one would reject long account numbers wholesale.
      if (start > 0 && isDigit(value.charAt(start - 1))) {
        continue;
      }
      String digits = readTwelveDigits(value, start);
      if (digits != null && isValidVerhoeff(digits)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reads twelve digits from {@code start}, allowing a single separator every four digits.
   *
   * @return the twelve digits, or null when the shape does not match
   */
  private static String readTwelveDigits(String value, int start) {
    StringBuilder digits = new StringBuilder(AADHAAR_DIGITS);
    int at = start;
    while (digits.length() < AADHAAR_DIGITS && at < value.length()) {
      char c = value.charAt(at);
      if (isDigit(c)) {
        digits.append(c);
        at++;
        continue;
      }
      // A separator is permitted only at a group boundary, and only one of them.
      boolean atGroupBoundary = digits.length() % 4 == 0 && digits.length() > 0;
      if ((c == ' ' || c == '-') && atGroupBoundary) {
        at++;
        continue;
      }
      return null;
    }
    if (digits.length() < AADHAAR_DIGITS) {
      return null;
    }
    // A thirteenth digit means this was a longer number, not an Aadhaar one.
    if (at < value.length() && isDigit(value.charAt(at))) {
      return null;
    }
    return digits.toString();
  }

  /**
   * Whether a string is exactly in PAN shape: {@code AAAAA9999A}.
   *
   * <p>Exact rather than a substring search. PAN's shape is common enough as a fragment of other
   * identifiers that a substring scan would produce false positives on legitimate references, and
   * unlike Aadhaar there is no check digit to make a looser test specific.
   *
   * @param value the string to test
   * @return true when the whole string is PAN-shaped
   */
  public static boolean looksLikePan(String value) {
    if (value.length() != 10) {
      return false;
    }
    for (int i = 0; i < 5; i++) {
      if (!isUpperLetter(value.charAt(i))) {
        return false;
      }
    }
    for (int i = 5; i < 9; i++) {
      if (!isDigit(value.charAt(i))) {
        return false;
      }
    }
    return isUpperLetter(value.charAt(9));
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isUpperLetter(char c) {
    return c >= 'A' && c <= 'Z';
  }
}
