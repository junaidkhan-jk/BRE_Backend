package dev.bre.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The §9.6 span-attribute allow-list and metric-label restriction. */
class TelemetryTest {

  @Test
  void theAllowListedAttributesArePermitted() {
    for (AttributeKey<?> key :
        new AttributeKey<?>[] {
          Telemetry.TENANT, Telemetry.POLICY, Telemetry.BUNDLE,
          Telemetry.DECISION, Telemetry.OUTCOME, Telemetry.ENVIRONMENT
        }) {
      assertThat(Telemetry.isPermittedSpanAttribute(key.getKey()))
          .as("%s is named in §9.6", key.getKey())
          .isTrue();
    }
  }

  /**
   * The failure this exists to prevent: a developer debugging a decision attaches the facts to a
   * span, it works, and every application's personal data is now in an observability backend with a
   * different retention policy and access list from the decision store.
   */
  @Test
  void anythingNotAllowListedIsRefused() {
    for (String key : new String[] {"facts", "bre.facts", "applicationRef", "member.income", "x"}) {
      assertThat(Telemetry.isPermittedSpanAttribute(key)).as("%s", key).isFalse();
    }

    assertThatThrownBy(
            () ->
                Telemetry.spanAttributes(
                    Map.of(AttributeKey.stringKey("facts"), "{\"income\":50000}")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not an allow-listed span attribute")
        .hasMessageContaining("§9.6");
  }

  /** Refusing beats dropping here: a silently dropped attribute looks instrumented and is not. */
  @Test
  void spanAttributesRefusesRatherThanDropping() {
    Map<AttributeKey<?>, Object> mixed = new LinkedHashMap<>();
    mixed.put(Telemetry.TENANT, "t_acme");
    mixed.put(AttributeKey.stringKey("partyRef"), "P-0003");

    assertThatThrownBy(() -> Telemetry.spanAttributes(mixed))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void allowListedAttributesBuildCorrectly() {
    Attributes attributes =
        Telemetry.spanAttributes(
            Map.of(
                Telemetry.TENANT, "t_acme",
                Telemetry.OUTCOME, "APPROVE",
                Telemetry.POLICY_VERSION, 3L));

    assertThat(attributes.get(Telemetry.TENANT)).isEqualTo("t_acme");
    assertThat(attributes.get(Telemetry.OUTCOME)).isEqualTo("APPROVE");
    assertThat(attributes.get(Telemetry.POLICY_VERSION)).isEqualTo(3L);
  }

  /**
   * The asymmetry that looks like a contradiction and is not: {@code bre.decision} is a permitted
   * span attribute and a forbidden metric label. A span is one sampled request on a retention
   * clock; a metric label becomes a persistent time series, so a decision id there is unbounded
   * cardinality and a permanent record that one application was decided at one instant.
   */
  @Test
  void theDecisionIdIsAllowedOnASpanAndForbiddenOnAMetric() {
    assertThat(Telemetry.isPermittedSpanAttribute(Telemetry.DECISION.getKey())).isTrue();
    assertThat(Telemetry.isPermittedMetricLabel(Telemetry.DECISION.getKey())).isFalse();
  }

  @Test
  void theIdentifiersSection96NamesAreForbiddenAsMetricLabels() {
    for (String label :
        new String[] {
          "applicationRef", "application_ref", "partyRef", "party_ref",
          "principalRef", "decisionId", "decision_id", "requestId"
        }) {
      assertThat(Telemetry.isPermittedMetricLabel(label)).as("%s", label).isFalse();
    }
  }

  @Test
  void permittedMetricLabelsSurvive() {
    for (String label : new String[] {"tenant", "outcome", "policy", "env", "status"}) {
      assertThat(Telemetry.isPermittedMetricLabel(label)).as("%s", label).isTrue();
    }
  }

  /**
   * Metric labels are filtered rather than refused, which is the opposite of span attributes.
   * Framework instrumentation commonly supplies its own labels, and failing a request because a
   * library wanted to tag a timer with a request id would be the worse outcome.
   */
  @Test
  void metricLabelsAreFilteredAndKeepTheirOrder() {
    Map<String, String> proposed = new LinkedHashMap<>();
    proposed.put("tenant", "t_acme");
    proposed.put("decisionId", "01a11b0b-df03");
    proposed.put("outcome", "APPROVE");
    proposed.put("applicationRef", "APP-2026-000123");

    Map<String, String> filtered = Telemetry.metricLabels(proposed);

    assertThat(filtered).containsOnlyKeys("tenant", "outcome");
    assertThat(filtered.keySet()).containsExactly("tenant", "outcome");
  }

  @Test
  void theDecisionSpanCarriesEveryAllowListedField() {
    Attributes attributes =
        Telemetry.decisionSpan(
            "t_acme", "MFI_JLG_CREDIT_UW", 3, "sha256:9b3f", "01a11b0b-df03", "APPROVE");

    assertThat(attributes.size()).isEqualTo(6);
    attributes.forEach(
        (key, value) ->
            assertThat(Telemetry.isPermittedSpanAttribute(key.getKey()))
                .as("decisionSpan must only set allow-listed keys; %s", key.getKey())
                .isTrue());
  }
}
