package dev.bre.perf;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * The decision service under load: 500 decisions a second, held (M2-08).
 *
 * <p><b>Why this exists separately from the JMH benchmarks.</b> M1-16 measures the <em>engine</em>:
 * a 100-rule decision has a p99 of 198 µs in-process. That number says nothing about the service.
 * Between the engine and the caller sit JSON decoding, bean validation, the policy cache lookup,
 * the idempotency read, the durable decision-log write, a connection from a pool of sixteen, and
 * virtual-thread scheduling under concurrency. NFR-PERF-01's budget — p50 ≤ 20 ms, p99 ≤ 100 ms
 * including the durable write — is a budget on all of that, and a 198 µs engine leaves it either
 * comfortable or not depending entirely on the parts this measures.
 *
 * <p><b>Why not Gatling, which the Build Plan names.</b> Gatling's value is its scenario DSL:
 * multi-step user journeys, feeders, think time, pacing across populations. This is one endpoint
 * with one body shape and a fixed rate. Taking it would add a Scala toolchain, a build plugin and a
 * reporting pipeline to a build whose containment rule (D11) is deliberately narrow, in exchange
 * for a scenario description that is three lines of Java here. The measurement itself is the same
 * arithmetic either way, and this harness does the part Gatling is genuinely good at —
 * <b>open-model arrival</b> — correctly, which is the part most hand-rolled load tests get wrong.
 * See below.
 *
 * <p><b>The open model, and why it is not optional.</b> A closed-model harness — N threads each
 * sending the next request as soon as the last one returns — cannot measure what it claims to. When
 * the service slows down, a closed harness slows down with it and the offered load silently falls;
 * the latency it reports is the latency of whatever rate the service was willing to accept, not the
 * rate asked for. That is coordinated omission, and it makes an overloaded service look fine. This
 * harness schedules each request at a fixed wall-clock instant and measures from that scheduled
 * instant — so a request the harness could not even start on time still counts its full lateness.
 *
 * <p>Run it against a service that is already up — {@code ./devstack/up.sh} — so what is measured
 * is a running service rather than a JVM still warming up inside the same process:
 *
 * <pre>{@code
 * ./devstack/up.sh
 * cd backend && ./gradlew :tests:perf:load --args="--url http://127.0.0.1:8080 \
 *     --facts ../reference-policy/fixtures/approve-5-member-jlg.json \
 *     --tenant t_devstack --rate 500 --duration 60"
 * }</pre>
 *
 * <p><b>Measured on 2026-10-09</b>, against the MFI reference policy's five-member approve fixture
 * with the IN_MEMORY decision log, on a 16-core workstation:
 *
 * <table border="1">
 *   <caption>Offered rate against observed latency</caption>
 *   <tr><th>offered</th><th>achieved</th><th>p50</th><th>p99</th><th>max</th><th>failures</th></tr>
 *   <tr><td>500/s, 60s</td><td>500.0/s</td><td>1 ms</td><td>14 ms</td><td>43 ms</td><td>none</td></tr>
 *   <tr><td>2,000/s, 20s</td><td>1,999.9/s</td><td>1 ms</td><td>64 ms</td><td>157 ms</td><td>none</td></tr>
 * </table>
 *
 * <p>M2-08's target is 500/s and it is met with the p99 at 14 ms against a 100 ms budget. The
 * 2,000/s row is there because a single passing number tells you nothing about headroom: the
 * service holds four times the target rate, p50 does not move at all, and the whole cost of the
 * extra load lands in the tail — which is the signature of queueing rather than of work getting
 * slower. Two things to keep in mind before reading either row as a production figure: the decision
 * log was IN_MEMORY, so NFR-PERF-01's durable write is <b>not</b> in these numbers, and both runs
 * were on loopback, so no network is either.
 *
 * <p>Exits non-zero when a budget is missed or when any request failed, so it can be a CI gate on
 * an environment that has one. It is not in the PR gate: a shared runner cannot hold 500 requests a
 * second steadily, and a load test that reports the runner's noise as the service's latency is
 * worse than no load test.
 */
public final class DecisionLoadTest {

  /** NFR-PERF-01, end to end and including the durable write. */
  private static final long P50_BUDGET_MS = 20;

  /** NFR-PERF-01. */
  private static final long P99_BUDGET_MS = 100;

  private DecisionLoadTest() {}

  /**
   * Runs the load test.
   *
   * @param args see the class documentation
   * @throws Exception if the facts file cannot be read
   */
  public static void main(String[] args) throws Exception {
    String url = flag(args, "--url", "http://127.0.0.1:8080");
    String tenant = flag(args, "--tenant", "t_devstack");
    String policy = flag(args, "--policy", "MFI_JLG_CREDIT_UW");
    String asOf = flag(args, "--as-of", "2026-10-01");
    Path factsFile =
        Path.of(flag(args, "--facts", "../reference-policy/fixtures/approve-5-member-jlg.json"));
    int rate = Integer.parseInt(flag(args, "--rate", "500"));
    int seconds = Integer.parseInt(flag(args, "--duration", "60"));
    int warmup = Integer.parseInt(flag(args, "--warmup", "5"));

    String facts = Files.readString(factsFile).trim();
    int total = rate * seconds;

    System.out.printf(
        "%n  %s  %d req/s for %ds (%,d requests), %ds warm-up%n    facts %s%n%n",
        url, rate, seconds, total, warmup, factsFile.getFileName());

    HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            // Virtual threads, so the harness is not the bottleneck: at 500 req/s with a 100 ms
            // p99 budget there are ~50 requests in flight, and a platform-thread pool sized for
            // that is a pool whose queueing is indistinguishable from the server's.
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

    // Warm-up is not measured. The JIT, the policy cache, the connection pool and the first
    // signature verification all cost something once, and reporting them as steady-state latency
    // would be reporting a number the service never actually exhibits.
    if (warmup > 0) {
      run(client, url, tenant, policy, asOf, facts, rate, warmup, "warmup-", null, null);
    }

    List<Long> latencies = new ArrayList<>(total);
    Map<String, Integer> failures = new ConcurrentHashMap<>();
    long wall =
        run(client, url, tenant, policy, asOf, facts, rate, seconds, "load-", latencies, failures);

    report(latencies, failures, rate, wall);

    boolean failed = !failures.isEmpty();
    long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
    if (sorted.length > 0) {
      failed |= percentile(sorted, 50) > P50_BUDGET_MS || percentile(sorted, 99) > P99_BUDGET_MS;
    }
    System.exit(failed ? 1 : 0);
  }

  /** Issues {@code rate × seconds} requests on a fixed schedule; returns the wall time in ms. */
  private static long run(
      HttpClient client,
      String url,
      String tenant,
      String policy,
      String asOf,
      String facts,
      int rate,
      int seconds,
      String prefix,
      List<Long> latencies,
      Map<String, Integer> failures)
      throws InterruptedException {

    int total = rate * seconds;
    long intervalNanos = TimeUnit.SECONDS.toNanos(1) / rate;
    long start = System.nanoTime();
    LongAdder done = new LongAdder();
    AtomicLong firstError = new AtomicLong();
    List<Long> collected =
        latencies == null ? null : java.util.Collections.synchronizedList(latencies);

    try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int index = 0; index < total; index++) {
        final int i = index;
        // The instant this request was SUPPOSED to go out. Latency is measured from here, not from
        // when the harness managed to start it — that is the whole point of the open model.
        long scheduled = start + i * intervalNanos;
        long waitNanos = scheduled - System.nanoTime();
        if (waitNanos > 0) {
          TimeUnit.NANOSECONDS.sleep(waitNanos);
        }

        // The request id must be unique: a repeat is idempotent (D5) and returns the stored
        // decision without evaluating anything, so reusing ids would measure the cache.
        String body = request(tenant, prefix + i, asOf, policy, facts);
        workers.execute(
            () -> {
              try {
                HttpResponse<String> response =
                    client.send(
                        HttpRequest.newBuilder(URI.create(url + "/v1/decisions"))
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(30))
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                        HttpResponse.BodyHandlers.ofString());
                long elapsedMs = (System.nanoTime() - scheduled) / 1_000_000;
                if (response.statusCode() != 201) {
                  record(failures, "HTTP " + response.statusCode(), firstError, response.body());
                } else if (collected != null) {
                  collected.add(elapsedMs);
                }
              } catch (IOException | InterruptedException e) {
                record(
                    failures,
                    e.getClass().getSimpleName(),
                    firstError,
                    String.valueOf(e.getMessage()));
                if (e instanceof InterruptedException) {
                  Thread.currentThread().interrupt();
                }
              } finally {
                done.increment();
              }
            });
      }
    }
    return (System.nanoTime() - start) / 1_000_000;
  }

  private static void record(
      Map<String, Integer> failures, String kind, AtomicLong first, String detail) {
    if (failures == null) {
      return;
    }
    failures.merge(kind, 1, Integer::sum);
    // One sample of the first failure, truncated. A load test that prints every failure body
    // drowns its own result, and the bodies may carry facts (§9.6) — so one, short, and only when
    // something actually went wrong.
    if (first.compareAndSet(0, 1)) {
      System.out.printf(
          "  first failure (%s): %s%n",
          kind, detail == null ? "" : detail.substring(0, Math.min(200, detail.length())));
    }
  }

  private static String request(
      String tenant, String requestId, String asOf, String policy, String facts) {
    return "{\"tenantId\":\""
        + tenant
        + "\",\"requestId\":\""
        + requestId
        + "\",\"asOf\":\""
        + asOf
        + "\",\"environment\":\"PROD\",\"policyCode\":\""
        + policy
        + "\",\"facts\":"
        + facts
        + "}";
  }

  private static void report(
      List<Long> latencies, Map<String, Integer> failures, int rate, long wallMs) {
    long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
    if (sorted.length == 0) {
      System.out.println("  no successful requests — nothing to report");
      failures.forEach((kind, count) -> System.out.printf("    %-24s %,d%n", kind, count));
      return;
    }

    System.out.printf("  %-14s %,d%n", "requests", sorted.length);
    System.out.printf(
        "  %-14s %.1f req/s offered, %.1f achieved%n",
        "rate", (double) rate, sorted.length * 1000.0 / wallMs);
    System.out.printf("  %-14s %d ms%n", "min", sorted[0]);
    for (int p : new int[] {50, 90, 95, 99}) {
      long value = percentile(sorted, p);
      String verdict =
          p == 50 ? budget(value, P50_BUDGET_MS) : p == 99 ? budget(value, P99_BUDGET_MS) : "";
      System.out.printf("  %-14s %d ms  %s%n", "p" + p, value, verdict);
    }
    System.out.printf("  %-14s %d ms%n", "max", sorted[sorted.length - 1]);

    if (failures.isEmpty()) {
      System.out.printf("%n  no failures%n%n");
    } else {
      System.out.printf("%n  FAILURES%n");
      failures.forEach((kind, count) -> System.out.printf("    %-24s %,d%n", kind, count));
      System.out.println();
    }
  }

  private static String budget(long value, long budgetMs) {
    return value <= budgetMs ? "(budget " + budgetMs + " ms)" : "OVER BUDGET — " + budgetMs + " ms";
  }

  /** The nearest-rank percentile of an ascending array. */
  private static long percentile(long[] ascending, int p) {
    int index = (int) Math.ceil(p / 100.0 * ascending.length) - 1;
    return ascending[Math.max(0, Math.min(index, ascending.length - 1))];
  }

  private static String flag(String[] args, String name, String fallback) {
    int at = Arrays.asList(args).indexOf(name);
    return at >= 0 && at + 1 < args.length ? args[at + 1] : fallback;
  }
}
