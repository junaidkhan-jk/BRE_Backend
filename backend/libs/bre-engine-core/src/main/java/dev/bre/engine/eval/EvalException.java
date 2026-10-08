package dev.bre.engine.eval;

import java.util.Objects;
import java.util.Optional;

/**
 * A runtime evaluation error (LLD §3.11).
 *
 * <p><b>This exception carries fact paths and types, never fact values</b> (LLD §1.2 rule 3). A
 * value in an exception message leaks PII into every stack trace and log aggregator downstream,
 * where retention and access control are not the engine's to enforce. The constructors therefore
 * accept a {@link String} path and a type <em>name</em>, and the ArchUnit rule {@code
 * EngineArchRules.exceptionsNeverCarryFactValues} fails the build if anyone adds an overload taking
 * a {@link Value}.
 */
public final class EvalException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final EvalErrorCode code;
  private final String factPath;
  private final String typeName;

  /**
   * Creates an evaluation error with no associated fact.
   *
   * @param code the error code
   */
  public EvalException(EvalErrorCode code) {
    this(code, null, null);
  }

  /**
   * Creates an evaluation error naming the fact path and type involved.
   *
   * @param code the error code
   * @param factPath the fact path, e.g. {@code member.income} — a path, never a value
   * @param typeName the name of the type involved, e.g. {@code Decimal} — a type, never a value
   */
  public EvalException(EvalErrorCode code, String factPath, String typeName) {
    super(buildMessage(code, factPath, typeName));
    this.code = Objects.requireNonNull(code, "code");
    this.factPath = factPath;
    this.typeName = typeName;
  }

  private static String buildMessage(EvalErrorCode code, String factPath, String typeName) {
    StringBuilder message = new StringBuilder(code.name());
    if (factPath != null) {
      message.append(" at ").append(factPath);
    }
    if (typeName != null) {
      message.append(" (").append(typeName).append(')');
    }
    return message.toString();
  }

  /** Returns the error code. */
  public EvalErrorCode code() {
    return code;
  }

  /** Returns the fact path involved, if one is known. */
  public Optional<String> factPath() {
    return Optional.ofNullable(factPath);
  }

  /** Returns the name of the type involved, if one is known. */
  public Optional<String> typeName() {
    return Optional.ofNullable(typeName);
  }
}
