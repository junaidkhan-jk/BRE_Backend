package dev.bre.registry.decisions;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The decision proxy (M5-08).
 *
 * <p>Against a stub HTTP server rather than a mocked client, because the two properties under test
 * are both about <b>what goes on the wire</b> — which tenant header the data plane receives, and
 * what path it receives — and a mock would only assert that this class calls itself the way this
 * class was written.
 */
class DecisionReaderTest {

  private HttpServer server;
  private final List<String> paths = new ArrayList<>();
  private final List<String> tenantHeaders = new ArrayList<>();

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          paths.add(exchange.getRequestURI().toString());
          tenantHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Tenant-Id")));
          byte[] body = "{\"decisionId\":\"d\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopStub() {
    server.stop(0);
  }

  private DecisionReader reader() {
    return new DecisionReader(
        "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(5));
  }

  @Test
  @DisplayName("the tenant on the wire is the one passed in, which comes from the session")
  void theTenantComesFromTheCaller() {
    Optional<DecisionReader.Answer> answer = reader().decision("t_acme", "01a1-abc");

    assertThat(answer).isPresent();
    assertThat(answer.orElseThrow().ok()).isTrue();
    // THE security property. The Decision API trusts X-Tenant-Id and has no authentication, so the
    // only thing standing between a studio user and another lender's decisions is that this header
    // is set here — from the session — and not by the browser.
    assertThat(tenantHeaders).containsExactly("t_acme");
    assertThat(paths).containsExactly("/v1/decisions/01a1-abc");
  }

  @Test
  @DisplayName("a decision id cannot steer the path it is concatenated into")
  void aDecisionIdCannotEscapeItsSegment() {
    // The id arrives from a URL somebody typed. Concatenating it unencoded would let a caller
    // reach a path this proxy never meant to expose — and the tenant scoping above is worth
    // nothing if the path can be chosen.
    reader().decision("t_acme", "../../v1/admin/users");

    // The assertion is about SLASHES, not about dots. A `..` that cannot be followed by a
    // separator is an ordinary two-character id, and the first version of this test asserted the
    // dots were gone — which would have failed a correct implementation and invited somebody to
    // "fix" it by stripping characters instead of encoding them.
    assertThat(paths)
        .singleElement()
        .satisfies(
            path -> {
              String segment = path.substring("/v1/decisions/".length());
              assertThat(path).startsWith("/v1/decisions/");
              assertThat(segment)
                  .as("an encoded segment cannot contain a separator, so it cannot be left")
                  .doesNotContain("/");
              assertThat(path).doesNotContain("/v1/admin");
            });
  }

  @Test
  @DisplayName("the explanation path is the decision's, with /explanation on it")
  void theExplanationPathIsRight() {
    reader().explanation("t_acme", "01a1-abc");

    assertThat(paths).containsExactly("/v1/decisions/01a1-abc/explanation");
    assertThat(tenantHeaders).containsExactly("t_acme");
  }

  @Test
  @DisplayName("with no decision service configured the reader answers empty, not an error")
  void anUnconfiguredReaderIsNotAFailure() {
    DecisionReader unconfigured = new DecisionReader("", Duration.ofSeconds(1));

    // A control plane has to start without the data plane (§10.1 keeps that direction working).
    // Empty is what lets the controller answer 501 "not configured" rather than a 503 that invites
    // a retry, or a 500 that looks like a defect in the registry.
    assertThat(unconfigured.configured()).isFalse();
    assertThat(unconfigured.decision("t_acme", "d")).isEmpty();
    assertThat(unconfigured.explanation("t_acme", "d")).isEmpty();
  }

  @Test
  @DisplayName("an unreachable decision service is a 503, not an exception")
  void anUnreachableServiceDegrades() {
    // A port nothing is listening on. The screen degrades; the registry does not fail.
    DecisionReader unreachable = new DecisionReader("http://127.0.0.1:1", Duration.ofMillis(500));

    DecisionReader.Answer answer = unreachable.decision("t_acme", "d").orElseThrow();

    assertThat(answer.status()).isEqualTo(503);
    assertThat(answer.ok()).isFalse();
  }
}
