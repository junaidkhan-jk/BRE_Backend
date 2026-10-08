package dev.bre.engine.decide;

import dev.bre.engine.eval.Comparisons;
import dev.bre.engine.eval.Value;
import dev.bre.engine.eval.Value.DateV;
import dev.bre.engine.eval.Value.ListV;
import dev.bre.engine.eval.Value.RecordV;
import dev.bre.engine.eval.Value.TextV;
import dev.bre.engine.ir.Ir.AuthorityEntry;
import dev.bre.engine.ir.Ir.AuthorityMatrix;
import dev.bre.engine.ir.Ir.RuntimeStatus;
import dev.bre.engine.ir.Ir.WorseWhen;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Deviation coverage and authority resolution (LLD §4.9).
 *
 * <p>Resolved <b>per entity, before aggregation</b>, for every entity whose raw status is {@code
 * DEVIATION}. Two things come out of it: which authority level the deviation needs, and whether an
 * approval already covers it.
 *
 * <p>The coverage predicate is strict on purpose, and every clause of it is a way a credit approval
 * could otherwise be stretched beyond what the approver agreed to:
 *
 * <ul>
 *   <li><b>Bound to a value, not to a rule.</b> An approval carries the value that was approved,
 *       and covers only while the measure is no worse than it (S8-02). An approver who allows ₹2.15
 *       lakh of indebtedness has not allowed ₹2.16 lakh.
 *   <li><b>Bound to one entity.</b> An approval for member P-0003 does not cover P-0004 (S8-03).
 *   <li><b>Expires.</b> Past {@code expiresOn}, it covers nothing (S8-04).
 *   <li><b>Bound to a level.</b> An L1 approval cannot cover a deviation needing L2 (S8-05).
 *   <li><b>Never covers a FAIL</b> (S8-06). A FAIL has no measure, so there is no tolerance to
 *       approve — the application failed the rule outright.
 * </ul>
 */
final class Coverage {

  private Coverage() {}

  /** The reason when the authority amount itself is missing (§4.9). */
  static final String DATA_MISSING_AUTHORITY_AMOUNT = "DATA_MISSING_AUTHORITY_AMOUNT";

  /** The reason when no matrix band governs the deviation (§4.9, PRD FR-DEV-004). */
  static final String DEV_NO_AUTHORITY = "DEV_NO_AUTHORITY";

  /** The annotation on an approved deviation (§4.9). */
  static final String DEVIATION_APPROVED = "DEVIATION_APPROVED";

  /**
   * Applies coverage and authority to one entity result.
   *
   * @param result the raw entity result
   * @param matrix the Authority Matrix, if the policy declares one
   * @param authorityAmount the resolved authority amount, if the policy declares the expression
   * @param facts the fact snapshot, read for {@code deviationApprovals} and the application
   * @param asOf the evaluation date
   * @return the result after coverage: unchanged unless the raw status was DEVIATION
   */
  static ComponentResult apply(
      ComponentResult result,
      Optional<AuthorityMatrix> matrix,
      Optional<Value> authorityAmount,
      RecordV facts,
      LocalDate asOf) {

    if (result.status() != RuntimeStatus.DEVIATION) {
      return result; // Only a DEVIATION is coverable. A FAIL never is (S8-06).
    }

    // NO MATRIX DECLARED. §4.9's algorithm assumes one, and the LLD does not say what to do
    // without it (docs/OPEN-QUESTIONS.md Q1). Adopted reading: a policy that declares no matrix
    // states no authority requirement, so there is no level clause to satisfy and coverage is
    // decided on the remaining clauses — rule, deviation type, entity, expiry and approved value.
    // The alternative, treating every deviation as unapprovable, would make S7-03 and five
    // sibling vectors unreachable for a correct engine. A real policy always declares a matrix
    // (CHK-011 enforces that it covers the whole amount range), so this path is for fragments.
    if (matrix.isEmpty()) {
      return coveringWithoutAuthority(result, facts, asOf)
          .map(approval -> result.approved(DEVIATION_APPROVED, approvalRefOf(approval)))
          .orElse(result);
    }

    if (authorityAmount.isEmpty() || authorityAmount.get().isMissing()) {
      return result.withEffectiveStatus(RuntimeStatus.DATA_MISSING, DATA_MISSING_AUTHORITY_AMOUNT);
    }

    Optional<String> level =
        resolveAuthority(
            matrix.get(),
            result.deviationType().orElse(null),
            textField(facts, "application", "productLine"),
            textField(facts, "application", "segment"),
            authorityAmount.get());

    if (level.isEmpty()) {
      // No band governs this deviation: nobody in the bank is identified as able to approve it,
      // so it goes to a human rather than being treated as approvable (§4.9).
      return result.withEffectiveStatus(RuntimeStatus.REFER, DEV_NO_AUTHORITY);
    }

    Optional<RecordV> approval = covering(result, facts, asOf, level.get(), matrix.get());

    if (approval.isPresent()) {
      return result.approved(DEVIATION_APPROVED, approvalRefOf(approval.get()));
    }
    return result.requiring(level.get());
  }

  private static String approvalRefOf(RecordV approval) {
    return approval.field("approvalRef") instanceof TextV ref ? ref.value() : null;
  }

  /** Coverage with every clause but the authority level, for a fragment with no matrix. */
  private static Optional<RecordV> coveringWithoutAuthority(
      ComponentResult result, RecordV facts, LocalDate asOf) {
    if (result.measure().isEmpty()) {
      return Optional.empty();
    }
    for (RecordV approval : approvalsOf(facts)) {
      if (matchesExceptLevel(approval, result, asOf)) {
        return Optional.of(approval);
      }
    }
    return Optional.empty();
  }

  /**
   * {@code resolveAuthority} (§4.9): the level whose band contains the amount, most specific first.
   *
   * <p>Specificity is {@code 2·[productLine exact] + [segment exact]}, so a band written for this
   * product line beats a wildcard one, and bands of one key cannot overlap.
   */
  private static Optional<String> resolveAuthority(
      AuthorityMatrix matrix,
      String deviationType,
      Optional<String> productLine,
      Optional<String> segment,
      Value amount) {

    if (deviationType == null) {
      return Optional.empty();
    }
    BigDecimal value = toDecimal(amount);
    if (value == null) {
      return Optional.empty();
    }

    return matrix.entries().stream()
        .filter(entry -> entry.deviationType().equals(deviationType))
        .filter(entry -> keyMatches(entry.productLine(), productLine))
        .filter(entry -> keyMatches(entry.segment(), segment))
        .filter(entry -> bandContains(entry, value))
        .max(Comparator.comparingInt(entry -> specificity(entry, productLine, segment)))
        .map(AuthorityEntry::levelCode);
  }

  /** A matrix key matches the application's value, or is the {@code *} wildcard. */
  private static boolean keyMatches(String entryKey, Optional<String> actual) {
    if ("*".equals(entryKey)) {
      return true;
    }
    // §4.9: a null segment matches only '*', which the wildcard branch above already handled.
    return actual.isPresent() && actual.get().equals(entryKey);
  }

  /** {@code amountAbove < amount <= amountUpTo}, where an absent bound is unbounded. */
  private static boolean bandContains(AuthorityEntry entry, BigDecimal amount) {
    if (entry.amountAbove().isPresent() && amount.compareTo(entry.amountAbove().get()) <= 0) {
      return false;
    }
    return entry.amountUpTo().isEmpty() || amount.compareTo(entry.amountUpTo().get()) <= 0;
  }

  private static int specificity(
      AuthorityEntry entry, Optional<String> productLine, Optional<String> segment) {
    int score = 0;
    if (productLine.isPresent() && productLine.get().equals(entry.productLine())) {
      score += 2;
    }
    if (segment.isPresent() && segment.get().equals(entry.segment())) {
      score += 1;
    }
    return score;
  }

  /** {@code covered(...)} (§4.9): the first approval in input order that satisfies every clause. */
  private static Optional<RecordV> covering(
      ComponentResult result,
      RecordV facts,
      LocalDate asOf,
      String requiredLevel,
      AuthorityMatrix matrix) {

    if (result.measure().isEmpty()) {
      return Optional.empty(); // No measure, nothing to approve against (S8-06).
    }
    if (!(facts.field("deviationApprovals") instanceof ListV approvals)) {
      return Optional.empty();
    }

    Optional<Integer> requiredRank = matrix.rankOf(requiredLevel);
    if (requiredRank.isEmpty()) {
      return Optional.empty();
    }

    for (Value element : approvals.elements()) {
      if (element instanceof RecordV approval
          && matches(approval, result, asOf, requiredRank.get(), matrix)) {
        return Optional.of(approval);
      }
    }
    return Optional.empty();
  }

  private static boolean matches(
      RecordV approval,
      ComponentResult result,
      LocalDate asOf,
      int requiredRank,
      AuthorityMatrix matrix) {

    if (!matchesExceptLevel(approval, result, asOf)) {
      return false;
    }

    // Level: the approver's rank must reach the required one. An unknown level code never matches.
    Optional<String> approvedLevel = text(approval.field("approvedLevel"));
    if (approvedLevel.isEmpty()) {
      return false;
    }
    Optional<Integer> approvedRank = matrix.rankOf(approvedLevel.get());
    return approvedRank.isPresent() && approvedRank.get() >= requiredRank;
  }

  /** Every coverage clause except the authority level (§4.9). */
  private static boolean matchesExceptLevel(
      RecordV approval, ComponentResult result, LocalDate asOf) {
    if (!sameText(approval, "ruleCode", result.code())
        || !sameText(approval, "deviationType", result.deviationType().orElse(null))
        || !sameText(approval, "entityType", result.entityType().orElse(null))
        || !sameText(approval, "entityRef", result.entityRef().orElse(null))) {
      return false;
    }

    // Expiry: the approval must state one, and asOf must be on or before it (S8-04).
    Optional<LocalDate> expiresOn = date(approval.field("expiresOn"));
    if (expiresOn.isEmpty() || asOf.isAfter(expiresOn.get())) {
      return false;
    }

    Value approvedValue = approval.field("approvedValue");
    if (approvedValue.isMissing()) {
      return false;
    }

    // The measure must be no worse than the approved value, in whichever direction the outcome
    // declared as worse. A categorical measure matches by equality (S8-07).
    Value measure = result.measure().orElseThrow();
    Optional<WorseWhen> worseWhen = result.worseWhen();
    if (worseWhen.isPresent()) {
      Value approvedAsMeasure = coerce(approvedValue, measure);
      int order = Comparisons.order(measure, approvedAsMeasure);
      boolean withinTolerance = worseWhen.get() == WorseWhen.HIGHER ? order <= 0 : order >= 0;
      if (!withinTolerance) {
        return false;
      }
    } else if (!Comparisons.areEqual(measure, coerce(approvedValue, measure))) {
      return false;
    }
    return true;
  }

  /**
   * Reads an approved value at the measure's type (§4.9: "parsed with the measure's static type").
   *
   * <p>An approval arrives as JSON, so {@code "215000"} may be a string where the measure is a
   * Decimal. Comparing them as written would make every numeric approval fail to cover.
   */
  private static Value coerce(Value approvedValue, Value measure) {
    if (Comparisons.isNumeric(measure) && approvedValue instanceof TextV text) {
      return Value.DecimalV.of(text.value());
    }
    if (measure instanceof DateV && approvedValue instanceof TextV text) {
      return new DateV(LocalDate.parse(text.value()));
    }
    return approvedValue;
  }

  private static boolean sameText(RecordV approval, String field, String expected) {
    if (expected == null) {
      return false;
    }
    return text(approval.field(field)).filter(expected::equals).isPresent();
  }

  private static Optional<String> text(Value value) {
    return value instanceof TextV text ? Optional.of(text.value()) : Optional.empty();
  }

  private static Optional<LocalDate> date(Value value) {
    if (value instanceof DateV date) {
      return Optional.of(date.value());
    }
    if (value instanceof TextV text) {
      try {
        return Optional.of(LocalDate.parse(text.value()));
      } catch (RuntimeException notADate) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  private static Optional<String> textField(RecordV facts, String root, String field) {
    return facts.field(root) instanceof RecordV record
        ? text(record.field(field))
        : Optional.empty();
  }

  private static BigDecimal toDecimal(Value value) {
    if (value instanceof Value.DecimalV decimal) {
      return decimal.value();
    }
    if (value instanceof Value.IntV integer) {
      return BigDecimal.valueOf(integer.value());
    }
    if (value instanceof TextV text) {
      try {
        return new BigDecimal(text.value());
      } catch (NumberFormatException notANumber) {
        return null;
      }
    }
    return null;
  }

  /** Approvals are matched in input order; the list is kept for the required-approvals report. */
  static List<RecordV> approvalsOf(RecordV facts) {
    if (!(facts.field("deviationApprovals") instanceof ListV approvals)) {
      return List.of();
    }
    return approvals.elements().stream()
        .filter(RecordV.class::isInstance)
        .map(RecordV.class::cast)
        .toList();
  }
}
