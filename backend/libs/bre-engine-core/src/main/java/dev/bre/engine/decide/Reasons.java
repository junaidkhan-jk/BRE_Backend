package dev.bre.engine.decide;

import dev.bre.engine.ir.Ir.RuntimeStatus;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reason selection (LLD §4.10).
 *
 * <p><b>Customer reasons are produced only when the decision is DECLINE.</b> That is the first line
 * of §4.10 and the most important: an approved or referred application has not been refused
 * anything, so there is nothing to disclose, and a "reason" attached to one would be read as a
 * refusal that never happened.
 *
 * <p>Internal reasons — every non-PASS reason code, in flow order — are always returned. The two
 * lists are deliberately different things: the internal list is the audit trail and is complete,
 * while the customer list is a reviewed, ordered, capped subset meant to be read by the applicant.
 *
 * <p>Three filters stand between the two, and each exists for a reason worth keeping:
 *
 * <ul>
 *   <li><b>Only FAIL contributes.</b> A deviation is a tolerance someone may approve and a REFER is
 *       a question, neither of which is a ground for refusal.
 *   <li><b>System codes are dropped.</b> {@code SYS_*}, {@code DEV_NO_AUTHORITY} and {@code
 *       DATA_MISSING_*} describe the engine's own state — a missing fact, an unmapped authority, a
 *       knockout skip. Telling an applicant they were declined because of {@code
 *       SYS_SKIPPED_KNOCKOUT} is both meaningless and wrong.
 *   <li><b>The catalogue's {@code adverse} flag is honoured.</b> See {@link ReasonCatalogue}.
 * </ul>
 */
public final class Reasons {

  /** Codes naming an absent fact rather than a credit ground (§4.10 step 3). */
  private static final String DATA_MISSING_PREFIX = "DATA_MISSING_";

  /** Codes naming the engine's own state (§4.10 step 3). */
  private static final String SYSTEM_PREFIX = "SYS_";

  private Reasons() {}

  /**
   * One reason, ordered and rendered.
   *
   * @param code the reason code
   * @param text the customer wording in the requested locale
   */
  public record CustomerReason(String code, String text) {}

  /**
   * An entity result with the ordering keys §4.10 needs.
   *
   * <p>The priority is the <em>component's</em>, not the entity's, and an absent priority sorts
   * last — §4.10's {@code rule.priority ?: ∞}. Carrying the stage and step indices means the order
   * is the flow's order and not the order a map happened to iterate in, which is what makes the
   * reason list reproducible on replay.
   *
   * @param result the entity result
   * @param priority the component's declared priority, empty when it declares none
   * @param stageIndex the stage's position in the flow
   * @param stepIndex the step's position in its stage
   */
  public record Ordered(
      ComponentResult result, Optional<Integer> priority, int stageIndex, int stepIndex) {}

  /**
   * Selects the customer-facing reasons (§4.10).
   *
   * @param decision the overall decision; anything but DECLINE yields an empty list
   * @param entities every entity result, with its ordering keys
   * @param catalogue the reason catalogue
   * @param limit {@code settings.customerReasonLimit}
   * @param locale the requested BCP 47 language tag
   * @return the reasons, in disclosure order, at most {@code limit} of them
   */
  public static List<CustomerReason> customerReasons(
      Outcomes.Decision decision,
      List<Ordered> entities,
      ReasonCatalogue catalogue,
      int limit,
      String locale) {

    if (decision != Outcomes.Decision.DECLINE || limit <= 0) {
      return List.of();
    }

    // Step 1: (priority ?: infinity, stageIndex, stepIndex), then entities in input order.
    // sorted() is stable, so entities within one step keep the order they were evaluated in.
    List<Ordered> ordered =
        entities.stream()
            .sorted(
                java.util.Comparator.<Ordered>comparingInt(
                        o -> o.priority().orElse(Integer.MAX_VALUE))
                    .thenComparingInt(Ordered::stageIndex)
                    .thenComparingInt(Ordered::stepIndex))
            .toList();

    Set<String> seen = new LinkedHashSet<>();
    List<CustomerReason> out = new ArrayList<>(Math.min(limit, 8));
    for (Ordered entry : ordered) {
      // Step 2: the effective status, not the raw one. A deviation that an approval covered reads
      // as PASS and must not surface as a ground for refusal.
      if (entry.result().effectiveStatus() != RuntimeStatus.FAIL) {
        continue;
      }
      Optional<String> code = entry.result().reasonCode();
      if (code.isEmpty() || isSystemCode(code.get()) || !catalogue.isAdverse(code.get())) {
        continue;
      }
      // Step 4: de-duplicate keeping the first occurrence, then cap.
      if (seen.add(code.get())) {
        out.add(new CustomerReason(code.get(), catalogue.render(code.get(), locale)));
        if (out.size() == limit) {
          break;
        }
      }
    }
    return List.copyOf(out);
  }

  /**
   * Every non-PASS reason code, in flow order, de-duplicated (§4.10's closing line).
   *
   * <p>Unlike the customer list this is not filtered, not capped and not rendered. It is the record
   * of what the engine actually found, and an auditor asking "why did this application refer" needs
   * {@code DATA_MISSING_AUTHORITY_AMOUNT} in it, which is precisely the kind of code the customer
   * list drops.
   *
   * @param entities every entity result, in flow order
   * @return the internal reason codes
   */
  public static List<String> internalReasons(List<Ordered> entities) {
    Set<String> out = new LinkedHashSet<>();
    for (Ordered entry : entities) {
      if (entry.result().effectiveStatus() == RuntimeStatus.PASS
          || entry.result().effectiveStatus() == RuntimeStatus.NOT_APPLICABLE) {
        continue;
      }
      entry.result().reasonCode().ifPresent(out::add);
    }
    return List.copyOf(out);
  }

  private static boolean isSystemCode(String code) {
    return code.startsWith(SYSTEM_PREFIX)
        || code.startsWith(DATA_MISSING_PREFIX)
        || Coverage.DEV_NO_AUTHORITY.equals(code);
  }
}
