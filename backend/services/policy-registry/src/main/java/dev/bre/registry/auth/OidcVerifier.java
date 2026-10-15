package dev.bre.registry.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.URI;
import java.net.URL;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Verifies an identity provider's JWT (M3-09, FR-SEC OIDC).
 *
 * <p><b>The identity provider says who you are. This service says what you may do.</b> That split
 * is the whole design, and it is not the conventional one — most integrations read roles out of a
 * claim. Here the token is used for one thing: establishing a {@code sub}. The tenant and the roles
 * then come from {@code users} and {@code role_assignments} in this installation's own database,
 * keyed on that subject, which is exactly what the local account table already does.
 *
 * <p>Reading roles from a claim would mean whoever administers the IdP can grant {@code
 * DEPLOY_APPROVE} on a lender's credit policy by editing a group mapping — without appearing
 * anywhere in this system's audit trail, and without the lender's own administrators being involved
 * in a decision 00 §3.3 says needs two of them. A directory administrator is not a credit-risk
 * approver, and the two jobs are not held by the same team at any lender. So a subject arriving
 * with a valid token and no user row here gets a 401: authenticated, and provisioned for nothing.
 *
 * <p><b>Nothing here is hand-rolled crypto.</b> The signature check, the JWKS fetch with its
 * caching and rate limiting, and the standard claim validation are all nimbus-jose-jwt's — the same
 * library Spring Security's resource server uses underneath. What this class owns is the policy:
 * which algorithms, which issuer, which audience, and where the roles come from.
 *
 * <p><b>Why not {@code spring-boot-starter-oauth2-resource-server}.</b> It installs its own filter
 * chain and its own authorisation model, and this service deliberately checks capabilities
 * explicitly in each method body rather than declaratively — see {@code Authorisation}. Two
 * mechanisms deciding who may call what is a defect waiting for the day they disagree.
 *
 * <p>Off unless an issuer is configured. An installation with no IdP uses the local account table,
 * which is what devstack does.
 */
@Component
public class OidcVerifier {

  private static final Logger log = LoggerFactory.getLogger(OidcVerifier.class);

  /**
   * Algorithms accepted.
   *
   * <p>An allow-list, because the alternative is trusting the token's own {@code alg} header — and
   * {@code alg: none} and the HMAC-with-the-public-key confusion are both long-standing JWT attacks
   * that an allow-list of asymmetric algorithms ends. Symmetric algorithms are absent deliberately:
   * with HMAC the verifier holds the same secret that signs, so this service could mint a token for
   * any subject it likes, and a compromise here would become a compromise of every service that
   * trusts the same IdP.
   */
  private static final Set<JWSAlgorithm> ALLOWED =
      Set.of(
          JWSAlgorithm.RS256,
          JWSAlgorithm.RS384,
          JWSAlgorithm.RS512,
          JWSAlgorithm.ES256,
          JWSAlgorithm.ES384,
          JWSAlgorithm.ES512);

  /**
   * Clock skew tolerated on exp and nbf. Small: the alternative is a token valid after revoking.
   */
  private static final int SKEW_SECONDS = 30;

  private final String issuer;
  private final String audience;
  private final ConfigurableJWTProcessor<SecurityContext> processor;

  /**
   * Creates the verifier.
   *
   * @param issuer the expected {@code iss}, or blank to disable OIDC entirely
   * @param jwksUri where to fetch the signing keys; defaults to the issuer's standard path
   * @param audience the expected {@code aud}
   */
  public OidcVerifier(
      @Value("${bre.oidc.issuer-uri:}") String issuer,
      @Value("${bre.oidc.jwks-uri:}") String jwksUri,
      @Value("${bre.oidc.audience:}") String audience) {
    this.issuer = issuer == null ? "" : issuer.trim();
    this.audience = audience == null ? "" : audience.trim();
    this.processor = this.issuer.isEmpty() ? null : build(this.issuer, jwksUri, this.audience);
    if (this.issuer.isEmpty()) {
      log.info(
          "no bre.oidc.issuer-uri: sign-in uses the local account table. Set an issuer to accept"
              + " an identity provider's tokens.");
    } else {
      log.info("accepting JWTs from issuer={} audience={}", this.issuer, this.audience);
    }
  }

  private static ConfigurableJWTProcessor<SecurityContext> build(
      String issuer, String jwksUri, String audience) {
    URL keys = keysUrl(issuer, jwksUri);
    // The library's cache and rate limiter, not a hand-written one. Without them a burst of
    // requests with an unknown kid becomes a burst of outbound calls to the IdP, which is a
    // denial-of-service an unauthenticated caller can aim at somebody else's identity provider.
    JWKSource<SecurityContext> source = JWKSourceBuilder.create(keys).retrying(true).build();

    ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    processor.setJWSKeySelector(new JWSVerificationKeySelector<>(ALLOWED, source));

    // iss must EQUAL the configured issuer, and sub and exp must be present. A token with no exp
    // never expires, which is not a session — it is a key somebody emailed.
    DefaultJWTClaimsVerifier<SecurityContext> claims =
        new DefaultJWTClaimsVerifier<SecurityContext>(
            audience.isEmpty() ? null : Set.of(audience),
            new JWTClaimsSet.Builder().issuer(issuer).build(),
            Set.of("sub", "exp"),
            Set.of());
    claims.setMaxClockSkew(SKEW_SECONDS);
    processor.setJWTClaimsSetVerifier(claims);
    return processor;
  }

  private static URL keysUrl(String issuer, String jwksUri) {
    String target =
        jwksUri == null || jwksUri.isBlank()
            // The standard location. Not discovered from /.well-known/openid-configuration,
            // because that is a second network dependency at startup for one string that an
            // operator already knows — and a discovery document fetched over a compromised
            // connection chooses the signing keys.
            ? issuer.replaceAll("/$", "") + "/.well-known/jwks.json"
            : jwksUri.trim();
    try {
      return URI.create(target).toURL();
    } catch (java.net.MalformedURLException bad) {
      throw new IllegalStateException("bre.oidc.jwks-uri is not a URL: " + target, bad);
    }
  }

  /** Whether an identity provider is configured at all. */
  public boolean enabled() {
    return processor != null;
  }

  /**
   * Whether a bearer value even looks like a JWT.
   *
   * <p>Used to decide which resolver to try, so a local session token is never sent through JWT
   * parsing and an IdP token is never hashed and looked up as a session. Three dot-separated
   * segments is the JWS compact serialisation; a local token is opaque base64 with no dots.
   *
   * @param token the bearer value
   * @return true when it has the shape of a compact JWT
   */
  public static boolean looksLikeJwt(String token) {
    if (token == null) {
      return false;
    }
    int first = token.indexOf('.');
    return first > 0 && token.indexOf('.', first + 1) > first + 1;
  }

  /**
   * Verifies a token and returns the subject it authenticates.
   *
   * <p><b>The subject, and nothing else.</b> Mapping it to a tenant and a set of roles is {@code
   * AuthService}'s job, through the same account table a local sign-in uses — see the class comment
   * for why that boundary is where it is.
   *
   * @param token the compact JWT
   * @return the {@code sub} claim, or empty when the token does not verify
   */
  public Optional<String> verify(String token) {
    if (processor == null) {
      return Optional.empty();
    }
    JWTClaimsSet claims;
    try {
      claims = processor.process(token, null);
    } catch (java.text.ParseException
        | com.nimbusds.jose.JOSEException
        | com.nimbusds.jose.proc.BadJOSEException invalid) {
      // At DEBUG, and without the token. A failed verification is an ordinary event on an endpoint
      // anybody can reach, and the token is a credential: at INFO this line would write a valid
      // bearer token into a log file on the one occasion the failure was a clock skew.
      log.debug("rejected a JWT: {}", invalid.getClass().getSimpleName());
      return Optional.empty();
    }
    String subject = claims.getSubject();
    return subject == null || subject.isBlank() ? Optional.empty() : Optional.of(subject);
  }
}
