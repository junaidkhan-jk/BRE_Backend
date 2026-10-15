package dev.bre.registry.auth;

import dev.bre.common.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the bearer token on every request, and refuses the request when there is none.
 *
 * <p><b>Default deny.</b> The allow-list below is the complete set of paths reachable without a
 * session, and it is three entries long. Writing it the other way round — a list of protected paths
 * — is how an endpoint added next month ends up unauthenticated: forgetting to add a path to a
 * deny-list fails open, forgetting to add one to an allow-list fails closed and is noticed
 * immediately.
 *
 * <p><b>The tenant comes from the session, never from a header.</b> This is the difference between
 * this service and the decision service, where {@code X-Tenant-Id} is an unverified claim. Here a
 * request acts on the tenant the authenticated account belongs to, so no header can move it to
 * another lender's policies.
 */
@Component
@Order(1)
public class AuthFilter extends OncePerRequestFilter {

  /**
   * Everything reachable without a session. Complete, and deliberately short.
   *
   * <p>{@code /v1/auth/login} has to be open or nobody could log in. The actuator health endpoint
   * is open because a load balancer cannot hold a session. Nothing else is.
   */
  private static final Set<String> OPEN_PATHS =
      Set.of("/v1/auth/login", "/actuator/health", "/error");

  /**
   * The API documentation, when it is published (M6-05).
   *
   * <p>Prefixes rather than exact paths, because the Swagger UI is a directory of assets. That is
   * the reason it is a separate, CONFIGURABLE list rather than three more entries in {@code
   * OPEN_PATHS} above: that set is exact-match and complete, and a prefix rule is a materially
   * weaker thing — {@code /v3/api-docs} as a prefix also opens {@code /v3/api-docsanything}.
   * Keeping them apart means the strong guarantee above stays strong and readable.
   *
   * <p><b>What this exposes, and the judgement.</b> The document is the API's SHAPE — paths,
   * parameters, schemas — and never a lender's data; every endpoint it describes still requires a
   * session and a capability. An integrator needs it, which is what M6-05 is for. But it is also a
   * complete inventory of the control plane's attack surface handed to anybody who can reach the
   * port, so it is off by default and {@code bre.openapi.public} turns it on. devstack turns it on;
   * a production deployment should serve it from the gateway to named integrators, or leave it off
   * and send them the file from {@code docs/api/}.
   */
  private static final List<String> DOC_PREFIXES =
      List.of("/v3/api-docs", "/swagger-ui", "/swagger-ui.html");

  private final AuthService authService;
  private final boolean publicDocs;

  /**
   * Creates the filter.
   *
   * @param authService resolves tokens
   * @param publicDocs whether the OpenAPI document and Swagger UI are reachable without a session
   */
  public AuthFilter(
      AuthService authService,
      @org.springframework.beans.factory.annotation.Value("${bre.openapi.public:false}")
          boolean publicDocs) {
    this.authService = authService;
    this.publicDocs = publicDocs;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    try {
      filter(request, response, chain);
    } catch (FilterFailure wrapped) {
      rethrow(wrapped);
    }
  }

  private static void rethrow(FilterFailure wrapped) throws ServletException, IOException {
    Throwable cause = wrapped.getCause();
    if (cause instanceof ServletException servlet) {
      throw servlet;
    }
    if (cause instanceof IOException io) {
      throw io;
    }
    throw new ServletException(cause);
  }

  private void filter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    if (OPEN_PATHS.contains(request.getRequestURI()) || isPublicDoc(request.getRequestURI())) {
      chain.doFilter(request, response);
      return;
    }

    // Which resolver, decided by the SHAPE of the token rather than by trying both.
    //
    // A local session token is opaque base64 with no dots; a JWT is three dot-separated segments.
    // Trying both would mean every expired IdP token also became a database lookup for a session
    // that cannot exist — and, worse, that a local token was fed through JWT parsing, where a
    // library's parse of attacker-supplied input is a surface worth not having. One shape, one
    // path.
    Optional<Principal> principal =
        bearerToken(request)
            .flatMap(
                token ->
                    OidcVerifier.looksLikeJwt(token)
                        ? authService.resolveOidcToken(token)
                        : authService.resolve(token));
    if (principal.isEmpty()) {
      // 401 with a problem body, and no detail about why. "Unknown token", "expired" and "revoked"
      // are all the same answer to a caller who should not have been here.
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setHeader("WWW-Authenticate", "Bearer");
      response.setContentType("application/problem+json");
      response
          .getWriter()
          .write(
              """
              {"type":"https://bre.dev/problems/unauthenticated","title":"Not authenticated",\
              "status":401,"detail":"Sign in and present the session token as \
              'Authorization: Bearer <token>'."}""");
      return;
    }

    request.setAttribute(Principal.ATTRIBUTE, principal.get());

    // Everything downstream runs with the tenant in scope, so TenantScopedDataSource can set
    // bre.tenant_id on each connection and row-level security has something to compare against.
    // The tenant comes from the SESSION, never from a header — so no request can act on another
    // lender's data by changing one.
    TenantContext.runIn(
        principal.get().tenantId(),
        (Runnable)
            () -> {
              try {
                chain.doFilter(request, response);
              } catch (ServletException | IOException e) {
                throw new FilterFailure(e);
              }
            });
  }

  /**
   * Carries a checked exception out of the scoped block.
   *
   * <p>{@code TenantContext.runIn} takes a Runnable, which cannot throw a checked exception — but a
   * filter chain throws {@code ServletException} and {@code IOException}. This wraps on the way out
   * and {@link #rethrow} unwraps, so the container still sees the exception type it expects rather
   * than a generic one.
   */
  private static final class FilterFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    FilterFailure(Exception cause) {
      super(cause);
    }
  }

  private boolean isPublicDoc(String uri) {
    return publicDocs && DOC_PREFIXES.stream().anyMatch(uri::startsWith);
  }

  private static Optional<String> bearerToken(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return Optional.empty();
    }
    String token = header.substring(7).trim();
    return token.isEmpty() ? Optional.empty() : Optional.of(token);
  }
}
