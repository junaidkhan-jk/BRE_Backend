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
- **An unresolved overlap, which M2 must settle before any service applies `bre.spring-service`.**
  LLD §1.1 gives `bre-platform-common` the job of "tenancy context, UUIDv7, problem+json, OTel
  helpers, PII log guard" — every one of which `trustt-platform-lib` also provides. Two tenancy
  mechanisms in one service is a defect waiting to happen. M2 picks one owner per concern and
  writes it down. `bre-platform-common` is deliberately left with no dependencies until then, so
  the choice is not pre-empted.
- Builds of `services/*` need Nexus credentials, so they are not reproducible by an outside
  contributor. Credentials come from `~/.gradle/gradle.properties` or the environment and are
  never committed — the pattern in `trustt-platform-bre/build.gradle`, which hardcodes a username
  and password in the build file, must not be copied.
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
