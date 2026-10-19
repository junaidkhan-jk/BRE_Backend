package dev.bre.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bre.facts.FactSchema;
import dev.bre.facts.FactsValidator;
import dev.bre.facts.LendingFacts;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code bre} — the offline command line (M1-15, M6-05).
 *
 * <p>Only the fact-schema commands so far, which are the ones M6-05 names: "fact-schema validation
 * CLI for their CI". The policy-source commands ({@code psf lint|compile|test|bundle|verify}) are
 * M1-15 and need the bundle format, which does not exist yet.
 *
 * <p>Offline by construction. It reads a file, consults a schema compiled into the jar, and writes
 * to stdout — no network, no credentials, nothing to configure. An integrator can run it on a
 * laptop with no access to the platform at all, which is the point: the earliest possible place to
 * find a problem is before anything has been sent.
 */
public final class Main {

  /** What the process exits with when the document is clean. */
  public static final int EXIT_OK = 0;

  /** What it exits with when the document has findings — so a CI step fails. */
  public static final int EXIT_FINDINGS = 1;

  /** What it exits with when it could not even look. */
  public static final int EXIT_USAGE = 2;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Main() {}

  /**
   * Entry point.
   *
   * @param args the command line
   */
  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  /**
   * Runs a command, returning the exit status.
   *
   * <p>Separate from {@link #main} so the behaviour can be tested without a process. A CLI whose
   * exit codes are untested is a CI step that may never fail.
   *
   * @param args the command line
   * @param out where to write output
   * @param err where to write errors
   * @return the exit status
   */
  public static int run(String[] args, PrintStream out, PrintStream err) {
    if (args.length == 0 || isHelp(args[0])) {
      usage(out);
      return args.length == 0 ? EXIT_USAGE : EXIT_OK;
    }
    if ("psf".equals(args[0])) {
      return PsfCommands.run(args, out, err);
    }
    if ("decide".equals(args[0])) {
      return DecideCommand.run(args, out, err);
    }
    if ("parallel-run".equals(args[0])) {
      return ParallelRunCommand.run(args, out, err);
    }
    if ("manifest".equals(args[0])) {
      return ManifestCommands.run(args, out, err);
    }
    if ("keygen".equals(args[0])) {
      return keygen(args, out, err);
    }
    if (!"facts".equals(args[0])) {
      err.println("unknown command '" + args[0] + "'");
      usage(err);
      return EXIT_USAGE;
    }
    if (args.length < 2) {
      usage(err);
      return EXIT_USAGE;
    }

    return switch (args[1]) {
      case "validate" -> validate(args, out, err);
      case "schema" -> printSchema(out);
      default -> {
        err.println("unknown subcommand 'facts " + args[1] + "'");
        usage(err);
        yield EXIT_USAGE;
      }
    };
  }

  private static int validate(String[] args, PrintStream out, PrintStream err) {
    if (args.length < 3) {
      err.println("usage: bre facts validate <facts.json>");
      return EXIT_USAGE;
    }
    Path file = Path.of(args[2]);
    JsonNode facts;
    try {
      facts = MAPPER.readTree(Files.readString(file));
    } catch (IOException e) {
      // The parser's own message can quote the offending text, which in a facts file is a value.
      err.println(file + ": could not be read as JSON");
      return EXIT_USAGE;
    }

    FactSchema schema = LendingFacts.schema();
    FactsValidator validator = new FactsValidator(schema);
    List<FactsValidator.Finding> findings = validator.validate(facts);

    if (findings.isEmpty()) {
      out.println("ok  " + file + " is valid against " + schema.code() + "@" + schema.version());
      return EXIT_OK;
    }

    out.println(
        findings.size()
            + (findings.size() == 1 ? " problem in " : " problems in ")
            + file
            + ", against "
            + schema.code()
            + "@"
            + schema.version());
    out.println();
    for (FactsValidator.Finding finding : findings) {
      out.println("  " + finding.path());
      out.println("      " + finding.problem());
      if (finding.problem().startsWith("is not a field")) {
        validator
            .nearestPath(finding.path())
            .ifPresent(nearest -> out.println("      did you mean: " + nearest));
      }
      out.println();
    }
    out.println("No value from the document appears above, by design (LLD §9.6).");
    return EXIT_FINDINGS;
  }

  private static int printSchema(PrintStream out) {
    FactSchema schema = LendingFacts.schema();
    out.println("# " + schema.code() + "@" + schema.version());
    out.println("# path, BEL type, tags. A list element is addressed with [].");
    out.println();
    schema
        .fields()
        .forEach(
            (path, field) -> {
              StringBuilder line = new StringBuilder();
              line.append(String.format("%-62s %-14s", path, field.belType()));
              if (!field.tags().isEmpty()) {
                line.append(
                    field.tags().stream()
                        .map(tag -> tag.name().toLowerCase(java.util.Locale.ROOT))
                        .sorted()
                        .toList());
              }
              if (field.isProtected()) {
                line.append("  -- rule logic needs an approved governance override (BEL-017)");
              }
              if (field.isAadhaar()) {
                line.append("  -- rule logic may never read this (BEL-026)");
              }
              out.println(line.toString().stripTrailing());
            });
    return EXIT_OK;
  }

  /** {@code bre keygen <private.pkcs8> <public.x509>} — a local signing key, for development. */
  private static int keygen(String[] args, PrintStream out, PrintStream err) {
    if (args.length < 3) {
      err.println("usage: bre keygen <private.pkcs8> <public.x509>");
      return EXIT_USAGE;
    }
    try {
      PsfCommands.writeKeyPair(Path.of(args[1]), Path.of(args[2]));
      out.println("ok  wrote " + args[1] + " and " + args[2]);
      out.println(
          "    FOR LOCAL WORK ONLY. A production signing key is generated in, and never leaves,"
              + " the KMS or HSM (§8.6).");
      return EXIT_OK;
    } catch (IOException e) {
      err.println("could not write the key pair: " + e.getMessage());
      return EXIT_USAGE;
    }
  }

  private static boolean isHelp(String arg) {
    return "-h".equals(arg) || "--help".equals(arg) || "help".equals(arg);
  }

  private static void usage(PrintStream out) {
    out.println(
        """
        bre — BRE command line (offline)

          bre facts validate <facts.json>   Validate a facts document against the canonical
                                            fact schema. Exits 1 on any finding, so it can be
                                            a CI step. Reports paths, never values.

          bre facts schema                  Print every declared fact path, its BEL type and
                                            its §9.1 tags.

          bre psf lint <policy.psf.yaml>    Parse, schema-check and run the §4.13 static
                                            checks. Exits 1 on any ERROR finding. Always
                                            reports what it did NOT check.

          bre psf compile <f> [-o <f>]      PSF to IR JSON.

          bre psf bundle <f> --key <k>      Compile, check, and sign a bre-bundle/1. Refuses
                 --kid <id> [-o <f>]        to sign a policy with a static-check error.

          bre psf verify <bundle> --pub <k> --kid <id>
                                            Recompute the hash and check the signature.

          bre decide <bundle> --facts <f>    Evaluate a signed bundle against a facts document,
                 --pub <k> --kid <id>        offline. Same engine, assets and fact typing the
                 [--as-of <YYYY-MM-DD>]      decision service uses.

          bre parallel-run <bundle>          Decide a file of the lender's own cases and report
                 --cases <cases.jsonl>       every outcome difference GROUPED BY CAUSE, for the
                 --pub <k> --kid <id>        pilot parallel run (PRD 12.1 exit criterion 3).
                 [--out <register.json>]     One JSON object per line, each with its own asOf.
                                             See docs/PARALLEL-RUN.md.

          bre manifest sign <f> --key <k>    Sign an Active Pointer Manifest with a LOCAL key.
                 --kid <id> [-o <f>]         A production manifest is signed by the registry.
          bre manifest verify <f> --pub <k>  Verify one.
          bre manifest show <f>              Print what a cell serves. Does NOT verify.

          bre keygen <private> <public>     An Ed25519 key pair for LOCAL work. A production
                                            key never leaves the KMS.

        Not yet implemented: psf test (M3-04 — needs the test runner).
        """);
  }
}
