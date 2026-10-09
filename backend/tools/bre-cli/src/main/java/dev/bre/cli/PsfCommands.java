package dev.bre.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.bre.bundle.Bundle;
import dev.bre.bundle.BundleException;
import dev.bre.bundle.BundleSigner;
import dev.bre.bundle.BundleVerifier;
import dev.bre.bundle.TrustStore;
import dev.bre.engine.check.CheckFinding;
import dev.bre.engine.check.StaticChecks;
import dev.bre.engine.ir.DependencyGraph;
import dev.bre.engine.ir.Ir;
import dev.bre.irjson.AssetCodec;
import dev.bre.irjson.IrCodec;
import dev.bre.irjson.IrSchemaValidator;
import dev.bre.irjson.PsfCodec;
import dev.bre.irjson.PsfException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.List;

/**
 * {@code bre psf lint | compile | bundle | verify} — the offline policy toolchain (M1-15).
 *
 * <p>What this is for: an analyst changing a credit rule can see what the platform will say about
 * it <em>before</em> submitting anything, on a laptop, with no access to the registry. Every
 * finding the studio would show, the same static checks, the same schema. The alternative — submit
 * and wait for a review cycle to learn the table has a gap — is how a one-character fix takes two
 * days.
 *
 * <p>Offline by construction. No network, no credentials, nothing to configure. Signing takes a key
 * file because a developer signing a bundle for a local test is a different act from a registry
 * signing one for production, where the key never leaves the KMS (§8.6).
 */
public final class PsfCommands {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The tenant used when a command only needs the IR, not a deployable policy. */
  private static final String LOCAL_TENANT = "t_local";

  private PsfCommands() {}

  /**
   * Runs a {@code psf} subcommand.
   *
   * @param args the full command line
   * @param out where output goes
   * @param err where errors go
   * @return the exit status
   */
  public static int run(String[] args, PrintStream out, PrintStream err) {
    if (args.length < 2) {
      err.println("usage: bre psf <lint|compile|bundle|verify> ...");
      return Main.EXIT_USAGE;
    }
    return switch (args[1]) {
      case "lint" -> lint(args, out, err);
      case "compile" -> compile(args, out, err);
      case "bundle" -> bundle(args, out, err);
      case "verify" -> verify(args, out, err);
      default -> {
        err.println("unknown subcommand 'psf " + args[1] + "'");
        yield Main.EXIT_USAGE;
      }
    };
  }

  /** {@code psf lint <policy.psf.yaml>} — parse, schema-check and run the §4.13 catalogue. */
  private static int lint(String[] args, PrintStream out, PrintStream err) {
    if (args.length < 3) {
      err.println("usage: bre psf lint <policy.psf.yaml>");
      return Main.EXIT_USAGE;
    }
    Path file = Path.of(args[2]);
    ObjectNode ir;
    try {
      ir = PsfCodec.toIrJson(read(file), LOCAL_TENANT);
    } catch (PsfException e) {
      out.println(file + ": " + e.getMessage());
      return Main.EXIT_FINDINGS;
    } catch (IOException e) {
      err.println(file + ": could not be read");
      return Main.EXIT_USAGE;
    }

    List<String> schemaViolations = IrSchemaValidator.violations(ir);
    if (!schemaViolations.isEmpty()) {
      out.println(schemaViolations.size() + " schema violation(s) in " + file + ":");
      schemaViolations.forEach(violation -> out.println("  " + violation));
      return Main.EXIT_FINDINGS;
    }

    Ir.PolicyIr policy = IrCodec.readPolicy(ir);

    // A cycle is reported first and alone: every later check would be reasoning about a policy
    // that cannot be evaluated at all, and the resulting noise buries the real problem.
    DependencyGraph graph = DependencyGraph.of(policy);
    if (!graph.isAcyclic()) {
      out.println("BEL-015 dependency cycle: " + String.join(" -> ", graph.cycle().orElseThrow()));
      return Main.EXIT_FINDINGS;
    }

    List<CheckFinding> findings = StaticChecks.run(policy);
    long errors = findings.stream().filter(CheckFinding::isError).count();

    if (findings.isEmpty()) {
      out.println("ok  " + file + " passes every implemented static check");
    } else {
      out.println(findings.size() + " finding(s) in " + file + " — " + errors + " error(s):");
      out.println();
      for (CheckFinding finding : findings) {
        out.println("  " + finding.code() + "  " + finding.severity() + "  " + finding.component());
        out.println("      " + finding.message());
        finding.witnesses().forEach(witness -> out.println("      e.g. " + witness));
        out.println();
      }
    }

    // What was NOT checked, always. A catalogue that silently skips entries reads as a clean
    // policy, and an analyst has no way to know the difference.
    out.println("Not checked by this build (each needs something that does not exist yet):");
    StaticChecks.notYetImplemented().entrySet().stream()
        .sorted(java.util.Map.Entry.comparingByKey())
        .forEach(entry -> out.println("  " + entry.getKey() + "  " + entry.getValue()));

    return errors > 0 ? Main.EXIT_FINDINGS : Main.EXIT_OK;
  }

  /** {@code psf compile <policy.psf.yaml> [-o out.ir.json]} — PSF to IR JSON. */
  private static int compile(String[] args, PrintStream out, PrintStream err) {
    if (args.length < 3) {
      err.println("usage: bre psf compile <policy.psf.yaml> [-o <out.ir.json>]");
      return Main.EXIT_USAGE;
    }
    try {
      ObjectNode ir = PsfCodec.toIrJson(read(Path.of(args[2])), LOCAL_TENANT);
      List<String> violations = IrSchemaValidator.violations(ir);
      if (!violations.isEmpty()) {
        violations.forEach(v -> out.println("  " + v));
        return Main.EXIT_FINDINGS;
      }
      String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(ir);
      Path target = outputPath(args);
      if (target == null) {
        out.println(json);
      } else {
        Files.writeString(target, json);
        out.println("ok  wrote " + target);
      }
      return Main.EXIT_OK;
    } catch (PsfException e) {
      out.println(e.getMessage());
      return Main.EXIT_FINDINGS;
    } catch (IOException e) {
      err.println("could not read or write: " + e.getMessage());
      return Main.EXIT_USAGE;
    }
  }

  /**
   * {@code psf bundle <policy.psf.yaml> --key <k> --kid <id> [--assets <f.json>] [-o out.json]}.
   */
  private static int bundle(String[] args, PrintStream out, PrintStream err) {
    String keyPath = flag(args, "--key");
    String kid = flag(args, "--kid");
    String assetsPath = flag(args, "--assets");
    if (args.length < 3 || keyPath == null || kid == null) {
      err.println(
          "usage: bre psf bundle <policy.psf.yaml> --key <key.pkcs8> --kid <id>"
              + " [--assets <assets.json>] [-o <f>]");
      return Main.EXIT_USAGE;
    }
    try {
      ObjectNode ir = PsfCodec.toIrJson(read(Path.of(args[2])), LOCAL_TENANT);
      List<String> violations = IrSchemaValidator.violations(ir);
      if (!violations.isEmpty()) {
        out.println("refusing to bundle a policy that is not schema-valid:");
        violations.forEach(v -> out.println("  " + v));
        return Main.EXIT_FINDINGS;
      }

      // A bundle is the thing a pod will actually serve. Signing one that fails its own static
      // checks would put a policy with a known gap behind a valid signature, which is worse than
      // an unsigned one: the signature is what everything downstream trusts instead of looking.
      List<CheckFinding> findings = StaticChecks.run(IrCodec.readPolicy(ir));
      List<CheckFinding> errors = findings.stream().filter(CheckFinding::isError).toList();
      if (!errors.isEmpty()) {
        out.println("refusing to bundle: " + errors.size() + " static check error(s)");
        errors.forEach(f -> out.println("  " + f));
        return Main.EXIT_FINDINGS;
      }

      ObjectNode manifest = MAPPER.createObjectNode();
      manifest.set("policy", ir.get("policy"));
      manifest.set("factSchema", ir.get("factSchema"));
      manifest.putObject("staticCheck").put("errors", 0).put("warnings", findings.size());

      // The bundle's assets are the RESOLVED values, not the refs. The refs are already in the
      // IR; what a pod needs is the parameter values and list contents those refs stood for, fixed
      // at this hash (§8.4, D2). Without --assets the bundle carries none, every `param.X` is
      // Missing, and a policy that reads parameters will REFER on every request — so say that,
      // rather than let it be discovered from an inexplicable decision.
      ObjectNode resolvedAssets;
      if (assetsPath == null) {
        resolvedAssets = MAPPER.createObjectNode();
        if (declaresAssets(ir)) {
          out.println(
              "note: this policy names a parameter set or reference lists but no --assets source"
                  + " was given, so the bundle carries no resolved values. Every param./list."
                  + " reference will be Missing at evaluation.");
        }
      } else {
        JsonNode source = MAPPER.readTree(read(Path.of(assetsPath)));
        resolvedAssets = AssetCodec.resolveForPolicy(source, ir.get("assets"));
        // The Authority Matrix goes back into the IR, where IrCodec reads it from. See
        // AssetCodec.resolveAuthorityMatrix for why it is the one asset that does.
        AssetCodec.resolveAuthorityMatrix(source, ir.get("assets"))
            .ifPresent(matrix -> ((ObjectNode) ir.get("assets")).set("authorityMatrix", matrix));
      }

      Bundle bundle =
          BundleSigner.sign(
              manifest, ir, resolvedAssets, kid, Bundle.Role.ORIGIN, privateKey(Path.of(keyPath)));

      Path target = outputPath(args);
      if (target == null) {
        out.println(bundle.toJson());
      } else {
        Files.writeString(target, bundle.toJson());
        out.println("ok  " + bundle.bundleHash());
        out.println("    wrote " + target);
      }
      return Main.EXIT_OK;
    } catch (PsfException e) {
      out.println(e.getMessage());
      return Main.EXIT_FINDINGS;
    } catch (IOException e) {
      err.println("could not read or write: " + e.getMessage());
      return Main.EXIT_USAGE;
    }
  }

  /** {@code psf verify <bundle.json> --pub <key.x509> --kid <id>}. */
  private static int verify(String[] args, PrintStream out, PrintStream err) {
    String pubPath = flag(args, "--pub");
    String kid = flag(args, "--kid");
    if (args.length < 3 || pubPath == null || kid == null) {
      err.println("usage: bre psf verify <bundle.json> --pub <key.x509> --kid <id>");
      return Main.EXIT_USAGE;
    }
    try {
      Bundle bundle = Bundle.parse(read(Path.of(args[2])));
      BundleVerifier verifier =
          new BundleVerifier(
              TrustStore.builder().ownBundleKey(kid, publicKey(Path.of(pubPath))).build());
      BundleVerifier.Verified verified = verifier.verify(bundle);

      out.println("ok  " + verified.bundleHash());
      out.println("    signed by " + verified.kid() + " as " + verified.role());
      return Main.EXIT_OK;
    } catch (BundleException e) {
      out.println("FAILED  " + e.getMessage());
      return Main.EXIT_FINDINGS;
    } catch (IOException e) {
      err.println("could not read: " + e.getMessage());
      return Main.EXIT_USAGE;
    }
  }

  // ---------------------------------------------------------------------------------------------

  private static boolean declaresAssets(ObjectNode ir) {
    JsonNode assets = ir.get("assets");
    if (assets == null) {
      return false;
    }
    return !assets.path("parameterSet").isNull() || !assets.path("referenceLists").isEmpty();
  }

  private static String read(Path file) throws IOException {
    return Files.readString(file);
  }

  private static Path outputPath(String[] args) {
    return Keys.outputPath(args);
  }

  private static String flag(String[] args, String name) {
    return Keys.flag(args, name);
  }

  private static PrivateKey privateKey(Path file) throws IOException {
    return Keys.privateKey(file);
  }

  private static PublicKey publicKey(Path file) throws IOException {
    return Keys.publicKey(file);
  }

  /**
   * Writes a fresh Ed25519 key pair, for local work only.
   *
   * @param privateFile where the private key goes
   * @param publicFile where the public key goes
   * @throws IOException if either cannot be written
   */
  public static void writeKeyPair(Path privateFile, Path publicFile) throws IOException {
    Keys.writeKeyPair(privateFile, publicFile);
  }

  /** Reads a JSON document, for a caller that already has the text. */
  static JsonNode parse(String json) throws IOException {
    return MAPPER.readTree(json);
  }
}
