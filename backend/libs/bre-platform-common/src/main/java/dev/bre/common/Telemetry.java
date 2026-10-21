package dev.bre.common;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Span attributes and metric labels, restricted to what §9.6 permits.
 *
 * <p><b>Span attributes come from an allow-list only.</b> Not a convention — a closed set, and
 * anything else is refused. The reason is that tracing is the easiest place in a platform to leak
 * personal data by accident: a developer debugging a decision adds {@code
 * span.setAttribute("facts", facts.toString())}, it works, it ships, and the facts of every
 * application are now in a third-party observability backend with a different retention policy and
 * a different access list from the decision store. §9.6 closes that by naming the attributes that
 * may exist; this class is where the closing happens.
 *
 * <p><b>Metric labels are stricter than span attributes, deliberately.</b> {@code bre.decision} is
 * a permitted span attribute and {@code decisionId} is a forbidden metric label, which looks
 * inconsistent until you look at what each thing is. A span is one sampled request and is discarded
 * on a retention clock. A metric label becomes a time series that persists, so a decision id there
 * is both unbounded cardinality — the fastest way to take down a metrics backend — and a permanent
 * record that a particular application was decided at a particular instant. §9.6 names {@code
 * applicationRef}, {@code partyRef} and {@code decisionId}; this refuses them.
 *
 * <p>The OpenTelemetry <em>API</em> only. A library that pulled in the SDK would decide the
 * exporter and sampler for every application embedding it; the API is a no-op until an application
 * installs an SDK of its own.
 */
public final class Telemetry {

  /** The tenant the work belongs to. */
  public static final AttributeKey<String> TENANT = AttributeKey.stringKey("bre.tenant");

  /** The policy code being evaluated. */
  public static final AttributeKey<String> POLICY = AttributeKey.stringKey("bre.policy");

  /** The policy version. */
  public static final AttributeKey<Long> POLICY_VERSION =
      AttributeKey.longKey("bre.policy_version");

  /** The content-addressed bundle hash. */
  public static final AttributeKey<String> BUNDLE = AttributeKey.stringKey("bre.bundle");

  /** The decision's identifier. Permitted on a span; never on a metric. */
  public static final AttributeKey<String> DECISION = AttributeKey.stringKey("bre.decision");

  /** The decision reached — APPROVE, REFER and so on. */
  public static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("bre.outcome");

  /** The environment code. */
  public static final AttributeKey<String> ENVIRONMENT = AttributeKey.stringKey("bre.environment");

  /** The engine version that decided. */
  public static final AttributeKey<String> ENGINE_VERSION =
      AttributeKey.stringKey("bre.engine_version");

  /** Whether the decision was straight-through. */
  public static final AttributeKey<Boolean> STP_ELIGIBLE =
      AttributeKey.booleanKey("bre.stp_eligible");

  /** Every attribute a span may carry (§9.6). */
  private static final Set<String> PERMITTED_SPAN_ATTRIBUTES =
      Set.of(
          TENANT.getKey(),
          POLICY.getKey(),
          POLICY_VERSION.getKey(),
          BUNDLE.getKey(),
          DECISION.getKey(),
          OUTCOME.getKey(),
          ENVIRONMENT.getKey(),
          ENGINE_VERSION.getKey(),
          STP_ELIGIBLE.getKey());

  /**
   * Attributes a metric must never be labelled with (§9.6).
   *
   * <p>Named rather than derived from the span allow-list, because the two sets differ for a real
   * reason and deriving one from the other would lose it.
   */
  private static final Set<String> FORBIDDEN_METRIC_LABELS =
      Set.of(
          "applicationRef",
          "application_ref",
          "bre.application",
          "partyRef",
          "party_ref",
          "bre.party",
          "principalRef",
          "principal_ref",
          "decisionId",
          "decision_id",
          DECISION.getKey(),
          "requestId",
          "request_id");

  private Telemetry() {}

  /**
   * Returns whether a key may appear as a span attribute.
   *
   * @param key the attribute key
   * @return true when §9.6's allow-list contains it
   */
  public static boolean isPermittedSpanAttribute(String key) {
    return PERMITTED_SPAN_ATTRIBUTES.contains(key);
  }

  /**
   * Returns whether a key may appear as a metric label.
   *
   * @param key the label name
   * @return false for the identifiers §9.6 forbids
   */
  public static boolean isPermittedMetricLabel(String key) {
    return !FORBIDDEN_METRIC_LABELS.contains(key);
  }

  /**
   * Sets an allow-listed string attribute on the current span.
   *
   * @param key one of this class's keys
   * @param value the value
   * @throws IllegalArgumentException if the key is not allow-listed
   */
  public static void setSpanAttribute(AttributeKey<String> key, String value) {
    requirePermitted(key.getKey());
    Span.current().setAttribute(key, value);
  }

  /**
   * Builds attributes, refusing any key §9.6 does not permit.
   *
   * <p>Refuses rather than drops. A silently dropped attribute is a span that looks instrumented
   * and is not, and the developer who added it has no way to tell — whereas an exception in a test
   * is found immediately. The restriction exists to be noticed.
   *
   * @param values the attributes to set, by key
   * @return the attributes
   * @throws IllegalArgumentException if any key is not allow-listed
   */
  public static Attributes spanAttributes(Map<AttributeKey<?>, Object> values) {
    AttributesBuilder builder = Attributes.builder();
    values.forEach(
        (key, value) -> {
          requirePermitted(key.getKey());
          @SuppressWarnings("unchecked")
          AttributeKey<Object> typed = (AttributeKey<Object>) key;
          builder.put(typed, value);
        });
    return builder.build();
  }

  /**
   * Filters a metric's labels down to the permitted ones.
   *
   * <p>Filters rather than refuses, which is the opposite of {@link #spanAttributes} and
   * deliberate: metric labels commonly arrive from a framework's own instrumentation rather than
   * from code anyone here wrote, and failing a request because a library wanted to label a timer
   * with a request id would be a worse outcome than dropping the label. The count of what was
   * dropped is the signal.
   *
   * @param labels the proposed labels
   * @return only the permitted ones, in their original order
   */
  public static Map<String, String> metricLabels(Map<String, String> labels) {
    Map<String, String> permitted = new LinkedHashMap<>();
    labels.forEach(
        (key, value) -> {
          if (isPermittedMetricLabel(key)) {
            permitted.put(key, value);
          }
        });
    // unmodifiableMap, NOT Map.copyOf, and the javadoc above is why. Map.copyOf returns an
    // immutable map whose iteration order is UNSPECIFIED and randomised per JVM by a salt — so it
    // threw away the LinkedHashMap's insertion order while this method promised to keep it. The
    // test that asserts the order passed for weeks by luck and then failed on an unrelated build,
    // which is the worst way for a flake to behave: a re-run makes it green and the re-run gets
    // read as proof there was nothing wrong. unmodifiableMap keeps the order and is still immutable
    // to the caller.
    return java.util.Collections.unmodifiableMap(permitted);
  }

  /**
   * The attributes for one decision, which is the span every service traces.
   *
   * @param tenantId the tenant
   * @param policyCode the policy
   * @param policyVersion the version
   * @param bundleHash the bundle hash
   * @param decisionId the decision id
   * @param outcome the decision reached
   * @return the attributes
   */
  public static Attributes decisionSpan(
      String tenantId,
      String policyCode,
      long policyVersion,
      String bundleHash,
      String decisionId,
      String outcome) {
    return Attributes.builder()
        .put(TENANT, tenantId)
        .put(POLICY, policyCode)
        .put(POLICY_VERSION, policyVersion)
        .put(BUNDLE, bundleHash)
        .put(DECISION, decisionId)
        .put(OUTCOME, outcome)
        .build();
  }

  private static void requirePermitted(String key) {
    if (!isPermittedSpanAttribute(key)) {
      throw new IllegalArgumentException(
          "'"
              + key
              + "' is not an allow-listed span attribute (§9.6). Permitted: "
              + String.join(", ", new java.util.TreeSet<>(PERMITTED_SPAN_ATTRIBUTES))
              + ". Add a key to Telemetry only after confirming it cannot carry a fact value.");
    }
  }
}
