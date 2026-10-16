package dev.bre.decision.verify;

/**
 * Something a monitor noticed (M4-07, §11.4).
 *
 * <p><b>A finding is not an alert, and keeping them apart is the point.</b> This service decides
 * what is true; a paging system decides who is woken and when. Conflating them would put an on-call
 * policy inside a credit engine, where it would be versioned with the engine and changed by whoever
 * was changing the engine.
 *
 * @param monitor which monitor, e.g. {@code DECISION_MIX}
 * @param severity {@code PAGE}, {@code TICKET} or {@code NOTIFY}, as §11.4 assigns them
 * @param tenantId the tenant it concerns
 * @param summary one sentence, naming the measurement and the threshold it crossed
 * @param observed what was measured
 * @param baseline what it was compared against
 */
public record Finding(
    String monitor,
    String severity,
    String tenantId,
    String summary,
    double observed,
    double baseline) {

  /** Paged on. §11.4's ERROR rate, and a replay mismatch. */
  public static final String PAGE = "PAGE";

  /** Ticketed. A drift that needs looking at this week rather than tonight. */
  public static final String TICKET = "TICKET";

  /** The tenant is told. An upstream data problem is theirs to fix, not the platform's. */
  public static final String NOTIFY = "NOTIFY";
}
