package dev.bre.engine.check;

import dev.bre.engine.bel.ast.Expr;
import dev.bre.engine.bel.parse.UnaryTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The elementary pieces one decision-table column can be split into (LLD §4.13.1).
 *
 * <p>The whole gap-and-overlap analysis rests on this one idea: a column's cells only ever mention
 * finitely many constants, so the infinitely many possible inputs collapse into finitely many
 * <em>pieces</em> that every cell either wholly contains or wholly excludes. Once a column is
 * pieces, a cell is a bitset, a row is a tuple of bitsets, and gaps and overlaps become set algebra
 * that terminates.
 *
 * <p><b>Numeric.</b> Bounds {@code b1 < … < bk} give {@code (−∞,b1), [b1], (b1,b2), …, [bk],
 * (bk,+∞)} — 2k+1 pieces. The singleton pieces are what make {@code < 700} and {@code <= 700}
 * distinguishable, which is exactly the boundary a credit rule is most often wrong at.
 *
 * <p><b>Categorical.</b> Every literal mentioned, plus {@code OTHER} for the unlisted values. The
 * {@code OTHER} piece is why a table over free text can still be shown to have a gap: "every value
 * nobody wrote down" is a real input a borrower can arrive with.
 *
 * <p>Every column also carries a <b>MISSING</b> piece, kept separate because a gap that is only
 * over MISSING is not an error — it falls to {@code onMissing} (S5).
 */
public final class ColumnDomain {

  /** What kind of values a column holds. */
  public enum Kind {
    /** Integer, Decimal or Date compared by ordering. */
    NUMERIC,
    /** Enum, Boolean or Text compared only by equality or membership. */
    CATEGORICAL,
    /** A cell referenced a variable, so the column cannot be analysed (CHK-014). */
    OPAQUE
  }

  private final Kind kind;
  private final List<String> pieceNames;
  private final List<BigDecimal> bounds;
  private final List<String> categories;
  private final int missingIndex;

  private ColumnDomain(
      Kind kind, List<String> pieceNames, List<BigDecimal> bounds, List<String> categories) {
    this.kind = kind;
    this.pieceNames = List.copyOf(pieceNames);
    this.bounds = List.copyOf(bounds);
    this.categories = List.copyOf(categories);
    this.missingIndex = pieceNames.size() - 1;
  }

  /**
   * Builds the domain for one column from every cell in it.
   *
   * @param cells the column's cells, one per row
   * @return the domain
   */
  public static ColumnDomain of(List<UnaryTest> cells) {
    if (cells.stream().anyMatch(ColumnDomain::isOpaque)) {
      return new ColumnDomain(Kind.OPAQUE, List.of("ANY", "MISSING"), List.of(), List.of());
    }

    TreeSet<BigDecimal> numericBounds = new TreeSet<>();
    Set<String> literals = new LinkedHashSet<>();
    cells.forEach(cell -> collect(cell, numericBounds, literals));

    if (!numericBounds.isEmpty()) {
      List<String> names = new ArrayList<>();
      List<BigDecimal> ordered = new ArrayList<>(numericBounds);
      names.add("(-inf," + ordered.get(0) + ")");
      for (int i = 0; i < ordered.size(); i++) {
        names.add("[" + ordered.get(i) + "]");
        names.add(
            i + 1 < ordered.size()
                ? "(" + ordered.get(i) + "," + ordered.get(i + 1) + ")"
                : "(" + ordered.get(i) + ",+inf)");
      }
      names.add("MISSING");
      return new ColumnDomain(Kind.NUMERIC, names, ordered, List.of());
    }

    List<String> names = new ArrayList<>(literals);
    // A Boolean column is CLOSED: true and false, and nothing else. Adding an OTHER piece to it
    // invents a third value that cannot exist, and then reports every total boolean table as
    // having a gap — which is what it did to the specification's own E17 screening table. An
    // enum is closed in the same way, but the engine cannot tell an enum from free text without
    // the fact schema, so only the boolean case is closed here.
    boolean closed =
        !literals.isEmpty()
            && literals.stream().allMatch(value -> "true".equals(value) || "false".equals(value));
    if (!closed) {
      names.add("OTHER");
    }
    names.add("MISSING");
    return new ColumnDomain(Kind.CATEGORICAL, names, List.of(), List.copyOf(literals));
  }

  /** Whether a cell mentions something the analysis cannot resolve to constants. */
  private static boolean isOpaque(UnaryTest cell) {
    return switch (cell) {
      case UnaryTest.Compare compare -> !isConstant(compare.operand());
      case UnaryTest.Range range -> !isConstant(range.low()) || !isConstant(range.high());
      // `in list.NAME` is resolvable only with the asset snapshot, which the engine does not hold.
      case UnaryTest.In in ->
          !(in.target() instanceof Expr.ListLit list)
              || list.elements().stream().anyMatch(e -> !isConstant(e));
      case UnaryTest.AnyOf anyOf -> anyOf.alternatives().stream().anyMatch(ColumnDomain::isOpaque);
      case UnaryTest.AnyPresent ignored -> false;
      case UnaryTest.IsMissing ignored -> false;
    };
  }

  private static boolean isConstant(Expr expr) {
    return expr instanceof Expr.IntLit
        || expr instanceof Expr.DecimalLit
        || expr instanceof Expr.StringLit
        || expr instanceof Expr.BoolLit
        || (expr instanceof Expr.Negate negate && isConstant(negate.operand()));
  }

  private static void collect(UnaryTest cell, Set<BigDecimal> bounds, Set<String> literals) {
    switch (cell) {
      case UnaryTest.Compare compare -> value(compare.operand(), bounds, literals);
      case UnaryTest.Range range -> {
        value(range.low(), bounds, literals);
        value(range.high(), bounds, literals);
      }
      case UnaryTest.In in -> {
        if (in.target() instanceof Expr.ListLit list) {
          list.elements().forEach(e -> value(e, bounds, literals));
        }
      }
      case UnaryTest.AnyOf anyOf -> anyOf.alternatives().forEach(a -> collect(a, bounds, literals));
      default -> {
        // `-` and `missing` introduce no constants.
      }
    }
  }

  private static void value(Expr expr, Set<BigDecimal> bounds, Set<String> literals) {
    switch (expr) {
      case Expr.IntLit lit -> bounds.add(BigDecimal.valueOf(lit.value()));
      case Expr.DecimalLit lit -> bounds.add(lit.value());
      case Expr.Negate negate -> {
        Set<BigDecimal> inner = new TreeSet<>();
        value(negate.operand(), inner, literals);
        inner.forEach(v -> bounds.add(v.negate()));
      }
      case Expr.StringLit lit -> literals.add(lit.value());
      case Expr.BoolLit lit -> literals.add(String.valueOf(lit.value()));
      default -> {
        // Non-constant operands already made the column opaque.
      }
    }
  }

  /**
   * The bitset a cell covers over this column's pieces.
   *
   * @param cell the cell
   * @return the pieces it matches
   */
  public BitSet cover(UnaryTest cell) {
    BitSet bits = new BitSet(pieceNames.size());
    switch (cell) {
      case UnaryTest.AnyPresent ignored -> bits.set(0, missingIndex); // every present piece
      case UnaryTest.IsMissing ignored -> bits.set(missingIndex);
      case UnaryTest.AnyOf anyOf -> anyOf.alternatives().forEach(a -> bits.or(cover(a)));
      case UnaryTest.Compare compare -> coverCompare(compare, bits);
      case UnaryTest.Range range -> coverRange(range, bits);
      case UnaryTest.In in -> coverIn(in, bits);
    }
    return bits;
  }

  private void coverCompare(UnaryTest.Compare compare, BitSet bits) {
    if (kind == Kind.OPAQUE) {
      bits.set(0);
      return;
    }
    if (kind == Kind.CATEGORICAL) {
      String literal = literalOf(compare.operand());
      int index = categories.indexOf(literal);
      switch (compare.op()) {
        case EQ -> {
          if (index >= 0) {
            bits.set(index);
          }
        }
        case NE -> {
          bits.set(0, missingIndex);
          if (index >= 0) {
            bits.clear(index);
          }
        }
        default -> bits.set(0, missingIndex); // ordering on a category is BEL-010 upstream
      }
      return;
    }

    BigDecimal bound = decimalOf(compare.operand());
    int point = pieceIndexOfBound(bound);
    switch (compare.op()) {
      case EQ -> bits.set(point);
      case NE -> {
        bits.set(0, missingIndex);
        bits.clear(point);
      }
      case LT -> bits.set(0, point);
      case LE -> bits.set(0, point + 1);
      case GT -> bits.set(point + 1, missingIndex);
      case GE -> bits.set(point, missingIndex);
    }
  }

  private void coverRange(UnaryTest.Range range, BitSet bits) {
    if (kind != Kind.NUMERIC) {
      bits.set(0, missingIndex);
      return;
    }
    int low = pieceIndexOfBound(decimalOf(range.low()));
    int high = pieceIndexOfBound(decimalOf(range.high()));
    bits.set(range.lowInclusive() ? low : low + 1, range.highInclusive() ? high + 1 : high);
  }

  private void coverIn(UnaryTest.In in, BitSet bits) {
    BitSet members = new BitSet(pieceNames.size());
    if (in.target() instanceof Expr.ListLit list) {
      for (Expr element : list.elements()) {
        if (kind == Kind.CATEGORICAL) {
          int index = categories.indexOf(literalOf(element));
          if (index >= 0) {
            members.set(index);
          }
        } else if (kind == Kind.NUMERIC) {
          members.set(pieceIndexOfBound(decimalOf(element)));
        }
      }
    }
    if (in.negated()) {
      bits.set(0, missingIndex);
      bits.andNot(members);
    } else {
      bits.or(members);
    }
  }

  /** The index of the singleton piece {@code [bound]}. */
  private int pieceIndexOfBound(BigDecimal bound) {
    for (int i = 0; i < bounds.size(); i++) {
      if (bounds.get(i).compareTo(bound) == 0) {
        return 1 + 2 * i;
      }
    }
    return 0;
  }

  private static String literalOf(Expr expr) {
    return switch (expr) {
      case Expr.StringLit lit -> lit.value();
      case Expr.BoolLit lit -> String.valueOf(lit.value());
      default -> "";
    };
  }

  private static BigDecimal decimalOf(Expr expr) {
    return switch (expr) {
      case Expr.IntLit lit -> BigDecimal.valueOf(lit.value());
      case Expr.DecimalLit lit -> lit.value();
      case Expr.Negate negate -> decimalOf(negate.operand()).negate();
      default -> BigDecimal.ZERO;
    };
  }

  /** The bitset covering every present piece — the starting universe for a gap search. */
  public BitSet allPresent() {
    BitSet bits = new BitSet(pieceNames.size());
    bits.set(0, missingIndex);
    return bits;
  }

  /** How many pieces this column has, including MISSING. */
  public int size() {
    return pieceNames.size();
  }

  /** Whether the column could not be analysed. */
  public boolean isOpaque() {
    return kind == Kind.OPAQUE;
  }

  /**
   * A readable representative of one piece, for a gap witness.
   *
   * @param piece the piece index
   * @return something an author can recognise
   */
  public String witness(int piece) {
    return piece >= 0 && piece < pieceNames.size() ? pieceNames.get(piece) : "?";
  }
}
