package dev.bre.facts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;

/**
 * The masked view of a facts document (LLD §9.4).
 *
 * <p>Six rules, applied by the schema's {@code x-bre-mask} tag:
 *
 * <ul>
 *   <li>{@code HIDE} — the value is replaced outright
 *   <li>{@code AGE_BAND} — a date of birth becomes an age band at {@code asOf}
 *   <li>{@code TOKEN_TAIL4} — a token keeps its prefix and its last four characters
 *   <li>{@code ROUND_1000} — an amount is rounded to the nearest thousand
 *   <li>{@code MONTH_ONLY} — a date becomes {@code YYYY-MM}
 *   <li>{@code NONE} — unchanged, and §9.4 allows it only on fields that are not personal data
 * </ul>
 *
 * <p><b>Masking removes information; it does not hide it behind a flag.</b> Every rule here is
 * lossy on purpose. The alternative that keeps coming up — sending the real value with a {@code
 * masked: true} marker and letting the client blank it — is not masking at all: the data has
 * already crossed the network, is already in the browser's memory, and is already in whatever proxy
 * logged the response body. This is why §13 puts masking in the BFF rather than in the studio, and
 * why this class returns a new document rather than annotating the old one.
 *
 * <p><b>Why AGE_BAND rather than a redacted date of birth.</b> A credit officer legitimately needs
 * to know an applicant is 34 rather than 17; they do not need the birth date. A band answers the
 * question the screen exists to answer and is not re-identifying, which an exact date of birth very
 * much is in combination with a village and a group.
 *
 * <p><b>Who sees what</b> is not decided here (§9.4): {@code OPS_VIEWER} and {@code RISK_ANALYST}
 * always get the masked view, and an {@code AUDITOR} may unmask with {@code X-Unmask-Reason}, which
 * is itself an audit event. This class does the masking; the caller decides whether to call it.
 * That split is deliberate — a masker that also decided authorisation would be a security decision
 * buried in a formatting class.
 */
public final class Masker {

  /** What a {@code HIDE} field becomes. */
  public static final String HIDDEN = "<masked>";

  /** What an identifying field becomes when its principal has been erased (§9.5). */
  public static final String SHREDDED = "<shredded>";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
  private static final int[] BAND_UPPER = {25, 35, 45, 55, 65};
  private static final String[] BAND_LABEL = {"18-25", "26-35", "36-45", "46-55", "56-65", "66+"};

  private final FactSchema schema;

  /**
   * Creates a masker over a schema.
   *
   * @param schema the fact schema, which carries the {@code x-bre-mask} rules
   */
  public Masker(FactSchema schema) {
    this.schema = schema;
  }

  /**
   * Returns the masked view of a facts document.
   *
   * @param facts the facts
   * @param asOf the business date, for {@code AGE_BAND} — passed in rather than read from a clock,
   *     so a decision's masked view shows the age the decision was taken on and not today's
   * @return a new document; the input is not modified
   */
  public JsonNode mask(JsonNode facts, LocalDate asOf) {
    return walk(facts, "", asOf);
  }

  /**
   * Masks one value, given its declared rule.
   *
   * <p>Exposed because a trace value (§9.4: "explanation traces apply the same rules") is a single
   * value with a known source path, not a document.
   *
   * @param value the value as text
   * @param rule the masking rule
   * @param asOf the business date, for {@code AGE_BAND}
   * @return the masked text
   */
  public static String maskValue(String value, FactSchema.Mask rule, LocalDate asOf) {
    if (value == null) {
      return null;
    }
    return switch (rule) {
      case HIDE -> HIDDEN;
      case AGE_BAND -> ageBand(value, asOf);
      case TOKEN_TAIL4 -> tokenTail4(value);
      case ROUND_1000 -> round1000(value);
      case MONTH_ONLY -> monthOnly(value);
      case NONE -> value;
    };
  }

  // ---------------------------------------------------------------------------------------------

  private JsonNode walk(JsonNode node, String path, LocalDate asOf) {
    if (node == null || node.isNull()) {
      return node;
    }
    if (node.isObject()) {
      ObjectNode out = MAPPER.createObjectNode();
      node.properties()
          .forEach(
              entry ->
                  out.set(
                      entry.getKey(), walk(entry.getValue(), child(path, entry.getKey()), asOf)));
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = MAPPER.createArrayNode();
      String elementPath = path + "[]";
      node.forEach(element -> out.add(walk(element, elementPath, asOf)));
      return out;
    }

    FactSchema.Mask rule =
        schema.field(path).map(FactSchema.Field::mask).orElse(FactSchema.Mask.NONE);
    if (rule == FactSchema.Mask.NONE) {
      // An UNDECLARED path is the case worth being careful about. `ext` is open (§3.4), so a
      // tenant can put anything in it — including an applicant's name. It has no mask rule, and
      // defaulting to NONE would publish it. Defaulting to HIDE would make `ext` useless for the
      // operational metadata it exists for. So: declared-and-NONE passes through, and
      // undeclared is hidden, which is the side to be wrong on.
      if (schema.field(path).isEmpty()) {
        return MAPPER.getNodeFactory().textNode(HIDDEN);
      }
      return node;
    }
    return MAPPER.getNodeFactory().textNode(maskValue(node.asText(), rule, asOf));
  }

  private static String child(String path, String key) {
    return path.isEmpty() ? key : path + "." + key;
  }

  private static String ageBand(String value, LocalDate asOf) {
    LocalDate dob;
    try {
      dob = LocalDate.parse(value);
    } catch (RuntimeException notADate) {
      // A value that is not a date cannot be banded, and showing it raw would defeat the rule.
      return HIDDEN;
    }
    int years = Period.between(dob, asOf).getYears();
    for (int i = 0; i < BAND_UPPER.length; i++) {
      if (years <= BAND_UPPER[i]) {
        return BAND_LABEL[i];
      }
    }
    return BAND_LABEL[BAND_LABEL.length - 1];
  }

  private static String tokenTail4(String value) {
    // tok_9f8e7d6c5b4a3210 -> tok_…3210 (§9.4). The prefix is kept because it names the token's
    // TYPE, which is operationally useful and not identifying; the tail is kept because it is what
    // lets a human confirm two systems are talking about the same token without seeing it.
    int underscore = value.indexOf('_');
    String prefix = underscore >= 0 ? value.substring(0, underscore + 1) : "";
    String body = underscore >= 0 ? value.substring(underscore + 1) : value;
    if (body.length() <= 4) {
      // Too short to keep a tail from without revealing the whole thing.
      return prefix + HIDDEN;
    }
    return prefix + "…" + body.substring(body.length() - 4);
  }

  private static String round1000(String value) {
    try {
      return new BigDecimal(value)
          .divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_EVEN)
          .multiply(BigDecimal.valueOf(1000))
          .toPlainString();
    } catch (NumberFormatException notANumber) {
      return HIDDEN;
    }
  }

  private static String monthOnly(String value) {
    try {
      return MONTH.format(LocalDate.parse(value));
    } catch (RuntimeException notADate) {
      // A month-only field may already be month-only; keep it if it is, hide it otherwise.
      return value.length() == 7 && value.charAt(4) == '-' ? value : HIDDEN;
    }
  }
}
