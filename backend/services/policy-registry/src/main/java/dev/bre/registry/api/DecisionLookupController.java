package dev.bre.registry.api;

import dev.bre.registry.auth.Principal;
import dev.bre.registry.auth.Role;
import dev.bre.registry.decisions.DecisionReader;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Decision lookup and the explanation viewer, for the studio (M5-08, J7, J8).
 *
 * <p><b>The tenant comes from the session and cannot be chosen.</b> That is the entire reason these
 * two routes exist here rather than the browser calling the decision service: the Decision API
 * trusts {@code X-Tenant-Id}, so a browser that could set it could read any tenant's decisions.
 * Here the header is supplied by {@link DecisionReader} from {@link Principal#tenantId()}, and
 * nothing a caller sends can influence it.
 *
 * <p><b>{@code DECISION_READ_MASKED}, which already existed.</b> The capability was defined before
 * there was anything to gate with it, held by the roles that should see a borrower's decision and
 * not by a policy author who happens to have {@code POLICY_READ}. {@code DECISION_UNMASK} also
 * exists and is deliberately NOT used: §9.4's unmask needs a recorded reason and an audit event,
 * and the data plane has no unmask route to call (M3-09). An unmask path gated on a capability but
 * backed by nothing would be worse than its absence.
 *
 * <p><b>Relayed, not re-shaped.</b> The decision service's body is passed through as produced.
 * Masking is §9.4's and happens there; the explanation is a replay against the bundle the decision
 * was pinned to (D7). A registry that reformatted either would become a second place where the
 * rules about personal data live, and the two would drift.
 *
 * <p>This is a stand-in for the BFF of TRD §13.1–13.3, whose module is still empty. The deviation
 * and its cost — this screen needs the data plane reachable — are recorded on {@link
 * DecisionReader}.
 */
@RestController
@RequestMapping("/v1/decisions")
public class DecisionLookupController {

  private final DecisionReader decisions;

  /**
   * Creates the controller.
   *
   * @param decisions the data-plane reader
   */
  public DecisionLookupController(DecisionReader decisions) {
    this.decisions = decisions;
  }

  /**
   * One decision, as the data plane recorded it.
   *
   * @param request the request, carrying the authenticated principal
   * @param decisionId the decision
   * @return the decision service's answer, relayed
   */
  @GetMapping("/{decisionId}")
  public ResponseEntity<String> decision(
      HttpServletRequest request, @PathVariable String decisionId) {
    Principal caller = Authorisation.require(request, Role.Capability.DECISION_READ_MASKED);
    return relay(decisions.decision(caller.tenantId(), decisionId));
  }

  /**
   * A decision's masked explanation, produced by replay (D7, §9.4).
   *
   * @param request the request, carrying the authenticated principal
   * @param decisionId the decision
   * @return the decision service's answer, relayed
   */
  @GetMapping("/{decisionId}/explanation")
  public ResponseEntity<String> explanation(
      HttpServletRequest request, @PathVariable String decisionId) {
    Principal caller = Authorisation.require(request, Role.Capability.DECISION_READ_MASKED);
    return relay(decisions.explanation(caller.tenantId(), decisionId));
  }

  /**
   * Relays the data plane's answer, including its status.
   *
   * <p>The status is relayed rather than normalised to 200-or-500, because the decision service's
   * statuses carry meaning a screen needs: a 404 is "no such decision", a 409 is "this decision
   * cannot be explained" — the facts were not stored, or the keys are gone — and a 503 is "ask
   * again". Collapsing them would make every one of those read as a failure of the studio.
   */
  private static ResponseEntity<String> relay(
      java.util.Optional<DecisionReader.Answer> maybeAnswer) {
    if (maybeAnswer.isEmpty()) {
      // 501 rather than 404 or 503. The route exists and the installation has not been given a
      // decision service to read, which is neither "no such decision" nor "try again" — and a
      // screen that said either would send somebody looking for a decision that is there.
      org.springframework.http.ProblemDetail problem =
          org.springframework.http.ProblemDetail.forStatus(
              org.springframework.http.HttpStatus.NOT_IMPLEMENTED);
      problem.setTitle("Decision lookup is not configured");
      problem.setDetail(
          "This registry has no bre.registry.decision-service-url, so it cannot read decisions."
              + " Decision lookup and the explanation viewer need the data plane reachable from"
              + " the control plane.");
      return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_IMPLEMENTED)
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .body(writeProblem(problem));
    }
    DecisionReader.Answer answer = maybeAnswer.get();
    return ResponseEntity.status(answer.status())
        .contentType(MediaType.APPLICATION_JSON)
        .body(answer.body());
  }

  private static String writeProblem(org.springframework.http.ProblemDetail problem) {
    return """
        {"type":"about:blank","title":"%s","status":%d,"detail":"%s"}"""
        .formatted(problem.getTitle(), problem.getStatus(), problem.getDetail());
  }
}
