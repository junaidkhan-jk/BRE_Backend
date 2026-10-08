package dev.bre.engine.ir;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The PolicyVersion IR (LLD §2.2), as records.
 *
 * <p>Field names are the schema's names, exactly: a component's identifier is {@code code}, a flow
 * step points at one with {@code ref}, and a rule's failure outcome is {@code onFail}. Renaming any
 * of them for Java taste would create two words for one thing and a bug at every boundary — the IR
 * JSON, the vectors, the studio and the audit trail all use these spellings.
 *
 * <p>Everything here is immutable and carries no behaviour. The IR is data that a compiled policy
 * is built <em>from</em> (§4.1); evaluation lives in {@code dev.bre.engine.decide}.
 */
public final class Ir {

  private Ir() {}

  /** Entity scope a component is evaluated at (§2.2 {@code $defs/scope}). */
  public enum Scope {
    /** Once per application. */
    APPLICATION,
    /** Once per party. */
    PARTY,
    /** Once per group. */
    GROUP,
    /** Once per group member. */
    MEMBER,
    /** Once per household. */
    HOUSEHOLD,
    /** Once per collateral item. */
    COLLATERAL
  }

  /** A status an author may assign in an outcome. Narrower than a runtime status. */
  public enum OutcomeStatus {
    /** The component passed. */
    PASS,
    /** Failed within an approvable tolerance. */
    DEVIATION,
    /** Send for review. */
    REFER,
    /** Failed. */
    FAIL
  }

  /**
   * What to do when a component cannot be evaluated for want of a fact (§2.2 {@code onMissing}).
   */
  public enum MissingPolicy {
    /** Refer the application. */
    REFER,
    /** Treat as failed. */
    FAIL,
    /** Treat as not applicable — CHK-009 warns, because it can be a fail-open. */
    NOT_APPLICABLE,
    /** Re-run the decision point with the unknown Boolean replaced by {@code value}. */
    USE_DEFAULT
  }

  /** Which direction makes a measure worse, for deviation coverage (§4.9, S8). */
  public enum WorseWhen {
    /** A higher measure is worse. */
    HIGHER,
    /** A lower measure is worse. */
    LOWER
  }

  /**
   * How a decision table resolves multiple matching rows (§2.2 {@code hitPolicy}).
   *
   * @param kind FIRST, UNIQUE, PRIORITY or COLLECT
   * @param op the collect operation, present only for COLLECT
   * @param collectOutput the output the collect operation writes, present only for COLLECT
   */
  public record HitPolicy(Kind kind, Optional<CollectOp> op, Optional<String> collectOutput) {

    /** Hit-policy kinds. */
    public enum Kind {
      /** The first matching row wins, in declaration order. */
      FIRST,
      /** Exactly one row may match; more is EVAL_TABLE_UNIQUE_VIOLATION. */
      UNIQUE,
      /** The most severe matching row wins. */
      PRIORITY,
      /** Every matching row contributes to one aggregated output. */
      COLLECT
    }

    /** Collect operations. */
    public enum CollectOp {
      /** Sum the contributions. */
      SUM,
      /** The least contribution. */
      MIN,
      /** The greatest contribution. */
      MAX,
      /** How many rows matched. */
      COUNT,
      /** Every contribution, as a list. */
      LIST
    }

    /**
     * A non-collect hit policy.
     *
     * @param kind FIRST, UNIQUE or PRIORITY
     * @return the policy
     */
    public static HitPolicy of(Kind kind) {
      return new HitPolicy(kind, Optional.empty(), Optional.empty());
    }

    /**
     * A COLLECT hit policy.
     *
     * @param op the collect operation
     * @param collectOutput the output it writes
     * @return the policy
     */
    public static HitPolicy collect(CollectOp op, String collectOutput) {
      return new HitPolicy(Kind.COLLECT, Optional.of(op), Optional.of(collectOutput));
    }
  }

  /**
   * What happens when a fact is missing (§2.2 {@code $defs/onMissing}).
   *
   * @param policy the action to take
   * @param value the Boolean default, required when and only when the policy is USE_DEFAULT
   * @param reasonCode the reason to attach
   */
  public record OnMissing(
      MissingPolicy policy, Optional<Boolean> value, Optional<String> reasonCode) {

    /** The IR's default: report DATA_MISSING, which refers. */
    public static final OnMissing DEFAULT =
        new OnMissing(MissingPolicy.REFER, Optional.empty(), Optional.empty());

    /**
     * An onMissing with a policy and no default value.
     *
     * @param policy the action
     * @param reasonCode the reason, or null
     * @return the onMissing
     */
    public static OnMissing of(MissingPolicy policy, String reasonCode) {
      return new OnMissing(policy, Optional.empty(), Optional.ofNullable(reasonCode));
    }
  }

  /**
   * The outcome assigned when a component does not pass (§2.2 {@code $defs/outcome}).
   *
   * <p>The schema's conditional requirements are real constraints, not decoration: a DEVIATION
   * needs both {@code deviationType} and {@code reasonCode} because an approver cannot act on an
   * unnamed tolerance, and a FAIL must not carry a {@code measure} because there is no tolerance to
   * measure against.
   *
   * @param status the status to assign
   * @param reasonCode the reason
   * @param deviationType the deviation type, for a DEVIATION
   * @param measure the BEL expression giving the measured value, for coverage matching
   * @param worseWhen which direction is worse; required for a numeric or date measure, forbidden
   *     for a categorical one
   */
  public record Outcome(
      OutcomeStatus status,
      Optional<String> reasonCode,
      Optional<String> deviationType,
      Optional<String> measure,
      Optional<WorseWhen> worseWhen) {

    /**
     * A plain failure outcome.
     *
     * @param reasonCode the reason
     * @return the outcome
     */
    public static Outcome fail(String reasonCode) {
      return new Outcome(
          OutcomeStatus.FAIL,
          Optional.of(reasonCode),
          Optional.empty(),
          Optional.empty(),
          Optional.empty());
    }
  }

  /**
   * A named intermediate value (§2.2 {@code $defs/variable}).
   *
   * @param name the variable's name
   * @param type its declared BEL type
   * @param scope the entity scope it is evaluated at
   * @param expr the BEL expression
   */
  public record VariableDef(String name, String type, Scope scope, String expr) {}

  /**
   * One rule (§2.2 {@code $defs/rule}).
   *
   * @param code the identifier
   * @param name a human label
   * @param scope the entity scope
   * @param when an optional guard; FALSE makes the rule NOT_APPLICABLE
   * @param check the expression that must be TRUE to pass
   * @param onFail the outcome when the check is FALSE
   * @param onMissing what to do when the check cannot be evaluated
   * @param outputs named output expressions, evaluated on a pass
   * @param priority tie-break order, where a component needs one
   */
  public record RuleDef(
      String code,
      String name,
      Scope scope,
      Optional<String> when,
      String check,
      Outcome onFail,
      OnMissing onMissing,
      Map<String, String> outputs,
      Optional<Integer> priority) {

    /** Creates a rule, copying the outputs map. */
    public RuleDef {
      outputs = Map.copyOf(outputs);
    }
  }

  /**
   * One row of a decision table (§2.2 {@code $defs/tableRow}).
   *
   * @param when one unary-test string per table input, in input order; AND across columns
   * @param then what the row assigns when it matches
   * @param label a human label for the row
   */
  public record TableRow(List<String> when, RowResult then, Optional<String> label) {
    /** Creates a row, copying the cell list. */
    public TableRow {
      when = List.copyOf(when);
    }
  }

  /**
   * What a matching table row assigns.
   *
   * <p>Every field is optional: a table in which no row carries a {@code status} is an
   * <em>output-only</em> table, whose step status is PASS on a match (§2.2's closing constraints).
   *
   * @param status the status, if the row assigns one
   * @param reasonCode the reason
   * @param deviationType the deviation type
   * @param measure the measured value expression
   * @param worseWhen which direction is worse
   * @param outputs named output expressions
   */
  public record RowResult(
      Optional<OutcomeStatus> status,
      Optional<String> reasonCode,
      Optional<String> deviationType,
      Optional<String> measure,
      Optional<WorseWhen> worseWhen,
      Map<String, String> outputs) {

    /** Creates a row result, copying the outputs map. */
    public RowResult {
      outputs = Map.copyOf(outputs);
    }

    /**
     * A row that only assigns a status.
     *
     * @param status the status
     * @param reasonCode the reason, or null
     * @return the row result
     */
    public static RowResult of(OutcomeStatus status, String reasonCode) {
      return new RowResult(
          Optional.of(status),
          Optional.ofNullable(reasonCode),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Map.of());
    }
  }

  /**
   * A named table input.
   *
   * @param name the input's name
   * @param expr the BEL expression producing the value its column tests
   */
  public record TableInput(String name, String expr) {}

  /**
   * A declared table output.
   *
   * @param name the output's name
   * @param type its declared BEL type
   */
  public record TableOutput(String name, String type) {}

  /**
   * A decision table (§2.2 {@code $defs/decisionTable}).
   *
   * @param code the identifier
   * @param name a human label
   * @param scope the entity scope
   * @param when an optional guard
   * @param hitPolicy how multiple matching rows resolve
   * @param inputs the input columns, in order
   * @param outputs the declared outputs
   * @param rows the rows, in declaration order
   * @param onMissing what to do when an input is missing
   * @param priority tie-break order
   */
  public record DecisionTableDef(
      String code,
      String name,
      Scope scope,
      Optional<String> when,
      HitPolicy hitPolicy,
      List<TableInput> inputs,
      List<TableOutput> outputs,
      List<TableRow> rows,
      OnMissing onMissing,
      Optional<Integer> priority) {

    /** Creates a table, copying the lists. */
    public DecisionTableDef {
      inputs = List.copyOf(inputs);
      outputs = List.copyOf(outputs);
      rows = List.copyOf(rows);
    }
  }

  /**
   * One bin of a scorecard characteristic (§2.2 {@code $defs/bin}).
   *
   * @param when the unary test the characteristic's value is matched against
   * @param points the points awarded
   * @param reasonCode the adverse reason this bin contributes
   */
  public record Bin(String when, int points, Optional<String> reasonCode) {}

  /**
   * One scorecard characteristic.
   *
   * @param name the characteristic's name
   * @param expr the BEL expression giving its value
   * @param reasonCode the adverse reason for the characteristic as a whole
   * @param bins the bins, in declaration order — which is also the tie-break order for adverse
   *     reasons (S11-01)
   */
  public record Characteristic(
      String name, String expr, Optional<String> reasonCode, List<Bin> bins) {
    /** Creates a characteristic, copying the bin list. */
    public Characteristic {
      bins = List.copyOf(bins);
    }
  }

  /**
   * How a raw score is scaled to the reported score.
   *
   * @param multiplier applied to the raw score
   * @param offset added after multiplying
   * @param scale the decimal scale of the result
   * @param roundingMode how to round to that scale
   */
  public record Scaling(BigDecimal multiplier, BigDecimal offset, int scale, String roundingMode) {

    /** Identity scaling: the reported score is the raw score. */
    public static final Scaling IDENTITY =
        new Scaling(BigDecimal.ONE, BigDecimal.ZERO, 0, "HALF_UP");
  }

  /**
   * A scorecard (§2.2 {@code $defs/scorecard}).
   *
   * @param code the identifier
   * @param name a human label
   * @param scope the entity scope
   * @param baseScore the score before any characteristic contributes
   * @param characteristics the characteristics, in declaration order
   * @param adverseReasons how many adverse reasons to report
   * @param scaling how to scale the raw score
   * @param onMissing what to do when a characteristic's value is missing
   */
  public record ScorecardDef(
      String code,
      String name,
      Scope scope,
      int baseScore,
      List<Characteristic> characteristics,
      int adverseReasons,
      Scaling scaling,
      OnMissing onMissing) {

    /** Creates a scorecard, copying the characteristic list. */
    public ScorecardDef {
      characteristics = List.copyOf(characteristics);
    }
  }

  /**
   * How per-entity statuses combine into one step status (§2.2 {@code $defs/aggregate}, §4.7).
   *
   * @param kind WORST, ALL_MUST_PASS, NONE or THRESHOLD
   * @param metric COUNT or PERCENT, for THRESHOLD
   * @param op the comparison, for THRESHOLD
   * @param value the threshold, as a decimal string or a {@code param.X} reference
   * @param statuses the statuses the metric counts, for THRESHOLD
   * @param result the status to assign when the threshold is met
   */
  public record AggregateSpec(
      Kind kind,
      Optional<Metric> metric,
      Optional<String> op,
      Optional<String> value,
      List<RuntimeStatus> statuses,
      Optional<OutcomeStatus> result) {

    /** Aggregation kinds. */
    public enum Kind {
      /** The most severe entity status wins. */
      WORST,
      /** Every entity must PASS; anything else fails the step. */
      ALL_MUST_PASS,
      /** No aggregation — a single-entity step. */
      NONE,
      /** A share or count of entities in given statuses triggers a result. */
      THRESHOLD
    }

    /** What a THRESHOLD measures. */
    public enum Metric {
      /** How many entities. */
      COUNT,
      /** What share of entities, as a whole-number percent. */
      PERCENT
    }

    /** Creates an aggregate spec, copying the status list. */
    public AggregateSpec {
      statuses = List.copyOf(statuses);
    }

    /**
     * A non-threshold aggregate.
     *
     * @param kind WORST, ALL_MUST_PASS or NONE
     * @return the spec
     */
    public static AggregateSpec of(Kind kind) {
      return new AggregateSpec(
          kind, Optional.empty(), Optional.empty(), Optional.empty(), List.of(), Optional.empty());
    }
  }

  /** Every status a component can end up with at runtime (§2.2 {@code $defs/anyStatus}). */
  public enum RuntimeStatus {
    /** Passed. */
    PASS,
    /** Passed within an approvable tolerance. */
    DEVIATION,
    /** Sent for review. */
    REFER,
    /** Failed. */
    FAIL,
    /** Did not apply. */
    NOT_APPLICABLE,
    /** A needed fact was absent. */
    DATA_MISSING,
    /** Could not be evaluated. */
    ERROR
  }

  /**
   * One flow step (§2.2 {@code flow.stages[].steps[]}).
   *
   * @param ref the code of the rule, table or scorecard this step runs
   * @param roles party roles to restrict to; allowed only when the component's scope is PARTY
   * @param aggregate how the per-entity statuses combine
   */
  public record StepDef(String ref, List<String> roles, AggregateSpec aggregate) {
    /** Creates a step, copying the role list. */
    public StepDef {
      roles = List.copyOf(roles);
    }

    /**
     * A step with no role restriction.
     *
     * @param ref the component code
     * @param aggregate the aggregation
     * @return the step
     */
    public static StepDef of(String ref, AggregateSpec aggregate) {
      return new StepDef(ref, List.of(), aggregate);
    }
  }

  /**
   * One flow stage.
   *
   * @param name the stage's code
   * @param knockout whether a FAIL in this stage skips every later step
   * @param steps the steps, in order
   */
  public record StageDef(String name, boolean knockout, List<StepDef> steps) {
    /** Creates a stage, copying the step list. */
    public StageDef {
      steps = List.copyOf(steps);
    }
  }

  /**
   * The flow.
   *
   * @param stages the stages, in order
   */
  public record FlowDef(List<StageDef> stages) {
    /** Creates a flow, copying the stage list. */
    public FlowDef {
      stages = List.copyOf(stages);
    }
  }

  /**
   * One Authority Matrix level (§4.9).
   *
   * @param levelCode the level's code, e.g. {@code L2}
   * @param rank its rank; a higher rank is a higher authority
   */
  public record AuthorityLevel(String levelCode, int rank) {}

  /**
   * One Authority Matrix band (§4.9).
   *
   * <p>A band is {@code amountAbove < amount <= amountUpTo}, and a <b>null bound means
   * unbounded</b> — which is why the bounds are {@link Optional} rather than defaulted to zero or
   * to a sentinel. An unbounded upper bound defaulted to zero would silently stop covering the
   * amounts it exists to cover.
   *
   * @param entryNo the entry's order, for diagnostics
   * @param deviationType the deviation type this band governs
   * @param productLine the product line, or {@code *} for any
   * @param segment the segment, or {@code *} for any
   * @param amountAbove the exclusive lower bound, empty when unbounded
   * @param amountUpTo the inclusive upper bound, empty when unbounded
   * @param levelCode the authority level the band requires
   */
  public record AuthorityEntry(
      int entryNo,
      String deviationType,
      String productLine,
      String segment,
      Optional<BigDecimal> amountAbove,
      Optional<BigDecimal> amountUpTo,
      String levelCode) {}

  /**
   * The Authority Matrix (§4.9).
   *
   * <p>The routing mode is pinned in the matrix <em>version</em>, copied from the tenant setting
   * when that version was created — not read from the tenant at decision time. That is what makes
   * replay exact: a decision from March resolves the authority it actually resolved then, even if
   * the tenant has since changed its routing.
   *
   * @param code the matrix's code
   * @param version its version
   * @param routingMode {@code HIGHEST_ONLY} or {@code EACH}
   * @param levels the levels and their ranks
   * @param entries the bands
   */
  public record AuthorityMatrix(
      String code,
      int version,
      String routingMode,
      List<AuthorityLevel> levels,
      List<AuthorityEntry> entries) {

    /** Creates a matrix, copying the lists. */
    public AuthorityMatrix {
      levels = List.copyOf(levels);
      entries = List.copyOf(entries);
    }

    /**
     * Returns a level's rank.
     *
     * @param levelCode the level code
     * @return its rank, or empty when the matrix does not declare it — §4.9 treats an unknown level
     *     code as no match rather than as rank zero
     */
    public Optional<Integer> rankOf(String levelCode) {
      return levels.stream()
          .filter(level -> level.levelCode().equals(levelCode))
          .map(AuthorityLevel::rank)
          .findFirst();
    }
  }

  /**
   * A whole policy (§2.2 root).
   *
   * <p>Only the parts evaluation needs are modelled here. {@code policy}, {@code factSchema},
   * {@code assets} and {@code tests} are metadata the control plane and the compiler use; they
   * arrive with the IR codec in M1-12.
   *
   * @param irVersion the IR version; 1
   * @param variables named intermediate values
   * @param rules the rules
   * @param decisionTables the decision tables
   * @param scorecards the scorecards
   * @param flow the flow
   * @param fuelLimit the evaluation fuel budget (§3.10)
   * @param authorityAmount the BEL expression giving the amount authority bands are read against
   *     (§2.2 {@code settings.authorityAmount})
   * @param authorityMatrix the Authority Matrix, when the policy declares one
   * @param customerReasonLimit how many customer-facing reasons a decline discloses (§2.2 {@code
   *     settings.customerReasonLimit}, default 4)
   */
  public record PolicyIr(
      int irVersion,
      List<VariableDef> variables,
      List<RuleDef> rules,
      List<DecisionTableDef> decisionTables,
      List<ScorecardDef> scorecards,
      FlowDef flow,
      Optional<Integer> fuelLimit,
      Optional<String> authorityAmount,
      Optional<AuthorityMatrix> authorityMatrix,
      int customerReasonLimit) {

    /** §2.2 M8: the default when {@code settings.customerReasonLimit} is absent. */
    public static final int DEFAULT_CUSTOMER_REASON_LIMIT = 4;

    /** Creates a policy, copying every list. */
    public PolicyIr {
      variables = List.copyOf(variables);
      rules = List.copyOf(rules);
      decisionTables = List.copyOf(decisionTables);
      scorecards = List.copyOf(scorecards);
    }
  }
}
