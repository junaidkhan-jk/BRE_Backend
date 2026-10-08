# ADR-016: trustt-platform-lib is permitted in `services/*`, not in `libs/*`

**Status:** Accepted, 2026-10-08. A deliberate deviation from D11.
**Source:** not carried from Draft 2 — this ADR records a decision taken at M0 that the
specification does not contain.

## Context

D11 (`BRE_PRD/draft2/00_Decisions_and_Canonical_Terms.md`) fixes the stack and ends with
**"Maven Central and npm only."** D3 fixes `bre-engine-core` as "a pure Java library with no
Spring dependency", and LLD §1.2 gives it the JDK and `re2j` and nothing else.

The BRE Platform is nonetheless being built inside an organisation that already runs the Trustt
lending platform: 23 microservices sharing `trustt-platform-lib`, resolved from a private Nexus
through an `in.novopay:*.dependency` Gradle plugin, already on Spring Boot 4.1.1. The platform
library provides the service-edge concerns the BRE's own services will otherwise re-implement —
authentication filters, tenancy propagation, audit columns, Kafka and Redis helpers, the SOF
request envelope.

Building those twice is waste. Pulling them into the engine is a correctness and licensing
problem, and ends the "one code path for live decisions, simulation, back-testing and studio
tests" property that D3 buys.

## Decision

Split the boundary at the `libs/` / `services/` line, which LLD §1.2 already draws:

- **`libs/*` stay pure.** Maven Central only. No Spring, no Nexus, no `trustt-platform-lib`.
  `bre-engine-core` keeps the JDK + `re2j` ceiling. This is enforced, not merely documented —
  the root `checkLibsAreMavenCentralOnly` task fails the build if any `libs/*` project declares
  a non-Maven-Central repository, and the ArchUnit dependency rule fails if engine code reaches
  outside its allowed packages.
- **`services/*` may additionally use `trustt-platform-lib`.** The Nexus repository is declared
  in exactly one place, the `bre.spring-service` convention plugin. LLD §1.2's `services/*` row
  already permits "any lib, Spring Boot 4.x"; this adds one more allowed origin for that row.

D11 in `00_Decisions_and_Canonical_Terms.md` is amended to say so, because that document is the
declared tie-breaker and code that silently contradicts it is worse than either choice.

## Consequences

- The product stays sellable and deployable standalone: nothing a customer runs in the data plane
  depends on Trustt-internal artifacts, because the engine and every library are clean.
- Integration with the Trustt LOS gets cheaper, since the BRE's own services speak the same
  envelope, auth and tenancy conventions as the 23 services around them.
- **The `bre-platform-common` overlap is now measured, and it is small.** LLD §1.1 gives that
  module "tenancy context, UUIDv7, problem+json, OTel helpers, PII log guard". Checked against the
  library rather than assumed, only the first genuinely collides:

  | Concern | In `trustt-platform-lib`? | Owner |
  |---|---|---|
  | Tenancy context | **Yes** — `ThreadLocalContext.setTenant/getTenantCode()` (`infra-platform/.../core/ThreadLocalContext.java:20-35`), populated from `tenant_code` in `RequestProcessorImpl.java:76-78` | **The only real contest.** M2 decides. |
  | UUIDv7 / correlation id | No. The nearest is a STAN transaction id from `SequenceManager` | `bre-platform-common` |
  | problem+json (RFC 9457) | No. A custom `{"response_status":{status,message,code}}` envelope (`JSONHelperForRequestResponse.java:125-144`) | `bre-platform-common` — and LLD §6.6's problem+json contract is incompatible with that envelope |
  | OpenTelemetry helpers | No. Elastic APM agent wiring only (`infra-platform/.../elasticapm/*`) | `bre-platform-common` |
  | PII-safe logging guard | No. Only opt-in `MaskStringUtil.maskAadhaar/maskMobile`, which a caller must remember to invoke | `bre-platform-common` — the engine's guard is automatic and enforced in CI |

  So `bre-platform-common` keeps four of its five jobs outright, and M2's decision narrows to
  tenancy alone. It is still left with no dependencies until that is settled.

- **The two service architectures do not match, and that is the real M2 question.** Trustt services
  have **no controller layer at all**: an API is declared in
  `deploy/application/orchestration/ServiceOrchestrationXML.xml` and dispatched at runtime by
  `infra-navigation`, with Java supplying only `@Processor` / `@Validator` beans against a
  JSON-template request envelope. The BRE's Decision API is OpenAPI 3.1 plus gRPC/protobuf with a
  frozen contract (D12, LLD §6.2–6.3). Those are different front doors, not two spellings of one.
  What is genuinely reusable here is the **infrastructure** — `infra-cache` (Redis),
  `infra-message-broker` (Kafka), `infra-batch`, `infra-service-security`, `infra-http-client`,
  `util-platform` — and not `infra-navigation`'s orchestration model. Adopting the XML dispatch
  layer would mean giving up the frozen API contract.

- **Pull only the domain-agnostic modules.** The library is 33 modules and much of it is payments
  and agent banking: `infra-transaction-hdfc` alone is 1,697 files, plus
  `infra-transaction-{paytm,indusind,ccavenue,veri5}`, `infra-matm-payswiff`,
  `infra-transaction-interface` (matm / remittance / aeps / billpay / neft) and
  `adapter-aadhaar-xsd` (UIDAI XSD models). None of that belongs in a decisioning engine. The
  `infra-{actor,task,approval,accounting,authorization,notifications,masterdata,reporting}`
  modules are client helpers for Trustt's own services, useful only if a BRE service actually calls
  them. `infra-rule-engine` is a Drools wrapper bound to the old BRE service's rule/action/fact
  master cache entities — not a starting point for `bre-engine-core`, and D3 already rejected
  Drools on the merits.
- Builds of `services/*` need Nexus credentials, so they are not reproducible by an outside
  contributor. Credentials come from `~/.gradle/gradle.properties` or the environment and are
  never committed — the pattern in `trustt-platform-bre/build.gradle`, which hardcodes a username
  and password in the build file, must not be copied. **The same literal credential pair appears
  in the build file of every Trustt service and every `trustt-platform-lib` module**, so it is a
  live secret committed across dozens of files; worth raising with whoever owns the Nexus
  instance, separately from this product.

- **Two build-level traps that M2 hits the moment it applies `bre.spring-service`.** Both verified
  against the Trustt repos. (a) There is no generic dependency plugin — every service applies its
  own published `in.novopay:<service>.dependency.gradle.plugin`, and the `bre.dependency` id that
  exists belongs to the old `trustt-platform-bre` service, not to this product. (b) Those plugins
  set `sourceCompatibility` / `targetCompatibility` to **17** imperatively
  (`InfraPlatformDependencyPlugin.java:63-64`) and disable `bootJar`, so applying one after
  `bre.java-library` would silently drop this product from Java 25 to Java 17 with no error.
  Preferred resolution: declare the `in.novopay:infra-*` artifacts directly in
  `gradle/libs.versions.toml` and skip the convention plugin, keeping version control in this
  repository instead of inside an opaque Nexus-hosted jar. Both traps are recorded in
  `bre.spring-service.gradle.kts`, where someone is actually going to read them.

- A smaller snag for whoever scaffolds a service: every Trustt service's `settings.gradle` says
  `includeBuild '../novopay-platform-lib'` while the directory on disk is `trustt-platform-lib`.
  The stale name is consistent across all of them, so it is a rename artifact, not a typo.
- A service that is intended to ship to customers who do not have Nexus access must not take a
  `trustt-platform-lib` dependency. That decision is per service, at M2.

## Alternatives rejected

- **Follow D11 exactly; no reuse.** Clean, and what the specification says, but it re-implements
  five solved service-edge concerns and leaves the LOS integration to a later adapter nobody has
  scheduled.
- **Adapter-only reuse** — product entirely pure, with a thin Trustt-facing adapter translating the
  SOF envelope to the Decision API. Keeps D11 intact and is the most portable, at the cost of one
  more deployable and a translation layer on the hot path.
- **Treat the Trustt platform as the foundation and rewrite the stack decisions around it.**
  Rejected: it would put Spring on the engine's classpath and end D3's purity guarantee, which is
  what makes replay, simulation and back-test exact.
