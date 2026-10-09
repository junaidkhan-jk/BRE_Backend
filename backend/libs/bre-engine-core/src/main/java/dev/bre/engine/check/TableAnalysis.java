package dev.bre.engine.check;

import dev.bre.engine.bel.parse.Compiled;
import dev.bre.engine.bel.parse.UnaryTest;
import dev.bre.engine.ir.Ir;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Gap, overlap and reachability analysis for decision tables and scorecards (LLD §4.13.1).
 *
 * <p><b>What this buys, and why it is an error rather than a warning.</b> A decision table with a
 * gap does not fail at authoring time; it fails at runtime, on the first application whose inputs
 * land in the hole, as {@code ERROR} — which refers the applicant for a defect in the policy nobody
 * noticed. The author sees an unexplained refer rate and no error anywhere. CHK-002 moves that
 * discovery from production to the editor, with a concrete witness tuple so it can be fixed rather
 * than hunted.
 *
 * <p>The budget exists because box subtraction can fan out: a pathological table could generate an
 * enormous remainder set. §4.13.1 caps it at 50,000 boxes and reports CHK-014 instead of running
 * forever — an analysis that hangs the studio is worse than one that admits it gave up.
 */
public final class TableAnalysis {

  /** §4.13.1's remainder budget, past which the analysis reports CHK-014 and stops. */
  public static final int MAX_BOXES = 50_000;

  /** How many witnesses a gap reports. */
  private static final int MAX_WITNESSES = 10;

  private TableAnalysis() {}

  /**
   * Analyses one decision table.
   *
   * @param table the table
   * @return every finding
   */
  public static List<CheckFinding> analyse(Ir.DecisionTableDef table) {
    List<CheckFinding> findings = new ArrayList<>();
    int columnCount = table.inputs().size();
    if (columnCount == 0 || table.rows().isEmpty()) {
      return findings;
    }

    List<ColumnDomain> domains = new ArrayList<>(columnCount);
    List<List<UnaryTest>> parsed = new ArrayList<>(table.rows().size());
    try {
      for (Ir.TableRow row : table.rows()) {
        List<UnaryTest> cells = new ArrayList<>(columnCount);
        for (int c = 0; c < columnCount; c++) {
          cells.add(Compiled.unaryTest(row.when().get(c)));
        }
        parsed.add(cells);
      }
    } catch (RuntimeException unparseable) {
      // A cell that does not parse is BEL-001 from the parse stage, which runs before this one.
      return List.of(
          CheckFinding.of(
              "CHK-014",
              CheckFinding.Severity.INFO,
              table.code(),
              "analysis skipped: a cell does not parse"));
    }

    for (int c = 0; c < columnCount; c++) {
      final int column = c;
      domains.add(ColumnDomain.of(parsed.stream().map(row -> row.get(column)).toList()));
    }

    List<Box> rows = new ArrayList<>(parsed.size());
    for (List<UnaryTest> cells : parsed) {
      List<BitSet> bits = new ArrayList<>(columnCount);
      for (int c = 0; c < columnCount; c++) {
        bits.add(domains.get(c).cover(cells.get(c)));
      }
      rows.add(new Box(bits));
    }

    boolean anyOpaque = domains.stream().anyMatch(ColumnDomain::isOpaque);
    if (anyOpaque) {
      findings.add(
          CheckFinding.of(
              "CHK-014",
              CheckFinding.Severity.INFO,
              table.code(),
              "a column references a variable or a reference list, so it is opaque to gap"
                  + " analysis and treated as 'any' for overlap"));
    }

    findings.addAll(overlaps(table, rows, domains));
    if (!anyOpaque) {
      findings.addAll(gaps(table, rows, domains));
      // Reachability is unknowable for the same reason a gap is: every cell in an opaque column
      // reads as "any", so every row after the first looks already covered. Reporting that would
      // tell an author to delete rows that are in fact reachable.
      findings.addAll(unreachable(table, rows));
    }
    return List.copyOf(findings);
  }

  /** CHK-001: under UNIQUE, any overlap is an error. Under PRIORITY and COLLECT it is allowed. */
  private static List<CheckFinding> overlaps(
      Ir.DecisionTableDef table, List<Box> rows, List<ColumnDomain> domains) {
    if (table.hitPolicy().kind() != Ir.HitPolicy.Kind.UNIQUE) {
      return List.of();
    }
    List<CheckFinding> findings = new ArrayList<>();
    for (int r = 0; r < rows.size(); r++) {
      for (int s = r + 1; s < rows.size(); s++) {
        if (rows.get(r).intersects(rows.get(s))) {
          List<BitSet> shared = new ArrayList<>();
          for (int c = 0; c < domains.size(); c++) {
            BitSet bits = (BitSet) rows.get(r).columns().get(c).clone();
            bits.and(rows.get(s).columns().get(c));
            shared.add(bits);
          }
          findings.add(
              new CheckFinding(
                  "CHK-001",
                  CheckFinding.Severity.ERROR,
                  table.code(),
                  "UNIQUE table: rows " + (r + 1) + " and " + (s + 1) + " can both match",
                  List.of(new Box(shared).witness(domains))));
        }
      }
    }
    return findings;
  }

  /** CHK-002: a combination of PRESENT values no row matches. */
  private static List<CheckFinding> gaps(
      Ir.DecisionTableDef table, List<Box> rows, List<ColumnDomain> domains) {

    List<BitSet> present = new ArrayList<>(domains.size());
    domains.forEach(domain -> present.add(domain.allPresent()));
    List<Box> remainder = new ArrayList<>(List.of(new Box(present)));

    for (Box row : rows) {
      List<Box> next = new ArrayList<>();
      for (Box box : remainder) {
        next.addAll(box.minus(row));
      }
      if (next.size() > MAX_BOXES) {
        return List.of(
            CheckFinding.of(
                "CHK-014",
                CheckFinding.Severity.INFO,
                table.code(),
                "gap analysis stopped: more than " + MAX_BOXES + " regions to consider"));
      }
      remainder = next;
      if (remainder.isEmpty()) {
        return List.of();
      }
    }

    List<String> witnesses =
        remainder.stream().limit(MAX_WITNESSES).map(box -> box.witness(domains)).toList();
    return List.of(
        new CheckFinding(
            "CHK-002",
            CheckFinding.Severity.ERROR,
            table.code(),
            "the table has a gap: these present-value combinations match no row, and at runtime"
                + " each is an ERROR that refers the application",
            witnesses));
  }

  /** CHK-003: under FIRST, a row no earlier row has already fully covered. */
  private static List<CheckFinding> unreachable(Ir.DecisionTableDef table, List<Box> rows) {
    if (table.hitPolicy().kind() != Ir.HitPolicy.Kind.FIRST) {
      return List.of();
    }
    List<CheckFinding> findings = new ArrayList<>();
    for (int r = 1; r < rows.size(); r++) {
      List<Box> remainder = new ArrayList<>(List.of(rows.get(r)));
      for (int s = 0; s < r && !remainder.isEmpty(); s++) {
        List<Box> next = new ArrayList<>();
        for (Box box : remainder) {
          next.addAll(box.minus(rows.get(s)));
        }
        remainder = next;
      }
      if (remainder.isEmpty()) {
        findings.add(
            CheckFinding.of(
                "CHK-003",
                CheckFinding.Severity.WARNING,
                table.code(),
                "row "
                    + (r + 1)
                    + " is unreachable: earlier rows already match everything it"
                    + " would"));
      }
    }
    return findings;
  }

  /**
   * Analyses a scorecard characteristic as a one-column table (CHK-012).
   *
   * <p>Bins must be pairwise disjoint and cover every present value. Overlapping bins mean the
   * score depends on bin order rather than on the borrower, and a gap means a characteristic
   * silently contributes nothing — which moves a score without anybody choosing to.
   *
   * @param scorecard the scorecard
   * @return every finding
   */
  public static List<CheckFinding> analyse(Ir.ScorecardDef scorecard) {
    List<CheckFinding> findings = new ArrayList<>();
    for (Ir.Characteristic characteristic : scorecard.characteristics()) {
      List<UnaryTest> bins;
      try {
        bins = characteristic.bins().stream().map(b -> Compiled.unaryTest(b.when())).toList();
      } catch (RuntimeException unparseable) {
        continue;
      }
      ColumnDomain domain = ColumnDomain.of(bins);
      if (domain.isOpaque()) {
        findings.add(
            CheckFinding.of(
                "CHK-014",
                CheckFinding.Severity.INFO,
                scorecard.code(),
                "characteristic '" + characteristic.name() + "' has an opaque bin"));
        continue;
      }

      List<Box> boxes = bins.stream().map(b -> new Box(List.of(domain.cover(b)))).toList();
      for (int i = 0; i < boxes.size(); i++) {
        for (int j = i + 1; j < boxes.size(); j++) {
          if (boxes.get(i).intersects(boxes.get(j))) {
            findings.add(
                CheckFinding.of(
                    "CHK-012",
                    CheckFinding.Severity.ERROR,
                    scorecard.code(),
                    "characteristic '"
                        + characteristic.name()
                        + "': bins "
                        + (i + 1)
                        + " and "
                        + (j + 1)
                        + " overlap, so the score would depend on bin order"));
          }
        }
      }

      List<Box> remainder = new ArrayList<>(List.of(new Box(List.of(domain.allPresent()))));
      for (Box bin : boxes) {
        List<Box> next = new ArrayList<>();
        for (Box box : remainder) {
          next.addAll(box.minus(bin));
        }
        remainder = next;
      }
      if (!remainder.isEmpty()) {
        findings.add(
            new CheckFinding(
                "CHK-012",
                CheckFinding.Severity.ERROR,
                scorecard.code(),
                "characteristic '"
                    + characteristic.name()
                    + "' does not cover every present"
                    + " value, so it would silently contribute nothing for these",
                remainder.stream()
                    .limit(MAX_WITNESSES)
                    .map(box -> box.witness(List.of(domain)))
                    .toList()));
      }
    }
    return List.copyOf(findings);
  }
}
