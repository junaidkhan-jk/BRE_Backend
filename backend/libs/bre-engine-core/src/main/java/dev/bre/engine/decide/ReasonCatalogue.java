package dev.bre.engine.decide;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The reason-code catalogue a decision renders its reasons through (LLD §2.2 {@code
 * REASON_CODE_CATALOGUE}, §4.10).
 *
 * <p>Two fields carry the weight. {@code adverse} says whether a code may be shown to the customer
 * as a ground for declining — the catalogue, not the rule, decides that, because the same rule may
 * be adverse in one jurisdiction and not another. {@code customerText} carries the wording per
 * locale, so that what the customer reads is reviewed text and never an internal code.
 *
 * <p>A code the catalogue does not know is treated as <b>not</b> adverse and is therefore not shown
 * to the customer. That direction is deliberate: showing an unreviewed internal code to a declined
 * applicant is a compliance problem, whereas omitting one is a gap the internal reasons still
 * record in full. Fail closed on disclosure, not on the audit trail.
 */
public record ReasonCatalogue(Map<String, Entry> entries) {

  /** An empty catalogue — no code is adverse, so no customer reason is produced. */
  public static final ReasonCatalogue EMPTY = new ReasonCatalogue(Map.of());

  /** The locale every rendering falls back to (§4.10 step 5). */
  public static final String FALLBACK_LOCALE = "en";

  /**
   * One catalogue entry.
   *
   * @param adverse whether this code may be disclosed to the customer as a reason for decline
   * @param customerText the customer wording by BCP 47 language tag; {@code en} is the fallback
   */
  public record Entry(boolean adverse, Map<String, String> customerText) {

    /** Creates an entry, copying the text map. */
    public Entry {
      customerText = Map.copyOf(customerText);
    }

    /**
     * An adverse entry with English wording.
     *
     * @param english the customer wording
     * @return the entry
     */
    public static Entry adverse(String english) {
      return new Entry(true, Map.of(FALLBACK_LOCALE, english));
    }

    /**
     * A non-adverse entry, recorded internally but never shown to the customer.
     *
     * @return the entry
     */
    public static Entry internal() {
      return new Entry(false, Map.of());
    }
  }

  /** Creates a catalogue, copying the map. */
  public ReasonCatalogue {
    entries = Map.copyOf(entries);
  }

  /**
   * Whether a code may be disclosed to the customer.
   *
   * @param code the reason code
   * @return true only when the catalogue knows the code and marks it adverse
   */
  public boolean isAdverse(String code) {
    Entry entry = entries.get(code);
    return entry != null && entry.adverse();
  }

  /**
   * Renders a code's customer wording, falling back to {@code en} and then to the code itself.
   *
   * @param code the reason code
   * @param locale the requested BCP 47 language tag
   * @return the wording
   */
  public String render(String code, String locale) {
    Entry entry = entries.get(code);
    if (entry == null) {
      return code;
    }
    Optional<String> exact = Optional.ofNullable(entry.customerText().get(locale));
    if (exact.isPresent()) {
      return exact.get();
    }
    // A tag like "en-IN" falls back to its language before it falls back to the default, so a
    // tenant that has only written "en" still gets English rather than a raw code.
    int dash = locale.indexOf('-');
    if (dash > 0) {
      String language = locale.substring(0, dash).toLowerCase(Locale.ROOT);
      String byLanguage = entry.customerText().get(language);
      if (byLanguage != null) {
        return byLanguage;
      }
    }
    return Optional.ofNullable(entry.customerText().get(FALLBACK_LOCALE)).orElse(code);
  }

  /** Builds a catalogue. */
  public static final class Builder {

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /**
     * Adds an adverse code with its English wording.
     *
     * @param code the reason code
     * @param english the customer wording
     * @return this builder
     */
    public Builder adverse(String code, String english) {
      entries.put(code, Entry.adverse(english));
      return this;
    }

    /**
     * Adds a code that is recorded internally but never disclosed.
     *
     * @param code the reason code
     * @return this builder
     */
    public Builder internal(String code) {
      entries.put(code, Entry.internal());
      return this;
    }

    /**
     * Builds the catalogue.
     *
     * @return the catalogue
     */
    public ReasonCatalogue build() {
      return new ReasonCatalogue(entries);
    }
  }

  /**
   * Starts building a catalogue.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }
}
