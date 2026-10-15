package dev.bre.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import dev.bre.bundle.BundleSigner;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Signing in with an identity provider's token (M3-09).
 *
 * <p>Against a real JWKS endpoint and real RSA signatures rather than a stubbed verifier, because
 * the thing under test is whether a token somebody else minted is accepted — and a stub would
 * assert my own assumptions about what nimbus does with a wrong issuer or an expired token.
 *
 * <p><b>The assertions that matter are the refusals.</b> Accepting a good token is one line of
 * happy path; the value of this file is that a token signed by the wrong key, issued by the wrong
 * issuer, minted for another service, already expired, or belonging to a subject nobody here has
 * provisioned, is each refused — and that the roles come from this installation's tables rather
 * than from the token, which is the one design decision in M3-09 that a wrong implementation would
 * make invisibly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OidcSignInTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String TENANT = "t_oidc";
  private static final String ADMIN_EMAIL = "admin@oidc.test";
  private static final String ADMIN_PASSWORD = "bootstrap-password-1";

  /** What the bootstrap stores as the subject: the local part of the email. */
  private static final String ADMIN_SUBJECT = "admin";

  private static final String AUDIENCE = "bre-policy-registry";

  private static EmbeddedPostgres postgres;
  private static Path registryDir;
  private static HttpServer idp;
  private static String issuer;
  private static RSAKey signingKey;
  private static RSAKey otherKey;

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry registry) throws Exception {
    postgres = EmbeddedPostgres.builder().start();
    registryDir = Files.createTempDirectory("bre-oidc-test");

    org.flywaydb.core.Flyway.configure()
        .dataSource(postgres.getPostgresDatabase())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    try (var connection = postgres.getPostgresDatabase().getConnection();
        var statement = connection.createStatement()) {
      statement.execute("ALTER ROLE bre_ctl_app LOGIN PASSWORD 'test-only'");
    }

    signingKey = new RSAKeyGenerator(2048).keyID("k1").keyUse(KeyUse.SIGNATURE).generate();
    // A second key the IdP never publishes, to sign the forged token with.
    otherKey = new RSAKeyGenerator(2048).keyID("k1").keyUse(KeyUse.SIGNATURE).generate();

    idp = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    idp.createContext(
        "/.well-known/jwks.json",
        exchange -> {
          byte[] body =
              new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    idp.start();
    issuer = "http://127.0.0.1:" + idp.getAddress().getPort();

    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
    registry.add("spring.datasource.username", () -> "bre_ctl_app");
    registry.add("spring.datasource.password", () -> "test-only");
    registry.add("spring.flyway.enabled", () -> "false");
    registry.add("bre.registry.bundle-signing-key", () -> privateKey());
    registry.add("bre.registry.manifest-signing-key", () -> privateKey());
    registry.add("bre.registry.bundle-dir", () -> registryDir.resolve("bundles").toString());
    registry.add("bre.registry.manifest-dir", () -> registryDir.resolve("manifests").toString());
    registry.add("bre.registry.bootstrap-tenant", () -> TENANT);
    registry.add("bre.registry.bootstrap-admin-email", () -> ADMIN_EMAIL);
    registry.add("bre.registry.bootstrap-admin-password", () -> ADMIN_PASSWORD);
    registry.add("bre.oidc.issuer-uri", () -> issuer);
    registry.add("bre.oidc.audience", () -> AUDIENCE);
  }

  private static String privateKey() {
    return Base64.getEncoder()
        .encodeToString(BundleSigner.generateKeyPair().getPrivate().getEncoded());
  }

  @AfterAll
  void stop() throws Exception {
    if (idp != null) {
      idp.stop(0);
    }
    if (postgres != null) {
      postgres.close();
    }
    if (registryDir != null) {
      try (var paths = Files.walk(registryDir)) {
        paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
      }
    }
  }

  @LocalServerPort private int port;

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @Test
  @DisplayName("a provisioned subject signs in with the provider's token, and gets ITS OWN roles")
  void aProvisionedSubjectIsAccepted() throws Exception {
    // The IdP's `sub` has to match the `subject` column, which is what role_assignments is keyed
    // on. That is the ordinary migration path: the local rows stay exactly as they are, the
    // password simply stops being used — and if an IdP issues opaque subjects instead of
    // usernames, the one-time job is updating that column, not re-granting every role.
    String token = jwt(signingKey, claims().subject(ADMIN_SUBJECT).build());

    HttpResponse<String> session = get("/v1/auth/me", token);
    assertThat(session.statusCode()).as("%s", session.body()).isEqualTo(200);

    JsonNode who = JSON.readTree(session.body());
    assertThat(who.get("subject").asText()).isEqualTo(ADMIN_SUBJECT);
    assertThat(who.get("tenantId").asText())
        .as("the tenant comes from the user row, never from the token")
        .isEqualTo(TENANT);
    assertThat(who.get("roles"))
        .as("and so do the roles: the IdP authenticates, this installation authorises")
        .isNotEmpty();

    // And the token actually authorises real work, so this is not just an identity echo.
    assertThat(get("/v1/policies", token).statusCode()).isEqualTo(200);
  }

  @Test
  @DisplayName("a token the provider never signed is refused, even with the right kid")
  void aForgedSignatureIsRefused() throws Exception {
    // Same kid, same issuer, same audience, same subject — a different private key. This is the
    // attack the JWKS fetch exists to stop, and the kid matching is what makes it worth asserting:
    // a verifier that selected a key by kid and forgot to verify would accept this.
    String forged = jwt(otherKey, claims().subject(ADMIN_SUBJECT).build());
    assertThat(get("/v1/policies", forged).statusCode()).isEqualTo(401);
  }

  @Test
  @DisplayName("a token from another issuer, or for another audience, is refused")
  void theWrongIssuerOrAudienceIsRefused() throws Exception {
    String wrongIssuer =
        jwt(
            signingKey,
            new JWTClaimsSet.Builder()
                .issuer("https://some-other-idp.example")
                .audience(AUDIENCE)
                .subject(ADMIN_SUBJECT)
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build());
    assertThat(get("/v1/policies", wrongIssuer).statusCode()).isEqualTo(401);

    // Minted by the SAME provider with the SAME key, for a different service. Accepting it is the
    // mistake an empty audience configuration makes, which is why the config comments say to set
    // one: a lender's identity provider issues tokens for a dozen applications, and a token for
    // the staff directory must not author credit policy.
    String wrongAudience =
        jwt(
            signingKey,
            new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience("some-other-service")
                .subject(ADMIN_SUBJECT)
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build());
    assertThat(get("/v1/policies", wrongAudience).statusCode()).isEqualTo(401);
  }

  @Test
  @DisplayName("an expired token is refused, and a token with no expiry is refused outright")
  void expiryIsRequiredAndEnforced() throws Exception {
    String expired =
        jwt(
            signingKey,
            new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(AUDIENCE)
                .subject(ADMIN_SUBJECT)
                .expirationTime(Date.from(Instant.now().minusSeconds(600)))
                .build());
    assertThat(get("/v1/policies", expired).statusCode()).isEqualTo(401);

    // No exp at all. Not a session — a key somebody emailed. Refused because exp is a REQUIRED
    // claim rather than one that is only checked when present.
    String noExpiry =
        jwt(
            signingKey,
            new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(AUDIENCE)
                .subject(ADMIN_SUBJECT)
                .build());
    assertThat(get("/v1/policies", noExpiry).statusCode()).isEqualTo(401);
  }

  @Test
  @DisplayName(
      "a valid token for an unprovisioned subject is refused: authenticated, not authorised")
  void anUnprovisionedSubjectIsRefused() throws Exception {
    // The decision this milestone turns on. A perfectly valid token, correctly signed, right
    // issuer and audience — for somebody this lender has never granted a role to. Accepting it
    // with no roles would be worse than refusing: a caller who gets 200 on sign-in and 403 on
    // everything reads it as a broken deployment, and an installation that auto-provisions from a
    // claim has moved "who may approve credit policy" to whoever administers the directory.
    String stranger = jwt(signingKey, claims().subject("not-provisioned@elsewhere.test").build());
    assertThat(get("/v1/policies", stranger).statusCode()).isEqualTo(401);
  }

  @Test
  @DisplayName("a local password sign-in still works alongside the provider")
  void localSignInStillWorks() throws Exception {
    // Both at once, on purpose. A migration to an IdP is not instant: a break-glass local
    // administrator is what gets a lender back in when the IdP itself is the outage.
    String local =
        JSON.readTree(
                post(
                        "/v1/auth/login",
                        "{\"email\":\""
                            + ADMIN_EMAIL
                            + "\",\"password\":\""
                            + ADMIN_PASSWORD
                            + "\"}")
                    .body())
            .get("token")
            .asText();
    assertThat(get("/v1/policies", local).statusCode()).isEqualTo(200);

    // An opaque session token is not fed through JWT parsing, and a JWT is not hashed and looked
    // up as a session. The filter picks the path by the token's shape.
    assertThat(local).doesNotContain(".");
  }

  // --- plumbing --------------------------------------------------------------------------------

  private static JWTClaimsSet.Builder claims() {
    return new JWTClaimsSet.Builder()
        .issuer(issuer)
        .audience(AUDIENCE)
        .issueTime(Date.from(Instant.now()))
        .expirationTime(Date.from(Instant.now().plusSeconds(300)));
  }

  private static String jwt(RSAKey key, JWTClaimsSet claims) throws Exception {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(key.getKeyID())
                .type(JOSEObjectType.JWT)
                .build(),
            claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  private HttpResponse<String> get(String path, String bearer) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Bearer " + bearer)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
