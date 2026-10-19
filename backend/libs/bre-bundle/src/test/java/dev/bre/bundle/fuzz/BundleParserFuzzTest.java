package dev.bre.bundle.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.bre.bundle.Bundle;
import dev.bre.bundle.BundleException;

/**
 * Fuzzes the bundle parser (M6-02).
 *
 * <p><b>Why this input and not another.</b> A bundle arrives from another installation, over {@code
 * POST /v1/bundles}, and the whole point of the trusted-exporter list is that the sender is NOT
 * this installation. Until the signature has been checked the bytes are a stranger's, and the parse
 * happens first — so every line of {@code Bundle.parse} runs on attacker-controlled input before
 * any trust decision has been made. That ordering is unavoidable (you cannot verify a signature
 * over a document you have not parsed) and it is exactly what makes this worth fuzzing.
 *
 * <p><b>The contract being asserted is narrow on purpose:</b> malformed input produces a {@link
 * BundleException} and nothing else. Not a {@code NullPointerException}, not a {@code
 * ClassCastException}, not a {@code StackOverflowError} from a deeply nested document — because
 * each of those reaches a caller as a 500 rather than a 4xx, and a 500 on an import endpoint is an
 * availability bug that any sender can trigger at will.
 *
 * <p>Run it: {@code JAZZER_FUZZ=1 ./gradlew :libs:bre-bundle:test --tests '*BundleParserFuzz*'}.
 * Without that variable it replays the committed corpus in milliseconds, so a crash found once
 * becomes a regression test for ever.
 */
class BundleParserFuzzTest {

  /**
   * Parses fuzzer bytes as a bundle document.
   *
   * @param data the fuzzer's bytes, consumed as a string
   */
  @FuzzTest(maxDuration = "30m")
  void parsingOnlyEverThrowsABundleException(FuzzedDataProvider data) {
    String document = data.consumeRemainingAsString();
    try {
      Bundle.parse(document);
    } catch (BundleException expected) {
      // The one permitted outcome. It carries a reason, which is what lets the import endpoint
      // answer 422 with something actionable instead of a bare 400.
      if (expected.reason() == null) {
        throw new AssertionError(
            "a BundleException must name its reason: " + expected.getMessage());
      }
    } catch (StackOverflowError nested) {
      // Called out separately because it is the failure a JSON parser actually has, and it is not
      // an Exception — a catch of RuntimeException would miss it entirely. A document nested a few
      // thousand deep is three lines to write and takes the pod down.
      throw new AssertionError(
          "a deeply nested document overflowed the stack. Any sender can write that document, so"
              + " this is an availability defect rather than a parsing one.",
          nested);
    } catch (RuntimeException unexpected) {
      throw new AssertionError(
          "parsing a bundle threw "
              + unexpected.getClass().getName()
              + ", which reaches the import endpoint as a 500. Malformed input must be a"
              + " BundleException so the caller gets 4xx and a reason.",
          unexpected);
    }
  }
}
