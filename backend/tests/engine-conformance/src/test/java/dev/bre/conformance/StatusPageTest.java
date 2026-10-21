package dev.bre.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps {@code docs/html/status.html} from lying (M5-10 housekeeping).
 *
 * <p><b>Written because the page had drifted by fourteen tasks.</b> It said 50 done, 2 partial, 16
 * not started while the real state was 63 done and 5 waiting on people — and its own subtitle
 * claimed the counts were derived from the rows rather than typed. They were typed. A status page
 * is read by somebody deciding whether a milestone is finished, which makes a stale one worse than
 * no page: it is the only artefact whose entire purpose is to be believed without being checked.
 *
 * <p>So the totals, the per-milestone headers and the rows are reconciled here, and the Build
 * Plan's own task list is the authority for which tasks exist. Updating a row and forgetting the
 * total now fails the build.
 *
 * <p>What this cannot check is whether a row's state is <em>true</em>. That is a judgement, and the
 * most it can do is make the arithmetic honest and insist that no task has been quietly dropped.
 */
class StatusPageTest {

  private static final Pattern ROW =
      Pattern.compile(
          "<td class=\"id\">(M(\\d)-\\d\\d)</td>.*?<td class=\"st\"><span class=\"pill \\w+\">"
              + "([^<]+)</span>",
          Pattern.DOTALL);

  private static final Pattern TOTAL =
      Pattern.compile("<div class=\"tot[^\"]*\"><b>(\\d+)</b><span>([^<]*)</span>");

  private static final Pattern MILESTONE_COUNTS =
      Pattern.compile(
          "<span class=\"kicker\">M(\\d) —[^<]*</span>.*?<div class=\"ms__counts\">(.*?)</div>",
          Pattern.DOTALL);

  private static final Pattern PILL_COUNT =
      Pattern.compile("<span class=\"pill \\w+\">(\\d+) ([a-z ]+)</span>");

  private static Path repoRoot() {
    String root = System.getProperty("bre.repo.root");
    assertThat(root).as("bre.repo.root must be set by the build").isNotNull();
    return Path.of(root).getParent();
  }

  private static String page() throws IOException {
    Path file = repoRoot().resolve("docs/html/status.html");
    assertThat(file).as("the status page is what this test is about").exists();
    return Files.readString(file);
  }

  /** The task ids the Build Plan declares, in order, de-duplicated. */
  private static List<String> planTasks() throws IOException {
    String plan = Files.readString(repoRoot().resolve("BRE_PRD/draft2/05_Build_Plan.md"));
    Matcher matcher =
        Pattern.compile("^\\|\\s*(M\\d-\\d\\d)\\s*\\|", Pattern.MULTILINE).matcher(plan);
    List<String> ids = new java.util.ArrayList<>();
    while (matcher.find()) {
      if (!ids.contains(matcher.group(1))) {
        ids.add(matcher.group(1));
      }
    }
    return ids;
  }

  private static Map<String, String> rows(String page) {
    Map<String, String> states = new LinkedHashMap<>();
    Matcher matcher = ROW.matcher(page);
    while (matcher.find()) {
      states.put(matcher.group(1), matcher.group(3));
    }
    return states;
  }

  @Test
  @DisplayName("every task in the Build Plan has a row, and no row is invented")
  void theRowsAreExactlyTheBuildPlansTasks() throws IOException {
    List<String> plan = planTasks();
    Map<String, String> rows = rows(page());

    assertThat(plan).as("the Build Plan's task table could not be parsed").hasSizeGreaterThan(60);
    // Both directions. A missing row hides a task; an extra row invents one, and an invented task
    // marked done is the more flattering of the two errors.
    assertThat(rows.keySet())
        .as("a task with no row on the status page is a task nobody is tracking")
        .containsAll(plan);
    assertThat(plan)
        .as("the page has a row for a task the Build Plan does not declare")
        .containsAll(rows.keySet());
  }

  @Test
  @DisplayName("the totals equal the rows they claim to summarise")
  void theTotalsAreDerivedFromTheRows() throws IOException {
    String page = page();
    Map<String, String> rows = rows(page);

    Map<String, Integer> byState = new TreeMap<>();
    rows.values().forEach(state -> byState.merge(state, 1, Integer::sum));

    Map<String, Integer> totals = new LinkedHashMap<>();
    Matcher matcher = TOTAL.matcher(page);
    while (matcher.find()) {
      // The label's first word is the state: "done", "needs people — …", "not started", or the
      // bare total whose label starts "tasks".
      String label = matcher.group(2).replace("&mdash;", "—");
      String state = label.contains("—") ? label.substring(0, label.indexOf('—')).trim() : label;
      totals.put(state, Integer.parseInt(matcher.group(1)));
    }

    assertThat(totals).as("the totals block could not be parsed").isNotEmpty();
    assertThat(totals.get("done"))
        .as("the page claims %s done; the rows say %s", totals.get("done"), byState.get("done"))
        .isEqualTo(byState.getOrDefault("done", 0));
    assertThat(totals.get("needs people")).isEqualTo(byState.getOrDefault("needs people", 0));
    assertThat(totals.get("not started"))
        .isEqualTo(byState.getOrDefault("pending", 0) + byState.getOrDefault("not started", 0));

    int declaredTotal =
        totals.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith("tasks"))
            .mapToInt(Map.Entry::getValue)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no overall task total on the page"));
    assertThat(declaredTotal).isEqualTo(rows.size());
  }

  @Test
  @DisplayName("each milestone header's counts equal that milestone's rows")
  void milestoneHeadersAgreeWithTheirRows() throws IOException {
    String page = page();

    Map<String, Map<String, Integer>> rowsByMilestone = new TreeMap<>();
    Matcher rowMatcher = ROW.matcher(page);
    while (rowMatcher.find()) {
      rowsByMilestone
          .computeIfAbsent(rowMatcher.group(2), key -> new TreeMap<>())
          .merge(rowMatcher.group(3), 1, Integer::sum);
    }

    Matcher headers = MILESTONE_COUNTS.matcher(page);
    int checked = 0;
    while (headers.find()) {
      String milestone = headers.group(1);
      Map<String, Integer> declared = new TreeMap<>();
      Matcher pills = PILL_COUNT.matcher(headers.group(2));
      while (pills.find()) {
        declared.put(pills.group(2).trim(), Integer.parseInt(pills.group(1)));
      }
      assertThat(declared)
          .as(
              "M%s's header says %s; its rows say %s",
              milestone, declared, rowsByMilestone.get(milestone))
          .isEqualTo(rowsByMilestone.get(milestone));
      checked++;
    }
    assertThat(checked).as("no milestone headers were parsed, so nothing was checked").isEqualTo(7);
  }

  @Test
  @DisplayName("the page does not claim a check it does not have")
  void theSubtitleIsTrue() throws IOException {
    // The page's subtitle names this class. It said the counts were "derived from the rows" when
    // they were typed by hand, which is how it came to be wrong by fourteen tasks — a claim of
    // verification is itself a thing that has to be verified.
    assertThat(page()).contains("StatusPageTest");
  }
}
