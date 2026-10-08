package dev.bre.engine.decide;

import dev.bre.engine.ir.Ir.AuthorityMatrix;
import dev.bre.engine.ir.Ir.RuntimeStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The approvals a decision still needs (LLD §4.9, "Required approvals").
 *
 * <p>Only <b>uncovered</b> deviations count. A deviation an approval already covers reads as PASS
 * after coverage and needs nothing further; asking an approver to approve it again would be asking
 * them to authorise something they already did.
 *
 * <p><b>The routing mode is read from the Authority Matrix version, never from the tenant.</b> It
 * was copied into the matrix when that version was created, which is what makes replay exact: a
 * decision taken in March must reproduce the approvals it actually required then, even if the
 * tenant has since switched from {@code HIGHEST_ONLY} to {@code EACH}. Reading the live tenant
 * setting here would make every historical replay drift the moment someone changed a preference.
 *
 * <p>The two modes answer different operational questions:
 *
 * <ul>
 *   <li>{@code HIGHEST_ONLY} — one approval, at the highest level any deviation requires, covering
 *       all of them. One signature, sequential, and the senior approver sees the whole picture.
 *   <li>{@code EACH} — one approval per deviation, which can run in parallel. Faster, and each
 *       approver sees only what they are authorising.
 * </ul>
 */
public final class RequiredApprovals {

  /** The mode when the matrix names none; §5.3's column default. */
  public static final String DEFAULT_ROUTING_MODE = "HIGHEST_ONLY";

  private RequiredApprovals() {}

  /**
   * One deviation needing approval.
   *
   * @param ruleCode the component that raised it
   * @param deviationType the deviation type
   * @param entityType the entity's type, when the component is per-entity
   * @param entityRef the entity's reference
   * @param level the authority level this deviation requires
   */
  public record Deviation(
      String ruleCode,
      String deviationType,
      Optional<String> entityType,
      Optional<String> entityRef,
      String level) {}

  /**
   * One approval item to be routed.
   *
   * @param level the authority level this item must be approved at
   * @param deviations the deviations it covers — all of them under {@code HIGHEST_ONLY}, exactly
   *     one under {@code EACH}
   */
  public record Item(String level, List<Deviation> deviations) {
    /** Creates an item, copying the list. */
    public Item {
      deviations = List.copyOf(deviations);
    }
  }

  /**
   * Resolves the approvals a decision requires.
   *
   * @param entities every entity result, in flow order then entity order
   * @param matrix the Authority Matrix, when the policy declares one
   * @return the approval items; empty when nothing deviates uncovered
   */
  public static List<Item> resolve(
      List<ComponentResult> entities, Optional<AuthorityMatrix> matrix) {

    List<Deviation> uncovered = uncovered(entities);
    if (uncovered.isEmpty()) {
      return List.of();
    }

    String mode = matrix.map(AuthorityMatrix::routingMode).orElse(DEFAULT_ROUTING_MODE);
    if ("EACH".equals(mode)) {
      // One item per deviation, in flow then entity order — which `uncovered` already preserves.
      return uncovered.stream().map(d -> new Item(d.level(), List.of(d))).toList();
    }

    // HIGHEST_ONLY: one item at the highest level required, covering every deviation.
    String highest = highestLevel(uncovered, matrix);
    return List.of(new Item(highest, uncovered));
  }

  /**
   * The uncovered deviations, in flow order then entity order.
   *
   * <p>A result qualifies when its <em>effective</em> status is still DEVIATION and it carries a
   * required level. Both conditions matter: coverage rewrites an approved deviation's effective
   * status to PASS, and {@link Coverage} sets {@code requiredLevel} only when a matrix band was
   * actually found — a deviation with no band became REFER with {@code DEV_NO_AUTHORITY} and is not
   * an approval anyone can be asked for.
   */
  private static List<Deviation> uncovered(List<ComponentResult> entities) {
    List<Deviation> out = new ArrayList<>();
    for (ComponentResult result : entities) {
      if (result.effectiveStatus() != RuntimeStatus.DEVIATION) {
        continue;
      }
      Optional<String> level = result.requiredLevel();
      Optional<String> deviationType = result.deviationType();
      if (level.isEmpty() || deviationType.isEmpty()) {
        continue;
      }
      out.add(
          new Deviation(
              result.code(),
              deviationType.get(),
              result.entityType(),
              result.entityRef(),
              level.get()));
    }
    return List.copyOf(out);
  }

  /**
   * The highest level among the deviations, by the matrix's own ranks.
   *
   * <p>Ranked by the matrix rather than by the level code's spelling. {@code L10} sorts before
   * {@code L2} alphabetically, and a tenant free to name its levels {@code MANAGER} and {@code
   * BOARD} has no alphabet to sort by at all — so the only correct order is the declared rank. A
   * level the matrix does not declare has no rank, and is treated as the lowest rather than assumed
   * senior, because inventing authority for an unknown code is the dangerous direction.
   */
  private static String highestLevel(List<Deviation> deviations, Optional<AuthorityMatrix> matrix) {
    Comparator<Deviation> byRank =
        Comparator.comparingInt(
            d -> matrix.flatMap(m -> m.rankOf(d.level())).orElse(Integer.MIN_VALUE));
    return deviations.stream()
        .max(byRank)
        .map(Deviation::level)
        .orElseThrow(() -> new IllegalStateException("resolve() checked the list was not empty"));
  }
}
