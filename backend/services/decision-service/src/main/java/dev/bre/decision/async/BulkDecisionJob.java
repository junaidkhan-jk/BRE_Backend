package dev.bre.decision.async;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bre.decision.api.DecisionApi.DecisionRequest;
import dev.bre.decision.api.DecisionApi.DecisionResponse;
import dev.bre.decision.app.DecideUseCase;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bulk decision jobs over a file (M4-06, FR-EXEC-010, §4.3).
 *
 * <p><b>Streamed, never loaded.</b> NFR-SCALE-02 sizes a job at a million cases, and a million fact
 * sets held in memory is tens of gigabytes — so the input is read a line at a time and each result
 * written before the next is read. The obvious implementation, {@code Files.readAllLines} then a
 * stream over a list, works perfectly on the thousand-row file somebody tests with and takes the
 * pod down on the real one.
 *
 * <p><b>One failed row does not fail the job.</b> A million-row file with one malformed line is not
 * a file somebody wants rejected in its entirety after fifty minutes of work — it is a file with
 * one bad line, and the result file says which. Every row's outcome is written, including the
 * failures, so the output has exactly as many records as the input and a reader can reconcile them
 * by position.
 *
 * <p><b>Each row is a real decision, recorded like any other.</b> Bulk is a different arrival
 * shape, not a different kind of decision: the same engine, the same policy resolution, the same
 * durable write and the same audit trail. A bulk-only path would be a second way to decide, and the
 * two would drift.
 *
 * <p>NDJSON only. §4.3 also names Parquet as an input format; it is not read here, and that is a
 * gap rather than a decision — the archive writer landed first and a Parquet reader for input is
 * the same library over a different path.
 */
public final class BulkDecisionJob {

  private static final Logger log = LoggerFactory.getLogger(BulkDecisionJob.class);

  private final DecideUseCase decide;
  private final ObjectMapper json;

  /**
   * Creates the job runner.
   *
   * @param decide the decision path, shared with every other way of deciding
   * @param json the codec
   */
  public BulkDecisionJob(DecideUseCase decide, ObjectMapper json) {
    this.decide = decide;
    this.json = json;
  }

  /**
   * What a run produced.
   *
   * @param total how many rows were read
   * @param decided how many produced a decision
   * @param failed how many did not, each recorded in the output
   * @param took how long the whole job took
   */
  public record Summary(int total, int decided, int failed, Duration took) {

    /**
     * Rows decided per second, for capacity planning against NFR-SCALE-02.
     *
     * @return the rate, or 0 when the job was instantaneous
     */
    public double perSecond() {
      double seconds = took.toMillis() / 1000.0;
      return seconds > 0 ? decided / seconds : 0;
    }
  }

  /**
   * Runs a job over an NDJSON file, writing an NDJSON result file.
   *
   * @param input one decision request per line
   * @param output one result per line, in the same order
   * @return the summary
   * @throws IOException if the files cannot be read or written
   */
  public Summary run(Path input, Path output) throws IOException {
    Instant started = Instant.now();
    AtomicInteger total = new AtomicInteger();
    AtomicInteger decided = new AtomicInteger();
    AtomicInteger failed = new AtomicInteger();

    try (Stream<String> lines = Files.lines(input, StandardCharsets.UTF_8);
        BufferedWriter out = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {

      lines
          .filter(line -> !line.isBlank())
          .forEach(
              line -> {
                total.incrementAndGet();
                try {
                  DecisionRequest request = json.readValue(line, DecisionRequest.class);
                  DecisionResponse response = decide.decide(request);
                  writeLine(out, Map.of("ok", true, "decision", response));
                  decided.incrementAndGet();
                } catch (RuntimeException
                    | com.fasterxml.jackson.core.JsonProcessingException rowFailed) {
                  failed.incrementAndGet();
                  // The row's failure is RECORDED, not logged and dropped. The output has one
                  // record per input row so a reader can reconcile by position, and the reason
                  // names the exception type and never a fact value (§9.6).
                  writeLine(
                      out,
                      Map.of(
                          "ok",
                          false,
                          "reason",
                          rowFailed.getClass().getSimpleName(),
                          "detail",
                          String.valueOf(rowFailed.getMessage())));
                }
              });
    }

    Summary summary =
        new Summary(
            total.get(), decided.get(), failed.get(), Duration.between(started, Instant.now()));
    log.info(
        "bulk job: {} rows, {} decided, {} failed, {} in {} ms ({} rows/s)",
        summary.total(),
        summary.decided(),
        summary.failed(),
        output.getFileName(),
        summary.took().toMillis(),
        Math.round(summary.perSecond()));
    return summary;
  }

  private void writeLine(BufferedWriter out, Map<String, Object> record) {
    try {
      out.write(json.writeValueAsString(record));
      out.newLine();
    } catch (IOException cannotWrite) {
      // This one DOES fail the job. A row that cannot be decided is one bad row; an output file
      // that cannot be written means every remaining result is lost, and continuing would produce
      // a result file that silently disagrees with its own summary.
      throw new UncheckedIOException("the bulk result file could not be written", cannotWrite);
    }
  }
}
