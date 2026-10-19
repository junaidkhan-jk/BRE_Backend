package dev.bre.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bre.bundle.Bundle;
import dev.bre.bundle.BundleVerifier;
import dev.bre.bundle.TrustStore;
import dev.bre.engine.decide.FlowEvaluator;
import dev.bre.engine.eval.EvaluationContext;
import dev.bre.engine.eval.ParameterTable;
import dev.bre.engine.eval.Value;
import dev.bre.engine.ir.Ir;
import dev.bre.facts.FactReader;
import dev.bre.facts.LendingFacts;
import dev.bre.irjson.AssetCodec;
import dev.bre.irjson.IrCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

/**
 * A verified bundle, ready to decide — the part {@code decide} and {@code parallel-run} share.
 *
 * <p>Extracted when the second caller appeared rather than in advance. What it keeps in one place
 * is the <b>order</b> of a correct evaluation: verify the signature, decode the IR, resolve the
 * bundle's own parameters and lists, read the facts <em>through the fact schema</em>, and only then
 * run the flow. Each of those is easy to do and easy to leave out, and a parallel run that skipped
 * the schema read — the step that gives every fact its type — would report differences against the
 * lender's policy that were really differences against the engine's own typing rules.
 *
 * <p>The verification is not optional here either, for the reason {@link DecideCommand} gives: a
 * tool that evaluates unverified bundles teaches the habit of evaluating unverified bundles.
 */
final class PolicyRunner {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Bundle bundle;
  private final Ir.PolicyIr policy;
  private final ParameterTable parameters;
  private final Map<String, Value> lists;
  private final FactReader factReader = new FactReader(LendingFacts.schema());

  private PolicyRunner(
      Bundle bundle, Ir.PolicyIr policy, ParameterTable parameters, Map<String, Value> lists) {
    this.bundle = bundle;
    this.policy = policy;
    this.parameters = parameters;
    this.lists = lists;
  }

  /**
   * Loads and verifies a bundle.
   *
   * @param bundleFile the bundle document
   * @param publicKey the bundle-signing public key
   * @param kid the key identifier the bundle is signed under
   * @return the runner
   * @throws IOException if a file cannot be read
   * @throws dev.bre.bundle.BundleException if the bundle does not verify
   */
  static PolicyRunner load(Path bundleFile, Path publicKey, String kid) throws IOException {
    Bundle bundle = Bundle.parse(Files.readString(bundleFile));
    new BundleVerifier(TrustStore.builder().ownBundleKey(kid, Keys.publicKey(publicKey)).build())
        .verify(bundle);
    return new PolicyRunner(
        bundle,
        IrCodec.readPolicy(bundle.ir()),
        AssetCodec.parameters(bundle.assets()),
        AssetCodec.lists(bundle.assets()));
  }

  /**
   * Returns the verified bundle.
   *
   * @return the bundle
   */
  Bundle bundle() {
    return bundle;
  }

  /**
   * Returns whether the bundle carries resolved parameters or lists.
   *
   * @return true if it has either
   */
  boolean hasResolvedAssets() {
    return !parameters.isEmpty() || !lists.isEmpty();
  }

  /**
   * Decides one case.
   *
   * @param facts the facts document, as parsed JSON
   * @param asOf the business date to decide at
   * @return the decision
   */
  FlowEvaluator.DecisionResult decide(Map<String, Object> facts, LocalDate asOf) {
    Value.RecordV record = factReader.toRecord(facts);
    return FlowEvaluator.evaluate(policy, EvaluationContext.of(record, parameters, lists, asOf));
  }

  /**
   * Decides one case from its JSON text.
   *
   * @param factsJson the facts document
   * @param asOf the business date
   * @return the decision
   * @throws IOException if the document is not JSON
   */
  FlowEvaluator.DecisionResult decideJson(String factsJson, LocalDate asOf) throws IOException {
    return decide(
        MAPPER.readValue(
            factsJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}),
        asOf);
  }
}
