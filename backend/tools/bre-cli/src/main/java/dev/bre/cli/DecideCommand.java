package dev.bre.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bre.bundle.Bundle;
import dev.bre.bundle.BundleException;
import dev.bre.bundle.BundleVerifier;
import dev.bre.bundle.TrustStore;
import dev.bre.engine.decide.ComponentResult;
import dev.bre.engine.decide.FlowEvaluator;
import dev.bre.engine.eval.EvaluationContext;
import dev.bre.engine.eval.Value;
import dev.bre.engine.ir.Ir;
import dev.bre.facts.FactReader;
import dev.bre.facts.LendingFacts;
import dev.bre.irjson.AssetCodec;
import dev.bre.irjson.IrCodec;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/**
 * {@code bre decide <bundle.json> --facts <f.json> --as-of <date> --pub <k> --kid <id>} (M6-05).
 *
 * <p>The offline decision: a signed bundle, a facts document, a date, and the answer — with no
 * service, no database and no network. It is what makes a policy testable by the person who wrote
 * it, and it is also the only place the whole chain is exercised in one step: verify the signature,
 * decode the IR, resolve the bundle's own parameters and lists, read the facts <em>through the fact
 * schema</em>, and run the flow.
 *
 * <p><b>The public key is required, not optional.</b> A tool that would happily evaluate an
 * unverified bundle teaches the habit of evaluating unverified bundles, and the habit is the risk:
 * a policy is the thing that decides whether somebody gets a loan, and "it was only local" is how
 * an unsigned one reaches somewhere that is not local.
 *
 * <p>This is the same engine, the same parameter resolution and the same fact typing the decision
 * service uses — the service adds the decision log, idempotency and the pointer manifest, not a
 * different evaluator. So a disagreement between this and production is a bug, not a difference of
 * configuration, which is exactly the property an authoring tool needs.
 */
final class DecideCommand {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The tenant time zone a business date is resolved in (00 §3.7a). */
  private static final ZoneId TENANT_ZONE = ZoneId.of("Asia/Kolkata");

  private DecideCommand() {}

  static int run(String[] args, PrintStream out, PrintStream err) {
    String factsPath = Keys.flag(args, "--facts");
    String asOfText = Keys.flag(args, "--as-of");
    String pubPath = Keys.flag(args, "--pub");
    String kid = Keys.flag(args, "--kid");
    if (args.length < 2 || factsPath == null || pubPath == null || kid == null) {
      err.println(
          "usage: bre decide <bundle.json> --facts <facts.json> --pub <key.x509> --kid <id>"
              + " [--as-of <YYYY-MM-DD>]");
      return Main.EXIT_USAGE;
    }

    LocalDate asOf;
    if (asOfText == null) {
      // Today in the tenant zone, read once and printed. The engine never reads a clock (D8);
      // resolving asOf is a decision made here, and a tool that silently used today's date would
      // give a different answer tomorrow for the same inputs without saying why. Asia/Kolkata
      // rather than the machine's zone (00 §3.7a) — a developer in another zone must not get a
      // different business date from the one the service would use.
      asOf = LocalDate.now(TENANT_ZONE);
      out.println("asOf not given; using today, " + asOf);
    } else {
      try {
        asOf = LocalDate.parse(asOfText);
      } catch (RuntimeException notADate) {
        err.println("--as-of must be an ISO-8601 date, e.g. 2026-10-01");
        return Main.EXIT_USAGE;
      }
    }

    try {
      Bundle bundle = Bundle.parse(Files.readString(Path.of(args[1])));
      BundleVerifier verifier =
          new BundleVerifier(
              TrustStore.builder().ownBundleKey(kid, Keys.publicKey(Path.of(pubPath))).build());
      verifier.verify(bundle);

      Ir.PolicyIr policy = IrCodec.readPolicy(bundle.ir());
      dev.bre.engine.eval.ParameterTable parameters = AssetCodec.parameters(bundle.assets());
      Map<String, Value> lists = AssetCodec.lists(bundle.assets());
      if (parameters.isEmpty() && lists.isEmpty()) {
        out.println(
            "note: this bundle carries no resolved assets, so every param. and list. reference is"
                + " Missing. Re-bundle with --assets if the policy uses them.");
      }

      Map<String, Object> facts =
          MAPPER.readValue(
              Files.readString(Path.of(factsPath)), new TypeReference<Map<String, Object>>() {});
      Value.RecordV factRecord = new FactReader(LendingFacts.schema()).toRecord(facts);

      FlowEvaluator.DecisionResult result =
          FlowEvaluator.evaluate(policy, EvaluationContext.of(factRecord, parameters, lists, asOf));

      report(result, bundle, asOf, out);
      return Main.EXIT_OK;
    } catch (BundleException e) {
      err.println("the bundle was refused: " + e.getMessage());
      return Main.EXIT_FINDINGS;
    } catch (IOException e) {
      err.println("could not read: " + e.getMessage());
      return Main.EXIT_USAGE;
    }
  }

  private static void report(
      FlowEvaluator.DecisionResult result, Bundle bundle, LocalDate asOf, PrintStream out) {
    out.println();
    out.println("  decision      " + result.decision());
    out.println("  stpEligible   " + result.stpEligible());
    out.println("  asOf          " + asOf);
    out.println("  bundle        " + bundle.bundleHash());
    out.println();

    out.println("  flow");
    for (FlowEvaluator.StepResult step : result.steps()) {
      out.printf("    %-26s %s%n", step.ref(), step.status());
      for (ComponentResult entity : step.entities()) {
        // Only the entities that did something. A 40-member group with every member passing would
        // otherwise print 40 identical lines per step and bury the one that did not.
        if (entity.effectiveStatus() == step.status() && entity.reasonCode().isEmpty()) {
          continue;
        }
        out.printf(
            "        %-16s %-10s %s%s%n",
            entity.entityRef().orElse("-"),
            entity.effectiveStatus(),
            entity.reasonCode().orElse(""),
            entity.error().map(e -> "  error: " + e).orElse(""));
      }
    }

    if (!result.customerReasons().isEmpty()) {
      out.println();
      out.println("  customer reasons");
      result.customerReasons().forEach(r -> out.println("    " + r.code()));
    }
    if (!result.internalReasons().isEmpty()) {
      out.println();
      out.println("  internal reasons");
      result.internalReasons().forEach(r -> out.println("    " + r));
    }
    if (!result.requiredApprovals().isEmpty()) {
      out.println();
      out.println("  approvals required");
      for (var item : result.requiredApprovals()) {
        out.println("    level " + item.level());
        item.deviations()
            .forEach(
                d ->
                    out.println(
                        "      "
                            + d.deviationType()
                            + "  "
                            + d.ruleCode()
                            + d.entityRef().map(r -> "  " + r).orElse("")));
      }
    }
    out.println();
  }
}
