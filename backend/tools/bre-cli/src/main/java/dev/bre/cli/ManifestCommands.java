package dev.bre.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.bre.bundle.PointerManifest;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;

/**
 * {@code bre manifest sign | verify | show} — the Active Pointer Manifest, offline (LLD §8.1).
 *
 * <p>This exists because signing a manifest correctly is not something a shell script or a Python
 * helper should be doing. The signature is over {@code UTF-8("bre-manifest/1:") ||
 * SHA-256(BRE-JCS(document without "signature"))}, and BRE-JCS is this project's own integers-only
 * profile of RFC 8785 — a second implementation of it in another language would be a second thing
 * that can disagree with the engine about what a document hashes to. One implementation, called
 * from wherever a manifest needs signing: the devstack, a developer's laptop, and later the
 * registry's own deployment path.
 *
 * <p>A manifest signed here is signed with a <b>local</b> key. The cell manifest key lives in a KMS
 * (§8.6) and a production manifest is signed by the registry, never by a CLI.
 */
final class ManifestCommands {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ManifestCommands() {}

  static int run(String[] args, PrintStream out, PrintStream err) {
    if (args.length < 2) {
      err.println("usage: bre manifest <sign|verify|show> ...");
      return Main.EXIT_USAGE;
    }
    return switch (args[1]) {
      case "sign" -> sign(args, out, err);
      case "verify" -> verify(args, out, err);
      case "show" -> show(args, out, err);
      default -> {
        err.println("unknown subcommand 'manifest " + args[1] + "'");
        yield Main.EXIT_USAGE;
      }
    };
  }

  /** {@code manifest sign <unsigned.json> --key <k.pkcs8> --kid <id> [-o <f>]}. */
  private static int sign(String[] args, PrintStream out, PrintStream err) {
    String keyPath = Keys.flag(args, "--key");
    String kid = Keys.flag(args, "--kid");
    if (args.length < 3 || keyPath == null || kid == null) {
      err.println("usage: bre manifest sign <unsigned.json> --key <k.pkcs8> --kid <id> [-o <f>]");
      return Main.EXIT_USAGE;
    }
    try {
      JsonNode document = MAPPER.readTree(Files.readString(Path.of(args[2])));
      if (!document.isObject()) {
        err.println("a manifest must be a JSON object");
        return Main.EXIT_USAGE;
      }
      // Parsed before signing, not after. Signing a document this build cannot read would produce
      // a manifest that verifies and is then rejected as malformed — a failure that looks like a
      // key problem and is not one.
      PointerManifest parsed = PointerManifest.of(document);
      ObjectNode signed =
          PointerManifest.sign((ObjectNode) document, kid, Keys.privateKey(Path.of(keyPath)));
      String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(signed);
      Path target = Keys.outputPath(args);
      if (target == null) {
        out.println(json);
      } else {
        Files.writeString(target, json);
        out.println(
            "ok  cell "
                + parsed.cellId()
                + " manifest version "
                + parsed.manifestVersion()
                + ", "
                + parsed.entries().size()
                + " entr"
                + (parsed.entries().size() == 1 ? "y" : "ies"));
      }
      return Main.EXIT_OK;
    } catch (dev.bre.bundle.BundleException malformed) {
      err.println("not a well-formed " + PointerManifest.FORMAT + ": " + malformed.getMessage());
      return Main.EXIT_FINDINGS;
    } catch (IOException e) {
      err.println("could not sign: " + e.getMessage());
      return Main.EXIT_USAGE;
    }
  }

  /** {@code manifest verify <manifest.json> --pub <k.x509>}. */
  private static int verify(String[] args, PrintStream out, PrintStream err) {
    String pubPath = Keys.flag(args, "--pub");
    if (args.length < 3 || pubPath == null) {
      err.println("usage: bre manifest verify <manifest.json> --pub <k.x509>");
      return Main.EXIT_USAGE;
    }
    try {
      JsonNode document = MAPPER.readTree(Files.readString(Path.of(args[2])));
      PublicKey key = Keys.publicKey(Path.of(pubPath));
      if (!PointerManifest.verify(document, key)) {
        out.println("FAIL  the signature does not verify against that key");
        return Main.EXIT_FINDINGS;
      }
      PointerManifest manifest = PointerManifest.of(document);
      out.println("ok  cell " + manifest.cellId() + " version " + manifest.manifestVersion());
      return Main.EXIT_OK;
    } catch (dev.bre.bundle.BundleException malformed) {
      err.println("not a well-formed " + PointerManifest.FORMAT + ": " + malformed.getMessage());
      return Main.EXIT_FINDINGS;
    } catch (IOException e) {
      err.println("could not read: " + e.getMessage());
      return Main.EXIT_USAGE;
    }
  }

  /** {@code manifest show <manifest.json>} — what this cell serves, without verifying it. */
  private static int show(String[] args, PrintStream out, PrintStream err) {
    if (args.length < 3) {
      err.println("usage: bre manifest show <manifest.json>");
      return Main.EXIT_USAGE;
    }
    try {
      PointerManifest manifest = PointerManifest.parse(Files.readString(Path.of(args[2])));
      out.println(
          "cell "
              + manifest.cellId()
              + "  version "
              + manifest.manifestVersion()
              + "  issued "
              + manifest.issuedAt());
      // Deliberately says so: show is for looking at a file, not for deciding to trust it.
      out.println("  (not verified; use 'manifest verify' for that)");
      for (PointerManifest.Entry e : manifest.entries()) {
        out.println(
            "  "
                + e.tenantId()
                + "  "
                + e.environment()
                + "  "
                + e.policyCode()
                + "  champion "
                + e.champion().bundleHash()
                + (e.pinningAllowed() ? "  pinning allowed" : "  pinning refused"));
        for (PointerManifest.Challenger c : e.challengers()) {
          out.println(
              "      challenger "
                  + c.bundleHash()
                  + "  buckets ["
                  + c.bucketFrom()
                  + ","
                  + c.bucketTo()
                  + ")  experiment "
                  + c.experimentId());
        }
        for (PointerManifest.Servable s : e.servable()) {
          out.println(
              "      servable   "
                  + s.bundleHash()
                  + s.championUntil().map(u -> "  champion until " + u).orElse("  (champion)"));
        }
      }
      return Main.EXIT_OK;
    } catch (dev.bre.bundle.BundleException malformed) {
      err.println("not a well-formed " + PointerManifest.FORMAT + ": " + malformed.getMessage());
      return Main.EXIT_FINDINGS;
    } catch (IOException e) {
      err.println("could not read: " + e.getMessage());
      return Main.EXIT_USAGE;
    }
  }
}
