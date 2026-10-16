package dev.bre.decision.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.bre.decision.api.DecisionApi.DecisionRequest;
import dev.bre.decision.api.DecisionApi.DecisionResponse;
import dev.bre.decision.app.DecideUseCase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Bulk decision jobs (M4-06, FR-EXEC-010). */
class BulkDecisionJobTest {

  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  @Test
  @DisplayName("every row is decided, and the output has one record per input row")
  void everyRowIsDecided(@TempDir Path dir) throws Exception {
    Path input = dir.resolve("in.ndjson");
    Files.writeString(input, rows(100), StandardCharsets.UTF_8);
    Path output = dir.resolve("out.ndjson");

    BulkDecisionJob job = new BulkDecisionJob(deciding(), JSON);
    BulkDecisionJob.Summary summary = job.run(input, output);

    assertThat(summary.total()).isEqualTo(100);
    assertThat(summary.decided()).isEqualTo(100);
    assertThat(summary.failed()).isZero();
    assertThat(Files.readAllLines(output)).hasSize(100);
  }

  @Test
  @DisplayName("one bad row does not fail the job, and its failure is IN the output")
  void oneBadRowDoesNotFailTheJob(@TempDir Path dir) throws Exception {
    // A million-row file with one malformed line is not a file somebody wants rejected in its
    // entirety after fifty minutes of work. It is a file with one bad line, and the output says
    // which — with one record per input row, so a reader can reconcile by position.
    Path input = dir.resolve("in.ndjson");
    Files.writeString(input, rows(2) + "{this is not json}\n" + rows(2), StandardCharsets.UTF_8);
    Path output = dir.resolve("out.ndjson");

    BulkDecisionJob.Summary summary = new BulkDecisionJob(deciding(), JSON).run(input, output);

    assertThat(summary.total()).isEqualTo(5);
    assertThat(summary.decided()).isEqualTo(4);
    assertThat(summary.failed()).isEqualTo(1);

    List<String> out = Files.readAllLines(output);
    assertThat(out).as("one record per input row, including the failure").hasSize(5);
    assertThat(out.get(2)).contains("\"ok\":false");
    assertThat(out.get(0)).contains("\"ok\":true");
    assertThat(out.get(4)).as("rows after the bad one are still decided").contains("\"ok\":true");
  }

  @Test
  @DisplayName("the input is streamed, not loaded")
  void theInputIsStreamed(@TempDir Path dir) throws Exception {
    // NFR-SCALE-02 sizes a job at a million cases. The obvious implementation — readAllLines then
    // a stream over the list — works on the thousand-row file somebody tests with and takes the
    // pod down on the real one. Ten thousand rows is not a million, but it IS enough that a
    // readAllLines implementation would show in the heap, and the assertion below is about the
    // shape rather than the size.
    Path input = dir.resolve("big.ndjson");
    Files.writeString(input, rows(10_000), StandardCharsets.UTF_8);
    Path output = dir.resolve("big-out.ndjson");

    long before = usedHeap();
    BulkDecisionJob.Summary summary = new BulkDecisionJob(deciding(), JSON).run(input, output);
    long growth = usedHeap() - before;

    assertThat(summary.decided()).isEqualTo(10_000);
    assertThat(growth)
        .as("heap growth should not scale with the file: streamed, not loaded")
        .isLessThan(200L * 1024 * 1024);
  }

  @Test
  @DisplayName("blank lines are skipped rather than counted as failures")
  void blankLinesAreSkipped(@TempDir Path dir) throws Exception {
    // A trailing newline is in every file anybody generates, and counting it as a failed row
    // would make every successful job report one failure — the kind of noise that teaches people
    // to ignore the failure count.
    Path input = dir.resolve("in.ndjson");
    Files.writeString(input, rows(3) + "\n\n", StandardCharsets.UTF_8);
    Path output = dir.resolve("out.ndjson");

    BulkDecisionJob.Summary summary = new BulkDecisionJob(deciding(), JSON).run(input, output);
    assertThat(summary.total()).isEqualTo(3);
    assertThat(summary.failed()).isZero();
  }

  // --- plumbing --------------------------------------------------------------------------------

  private static long usedHeap() {
    System.gc();
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }

  private static DecideUseCase deciding() {
    DecideUseCase decide = mock(DecideUseCase.class);
    when(decide.decide(any(DecisionRequest.class)))
        .thenAnswer(
            invocation -> {
              DecisionRequest request = invocation.getArgument(0);
              return new DecisionResponse(
                  UUID.randomUUID().toString(),
                  request.tenantId(),
                  request.requestId(),
                  LocalDate.of(2026, 10, 1),
                  "APPROVE",
                  true,
                  List.of());
            });
    return decide;
  }

  private static String rows(int count) throws IOException {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < count; i++) {
      out.append(
              JSON.writeValueAsString(
                  new DecisionRequest(
                      "t_bulk",
                      "req-" + i,
                      LocalDate.of(2026, 10, 1),
                      java.util.Map.of("application", java.util.Map.of("applicationRef", "A" + i)),
                      "PROD",
                      "MFI_JLG_CREDIT_UW",
                      null)))
          .append('\n');
    }
    return out.toString();
  }
}
