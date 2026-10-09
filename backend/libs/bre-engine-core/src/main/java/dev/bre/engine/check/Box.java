package dev.bre.engine.check;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * A region of the input space: one bitset per column (LLD §4.13.1).
 *
 * <p>A decision-table row is a box. So is "everything a table has not covered yet". Gap analysis is
 * then just repeated box subtraction until either nothing is left — the table is total — or
 * something is, which is a combination of inputs no row matches.
 *
 * <p>Box difference is the one piece of real algebra here. {@code u ∖ r} is not a box in general,
 * but it decomposes into at most C disjoint boxes: for each column i, take the part of {@code u}
 * that agrees with {@code r} on columns before i and disagrees on column i. That decomposition is
 * why the analysis terminates on a 16-column table instead of enumerating an input space.
 */
public record Box(List<BitSet> columns) {

  /** Creates a box, copying each column's bitset so the box is immutable. */
  public Box {
    List<BitSet> copies = new ArrayList<>(columns.size());
    columns.forEach(bits -> copies.add((BitSet) bits.clone()));
    columns = List.copyOf(copies);
  }

  /** Whether this box contains no points at all. */
  public boolean isEmpty() {
    return columns.stream().anyMatch(BitSet::isEmpty);
  }

  /**
   * Whether two boxes share any point.
   *
   * <p>{@code B(r) ∩ B(s) ≠ ∅ ⇔ ∀c: r_c ∩ s_c ≠ ∅} — they overlap only if they overlap in
   * <em>every</em> column, which is what makes the test cheap.
   *
   * @param other the other box
   * @return true when they intersect
   */
  public boolean intersects(Box other) {
    for (int i = 0; i < columns.size(); i++) {
      if (!columns.get(i).intersects(other.columns.get(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * {@code this ∖ other}, as at most one box per column.
   *
   * @param other the box to remove
   * @return disjoint boxes covering exactly what is left
   */
  public List<Box> minus(Box other) {
    if (!intersects(other)) {
      return List.of(this);
    }
    List<Box> remainder = new ArrayList<>(columns.size());
    for (int i = 0; i < columns.size(); i++) {
      BitSet difference = (BitSet) columns.get(i).clone();
      difference.andNot(other.columns.get(i));
      if (difference.isEmpty()) {
        continue;
      }
      List<BitSet> piece = new ArrayList<>(columns.size());
      for (int c = 0; c < columns.size(); c++) {
        if (c < i) {
          BitSet shared = (BitSet) columns.get(c).clone();
          shared.and(other.columns.get(c));
          piece.add(shared);
        } else if (c == i) {
          piece.add(difference);
        } else {
          piece.add(columns.get(c));
        }
      }
      Box box = new Box(piece);
      if (!box.isEmpty()) {
        remainder.add(box);
      }
    }
    return List.copyOf(remainder);
  }

  /**
   * One representative point, as readable piece names.
   *
   * @param domains the column domains
   * @return a witness tuple
   */
  public String witness(List<ColumnDomain> domains) {
    List<String> parts = new ArrayList<>(columns.size());
    for (int i = 0; i < columns.size(); i++) {
      parts.add(domains.get(i).witness(columns.get(i).nextSetBit(0)));
    }
    return "(" + String.join(", ", parts) + ")";
  }
}
