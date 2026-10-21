package dev.bre.registry.decisions;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads a decision from the data plane, on behalf of an authenticated studio user (M5-08).
 *
 * <p><b>Why this exists in the CONTROL plane at all.</b> The Decision API has no authentication —
 * {@code X-Tenant-Id} is a header anybody can set, which is deliberate and is the first finding in
 * {@code docs/THREAT-MODEL.md}: the service is meant to sit behind a gateway. The studio,
 * meanwhile, authenticates against this registry and holds a session with roles and a tenant. A
 * browser calling the decision service directly would therefore be able to read <em>any</em>
 * tenant's decisions by editing one header, so {@code ui/studio-web/src/api/bff.ts} states the
 * studio must never do it.
 *
 * <p>The designed answer is the BFF of TRD §13.1–13.3, and {@code services/studio-service} is still
 * an empty module. Until it exists, this registry is the service the studio is already
 * authenticated against, and proxying the two read endpoints through it is what makes the tenant
 * scope <em>come from the session</em> rather than from a header the caller controls. That is the
 * whole point: {@link #decision} and {@link #explanation} take a tenant the caller cannot choose.
 *
 * <p><b>The deviation, recorded.</b> §2 keeps the control plane off the decision hot path, and this
 * does not change that — nothing here is called while a decision is being made. What it does
 * introduce is the reverse dependency: this one studio screen needs the data plane reachable. The
 * registry being down must not stop decisions (§10.1) and still does not; the decision service
 * being down now degrades a screen, which is the lesser direction and is the price of not shipping
 * an unauthenticated read path to a browser. When studio-service lands, this moves there and the
 * studio changes one base URL.
 *
 * <p><b>Pass-through, not interpretation.</b> The body is relayed as the bytes the decision service
 * produced. Masking is §9.4's and happens there, the explanation is a replay against the pinned
 * bundle, and a registry that re-shaped either would become a second place where the rules about
 * personal data live. There is no unmask path here, deliberately: {@code DECISION_UNMASK} exists as
 * a capability and the data plane has nothing to call for it (M3-09).
 */
@org.springframework.stereotype.Component
public final class DecisionReader {

  private static final Logger log = LoggerFactory.getLogger(DecisionReader.class);

  private final HttpClient client;
  private final String baseUrl;
  private final Duration timeout;

  /**
   * Creates the reader.
   *
   * @param baseUrl the decision service, e.g. {@code http://decision-service:8080}; blank disables
   *     the endpoints rather than failing at startup, because a control plane must come up without
   *     the data plane
   * @param timeoutMillis how long to wait
   */
  // @Autowired because there are TWO constructors. Spring only auto-selects when there is exactly
  // one, so with the package-private test constructor beside this one it fell back to looking for
  // a no-arg and every registry context failed to start — five test classes with
  // initializationError and nothing in the message about this class.
  @org.springframework.beans.factory.annotation.Autowired
  public DecisionReader(
      @org.springframework.beans.factory.annotation.Value("${bre.registry.decision-service-url:}")
          String baseUrl,
      @org.springframework.beans.factory.annotation.Value(
              "${bre.registry.decision-service-timeout-ms:5000}")
          long timeoutMillis) {
    this(baseUrl, Duration.ofMillis(timeoutMillis));
  }

  /**
   * Creates the reader with an explicit timeout, for tests.
   *
   * @param baseUrl the decision service, or blank to disable
   * @param timeout how long to wait
   */
  DecisionReader(String baseUrl, Duration timeout) {
    this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    this.timeout = timeout;
    this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  /**
   * Returns whether a decision service is configured at all.
   *
   * @return true when a base URL was given
   */
  public boolean configured() {
    return !baseUrl.isBlank();
  }

  /** What the data plane answered. */
  public record Answer(int status, String body) {

    /**
     * Returns whether the decision service answered successfully.
     *
     * @return true for a 2xx
     */
    public boolean ok() {
      return status >= 200 && status < 300;
    }
  }

  /**
   * One decision, as recorded.
   *
   * @param tenantId the tenant from the caller's SESSION, never from a request header
   * @param decisionId the decision
   * @return the answer, or empty when no decision service is configured
   */
  public Optional<Answer> decision(String tenantId, String decisionId) {
    return get(tenantId, "/v1/decisions/" + encode(decisionId));
  }

  /**
   * A decision's masked explanation (§9.4), produced by replay against the pinned bundle (D7).
   *
   * @param tenantId the tenant from the caller's SESSION
   * @param decisionId the decision
   * @return the answer, or empty when no decision service is configured
   */
  public Optional<Answer> explanation(String tenantId, String decisionId) {
    return get(tenantId, "/v1/decisions/" + encode(decisionId) + "/explanation");
  }

  private Optional<Answer> get(String tenantId, String path) {
    if (!configured()) {
      return Optional.empty();
    }
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(timeout)
            .header("Accept", "application/json")
            // The tenant the SESSION establishes. The decision service trusts this header, which is
            // exactly why a browser must not be the one setting it.
            .header("X-Tenant-Id", tenantId)
            .GET()
            .build();
    try {
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return Optional.of(new Answer(response.statusCode(), response.body()));
    } catch (IOException unreachable) {
      // The path, never the body: a decision service error body can carry a problem detail, and a
      // registry log is not where a fact value should be able to end up (§9.6).
      log.warn("the decision service did not answer {}: {}", path, unreachable.getMessage());
      return Optional.of(new Answer(503, ""));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return Optional.of(new Answer(503, ""));
    }
  }

  /**
   * Encodes one path segment.
   *
   * <p>Not cosmetic. A decision id arrives from a URL the user typed, and concatenating it into
   * another URL unchecked is how a caller reaches a path this proxy did not mean to expose — the
   * tenant scoping above is worth nothing if the path can be steered.
   */
  private static String encode(String segment) {
    return java.net.URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
