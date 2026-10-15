package dev.bre.registry.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The control-plane OpenAPI document (M6-05).
 *
 * <p><b>Generated from the Spring mappings, not hand-written.</b> Forty-three endpoints
 * hand-maintained alongside an API under active development is a document that is wrong and does
 * not say so — and an integrator reading it believes it. Generating it means the only thing that
 * can drift is the prose below.
 *
 * <p>The {@code decision-api.openapi.yaml} contract in {@code libs/bre-contracts} is hand-written
 * on purpose and stays that way: it is the <em>published</em> contract for the two endpoints an
 * origination system calls, and a published contract should be the input to the implementation
 * rather than a report of it. That is the opposite trade from this document, which describes an
 * internal authoring API whose shape is still moving.
 *
 * <p>The description is not decoration. It carries the two things every integrator gets wrong about
 * this API, and the second is the one that produces a support ticket on day one.
 */
@Configuration
public class OpenApiConfiguration {

  /**
   * The document's metadata and its one security scheme.
   *
   * @return the OpenAPI definition springdoc merges the generated paths into
   */
  @Bean
  public OpenAPI breControlPlaneApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("BRE Control Plane API")
                .version("0.1.0")
                .description(
                    """
                    Authoring, reviewing, approving and deploying credit policy. This is the \
                    control plane — it is NOT the Decision API.

                    **Two things to know before you integrate.**

                    **1. This is not where decisions are made.** Decisions come from the \
                    decision-service (`POST /v1/decisions`), whose contract is published \
                    separately as `decision-api.openapi.yaml`. Everything here is about the \
                    policy a decision is made *with*. `POST /v1/policies/{code}/versions/\
                    {version}/run` looks like a decision endpoint and is not: it evaluates a \
                    DRAFT against facts you supply, stores nothing, and its result is not a \
                    decision anybody can be held to.

                    **2. Authentication establishes who; authorisation comes from this \
                    installation.** Present either a session token from `POST /v1/auth/login` \
                    or your identity provider's JWT. In both cases the tenant and the roles come \
                    from this installation's own tables, never from a header or a token claim — \
                    so a valid IdP token for a subject nobody here has provisioned is a 401, not \
                    a 200 with no permissions. Every endpoint needs a specific capability, and a \
                    403 names the one you are missing and which roles grant it.

                    **Segregation of duties is enforced, not advisory.** A version's editor \
                    cannot review it; a deployment's approver can be neither the author, nor the \
                    reviewer, nor the requester. Those are database constraints and triggers, so \
                    a client that works around the UI still cannot work around them. Plan your \
                    integration test users accordingly — the commonest first failure is one \
                    service account trying to do all three.
                    """))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearer",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .description(
                            "A session token from POST /v1/auth/login, or your identity"
                                + " provider's access token. Both go in the same header.")))
        // Applied globally rather than per operation, because every endpoint except the login and
        // the health check requires it — and a document that marks security per operation invites
        // the one omission that reads as "this endpoint is open".
        .addSecurityItem(new SecurityRequirement().addList("bearer"));
  }
}
