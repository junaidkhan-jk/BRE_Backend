# Contributing to the BRE Platform

The specification is in `BRE_PRD/`. **`BRE_PRD/draft2/00_Decisions_and_Canonical_Terms.md` is
the tie-breaker:** if two documents disagree, `00` wins — raise the conflict and fix the document
that is wrong. The build target is `BRE_PRD/BRE_TRD_v1.0.md` plus the Draft 2 set; the
`BRE_TRD_BFF_FunctionBased_v1.0.md` variant is **not** in scope.

## Definition of Done (every task)

From Build Plan §8. All of it, every task.

1. The code follows the Draft 2 specification. **Any deviation is recorded in an ADR, and `00` is
   updated.** (See `docs/adr/ADR-016` for the one deviation taken so far.)
2. There are unit tests. Engine work has conformance vectors and property tests where the
   behaviour is algebraic.
3. CI is green: ArchUnit, formatting, Error Prone, coverage gate, JMH thresholds, SBOM and licence
   scan.
4. **The work has been run end to end in `devstack`** (from M2 onwards), and the PR description
   says how.
5. There are no secrets, PII or fact values in logs or test fixtures — synthetic fixtures only
   (LLD §9.7).
6. API, event or schema changes are reflected in `bre-contracts`, with breaking-change checks
   passing.
7. User-visible behaviour has an entry in the Authoring Guide or the integration guide.

## Build principles that the build itself enforces

- **The specification is executable** (Build Plan P1). The LLD §13 conformance vectors and the
  `00` §3.10 semantics S1–S13 are a JUnit suite *before* the engine is written. The engine is done
  when that suite is green, not when it looks finished.
- **A rule with no failing fixture proves nothing.** Every ArchUnit rule in
  `libs/bre-engine-core/src/test/java/dev/bre/arch/` is asserted twice: it must pass over
  production code *and* fire over a deliberate violation in `dev.bre.engine.archfixture`. Add a
  rule, add its fixture.
- **No test may pass by checking nothing.** The conformance suite pins its vector families, the
  PII scan asserts it reached the source tree, and the fail-closed property is run against a
  deliberately fail-open stub to prove the harness catches one. Keep that property when you add
  checks.

## Running it

```bash
./gradlew build                          # everything: compile, format, ArchUnit, tests
./gradlew spotlessApply                  # fix formatting
./gradlew checkLibsAreMavenCentralOnly   # the D11 / ADR-016 repository boundary
./gradlew :tests:engine-conformance:test # the conformance vectors and property tests
```

### Two JVMs, on purpose

`gradle/gradle-daemon-jvm.properties` pins the **daemon** to Java 21; the convention plugin
compiles every module to the **Java 25** toolchain (ADR-012). google-java-format drives javac's
internal parser and no released version runs on JDK 25 yet, so the formatter needs the older
daemon. Raise both together once it supports 25. Gradle provisions both JDKs itself through the
foojay resolver; no system install is needed.

### Nexus credentials

Only `services/*` need them, and only from M2. Put `trusttNexusUsername` / `trusttNexusPassword`
in `~/.gradle/gradle.properties`, or export `TRUSTT_NEXUS_USERNAME` / `TRUSTT_NEXUS_PASSWORD`.
**Never in a build file** — `trustt-platform-bre/build.gradle` hardcodes a username and password
and that pattern must not be copied here.

## Test strategy by layer

From Build Plan §9.

| Layer | Tool | Runs | Gate |
|---|---|---|---|
| Unit | JUnit 5, AssertJ | Every PR | Coverage ≥ 85% on `libs/` |
| Engine conformance | JSON vectors + JUnit runner | Every PR | 100% pass |
| Property-based | jqwik (fail-closed, decimal algebra, aggregation monotonicity) | Every PR | Pass |
| Fuzzing | Jazzer (BEL parser, type checker, PSF decoder) | Nightly 1 h; 24 h before release | No crash, hang or exception escape |
| Integration | embedded-postgres, embedded Kafka KRaft; Testcontainers in CI | PR (embedded), nightly (Testcontainers) | Pass |
| Contract | OpenAPI and JSON Schema validation, buf breaking check, AsyncAPI validation | Every PR | Pass |
| End-to-end journeys | API scripts for J1–J8; Playwright for the UI | Nightly | Pass |
| Performance | JMH (engine), Gatling (API) | PR smoke; weekly on the PERF cell | NFR thresholds |
| Chaos | Chaos Mesh on the PERF cell | Each milestone from M4 | RPO/RTO met |
| Security | Dependency scan, ZAP baseline, cross-tenant RLS suite, expression-safety suite | PR + nightly | No high or critical findings |

## Commits and branches

Conventional commits. `main` is protected. One logical change per PR, and the PR description
answers the Definition of Done checklist in `.github/pull_request_template.md`.
