package dev.bre.engine.trace;

import dev.bre.engine.eval.Value;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Records an expression's evaluation, or does nothing at all (LLD §4.12).
 *
 * <p><b>{@link #NONE} is the default and costs nothing.</b> Recording is off on the decision path:
 * an explanation is regenerated on demand by re-running with a recorder attached, so the hot path
 * never pays for a trace nobody asked for. That is checked by {@link #isRecording()} rather than by
 * a null test, so a caller cannot forget.
 *
 * <p><b>Masking is decided here, not by the caller</b> (§9.4). A value from a field tagged {@code
 * pii}, {@code protected} or {@code regulated} is recorded as {@code <masked>} unless the caller
 * holds unmask rights with a recorded reason. Doing it at record time rather than at render time
 * means the masked value never exists in the trace object at all — so it cannot be leaked by a
 * logging call, a serialiser, or a debugger session on the way out.
 */
public class TraceRecorder {

  /** A recorder that records nothing. The decision path uses this. */
  public static final TraceRecorder NONE =
      new TraceRecorder(path -> false) {
        @Override
        public boolean isRecording() {
          return false;
        }

        @Override
        public void enter(String src, int start, int end) {
          // nothing
        }

        @Override
        public void exit(Value value, String factPath) {
          // nothing
        }
      };

  private final Predicate<String> isSensitive;
  private final Deque<Frame> stack = new ArrayDeque<>();
  private ExprTrace root;

  private record Frame(String src, int start, int end, List<ExprTrace> children) {}

  /**
   * Creates a recorder.
   *
   * @param isSensitive true when a fact path's value must be masked (§9.1 tags pii, protected,
   *     regulated). A recorder built with {@code path -> false} records every value, which is
   *     correct only for a caller holding unmask rights with a recorded reason (§9.4).
   */
  public TraceRecorder(Predicate<String> isSensitive) {
    this.isSensitive = isSensitive;
  }

  /**
   * A recorder that masks every fact value.
   *
   * <p>The right default for an explanation shown to anyone without unmask rights: the structure
   * and the outcome are visible, the borrower's figures are not.
   *
   * @return the recorder
   */
  public static TraceRecorder masking() {
    return new TraceRecorder(path -> true);
  }

  /**
   * A recorder that records every value.
   *
   * @return the recorder
   */
  public static TraceRecorder unmasked() {
    return new TraceRecorder(path -> false);
  }

  /**
   * Whether this recorder is recording.
   *
   * @return false only for {@link #NONE}
   */
  public boolean isRecording() {
    return true;
  }

  /**
   * Begins a sub-expression.
   *
   * @param src its source text
   * @param start the start offset
   * @param end the end offset
   */
  public void enter(String src, int start, int end) {
    if (stack.size() >= ExprTrace.MAX_DEPTH) {
      // Still pushed, so enter and exit stay balanced; the node is dropped on the way out.
      stack.push(new Frame(src, start, end, new ArrayList<>()));
      return;
    }
    stack.push(new Frame(src, start, end, new ArrayList<>()));
  }

  /**
   * Ends the current sub-expression with the value it produced.
   *
   * @param value the value
   * @param factPath the fact path this value came from, or null when it is computed
   */
  public void exit(Value value, String factPath) {
    Frame frame = stack.pop();
    boolean tooDeep = stack.size() >= ExprTrace.MAX_DEPTH;

    String rendered = render(value, factPath);
    Optional<String> note = noteFor(value);
    ExprTrace node =
        new ExprTrace(
            frame.src(),
            new ExprTrace.Span(frame.start(), frame.end()),
            value.typeName(),
            rendered,
            note,
            tooDeep ? List.of() : frame.children());

    if (stack.isEmpty()) {
      root = node;
    } else if (!tooDeep) {
      stack.peek().children().add(node);
    }
  }

  private String render(Value value, String factPath) {
    if (value.isMissing()) {
      return ExprTrace.MISSING;
    }
    if (factPath != null && isSensitive.test(factPath)) {
      return ExprTrace.MASKED;
    }
    return text(value);
  }

  /**
   * Renders a value as a person would read it.
   *
   * <p>Not {@code toString()}: these are records, so the default renders {@code BoolV[value=true]}.
   * An explanation is read by a credit officer deciding whether to override a decision, and Java
   * record syntax in it is both noise and a small leak of how the engine is built.
   */
  private static String text(Value value) {
    return switch (value) {
      case Value.BoolV bool -> String.valueOf(bool.value());
      case Value.IntV integer -> String.valueOf(integer.value());
      case Value.DecimalV decimal -> decimal.value().toPlainString();
      case Value.TextV string -> string.value();
      case Value.DateV date -> date.value().toString();
      case Value.DateTimeV dateTime -> dateTime.value().toString();
      case Value.PeriodV period -> period.value().toString();
      case Value.ListV list ->
          list.elements().stream()
              .limit(ExprTrace.MAX_LIST_ELEMENTS)
              .map(TraceRecorder::text)
              .toList()
              .toString();
      // A record's field VALUES are facts; rendering them here would bypass the path-based
      // masking entirely, so a record shows its shape and not its contents.
      case Value.RecordV record -> "{" + String.join(", ", record.fields().keySet()) + "}";
      case Value.Missing ignored -> ExprTrace.MISSING;
    };
  }

  private static Optional<String> noteFor(Value value) {
    if (value instanceof Value.ListV list && list.elements().size() > ExprTrace.MAX_LIST_ELEMENTS) {
      return Optional.of(
          "showing the first "
              + ExprTrace.MAX_LIST_ELEMENTS
              + " of "
              + list.elements().size()
              + " elements");
    }
    return Optional.empty();
  }

  /**
   * The recorded trace, once evaluation has finished.
   *
   * @return the root node, or empty when nothing was recorded
   */
  public Optional<ExprTrace> trace() {
    return Optional.ofNullable(root);
  }
}
