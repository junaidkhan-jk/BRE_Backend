package dev.bre.conformance;

/**
 * The seam between the conformance suite and the engine.
 *
 * <p>The suite is written against this interface before the engine exists (Build Plan P1: "the
 * specification is executable"). Until {@code bre-engine-core} implements it, {@link
 * #notImplemented()} reports every vector as pending rather than as a failure — the suite's job at
 * M0 is to enumerate the specification completely, not to be red.
 *
 * <p>M1 replaces the implementation here with the real engine, one vector family at a time. The
 * engine is "done" when this suite is green, not when it looks finished.
 */
public interface EngineUnderTest {

  /** Thrown by {@link #notImplemented()} for every vector; the runner turns it into "pending". */
  final class NotImplemented extends UnsupportedOperationException {
    private static final long serialVersionUID = 1L;

    NotImplemented(String vectorId) {
      super("engine not implemented (vector " + vectorId + ")");
    }
  }

  /**
   * Evaluates one vector and returns the actual outcome, shaped like the vector's {@code expect}
   * object so the runner can compare them without knowing the engine's types.
   *
   * @param vector the vector to evaluate
   * @return the actual outcome
   */
  ActualOutcome evaluate(ConformanceVector vector);

  /** The engine stand-in used until M1 lands the real one. */
  static EngineUnderTest notImplemented() {
    return vector -> {
      throw new NotImplemented(vector.id());
    };
  }
}
