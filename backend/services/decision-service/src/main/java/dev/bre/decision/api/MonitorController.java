package dev.bre.decision.api;

import dev.bre.decision.verify.Finding;
import dev.bre.decision.verify.OutcomeMonitors;
import dev.bre.decision.verify.ReplayVerificationJob;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The outcome guardrails and replay verification, as an endpoint (M4-07, §11.4).
 *
 * <p><b>Findings, not alerts.</b> This service says what is true; a paging system decides who is
 * woken. Each finding carries a severity §11.4 assigns, and an operator's alerting scrapes this and
 * applies their own routing — which is the right split, because an on-call rota changes far more
 * often than a credit engine and must not be versioned with it.
 */
@RestController
@RequestMapping("/v1/monitors")
public class MonitorController {

  private final OutcomeMonitors monitors;
  private final ReplayVerificationJob verification;

  /**
   * Creates the controller.
   *
   * @param monitors the outcome guardrails
   * @param verification the replay-verification job
   */
  public MonitorController(OutcomeMonitors monitors, ReplayVerificationJob verification) {
    this.monitors = monitors;
    this.verification = verification;
  }

  /**
   * Runs the outcome guardrails for the calling tenant.
   *
   * @param tenantId the tenant, from {@code X-Tenant-Id}
   * @return the findings, empty when nothing crossed a threshold
   */
  @GetMapping
  public List<Finding> outcomes(@RequestHeader("X-Tenant-Id") String tenantId) {
    return monitors.check(tenantId);
  }

  /**
   * Runs replay verification over a sample.
   *
   * <p>A POST because it is work rather than a read: it replays policy over real decisions, which
   * on a large sample is minutes of CPU and not something a dashboard should trigger by refreshing.
   *
   * @param tenantId the tenant
   * @param sample how many decisions to draw
   * @return the result, including what it could NOT check
   */
  @PostMapping("/replay-verification")
  public Map<String, Object> verifyReplay(
      @RequestHeader("X-Tenant-Id") String tenantId,
      @RequestParam(defaultValue = "1000") int sample) {

    ReplayVerificationJob.Result result = verification.verify(tenantId, sample);
    return Map.of(
        "sampled", result.sampled(),
        "verified", result.verified(),
        "mismatched", result.mismatched(),
        "notReplayable", result.notReplayable(),
        "shredded", result.shredded(),
        // Reported explicitly, because zero mismatches out of zero replays is NOT a pass and a
        // caller reading only "mismatched: 0" would believe it was one.
        "meaningful", result.meaningful(),
        "findings", result.findings());
  }
}
