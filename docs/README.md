# BRE Platform — documentation map

## What this product is

A multi-tenant business-rules and credit-decisioning platform for Indian lenders. The Decision API
is a **pure function of `(facts, policy bundle, asOf)`** — the engine never calls a bureau, KYC,
screening or core-banking system (D2). That determinism is what makes replay, simulation and
back-testing exact. The MVP vertical is MFI group lending, JLG and SHG (D4).

## Which document to read

The specification lives in [`../BRE_PRD/`](../BRE_PRD/).

**The build target is [`../BRE_PRD/BRE_TRD_v1.0.md`](../BRE_PRD/BRE_TRD_v1.0.md)** (v1.1-draft,
standalone, dated 2026-10-06) plus the Draft 2 set below.

**Not the build target:** `../BRE_PRD/BRE_TRD_BFF_FunctionBased_v1.0.md` — the variant that adds
§14 function-based policy authoring — and the four Draft 1 PDFs, which Draft 2 replaces. Both are
kept for reference. Do not implement from them.

| # | Document | Read it for |
|---|---|---|
| 00 | [`00_Decisions_and_Canonical_Terms.md`](../BRE_PRD/draft2/00_Decisions_and_Canonical_Terms.md) | **The tie-breaker.** Decisions D1–D15, the shared vocabulary, fact schema, BEL semantics S1–S13, PSF v1 |
| 01 | [`01_PRD.md`](../BRE_PRD/draft2/01_PRD.md) | What we are building and for whom; requirements, priorities, acceptance criteria, release plan |
| 02 | [`02_Architecture.md`](../BRE_PRD/draft2/02_Architecture.md) | Control plane and data plane, capacity, security, DR, and the ADRs |
| 03 | [`03_LLD.md`](../BRE_PRD/draft2/03_LLD.md) | What to build: repo layout §1.1, dependency rules §1.2, BEL grammar, IR, DDL, APIs, events, **conformance vectors §13** |
| 04 | [`04_Rule_Authoring_Guide.md`](../BRE_PRD/draft2/04_Rule_Authoring_Guide.md) | How a business analyst writes, tests and ships a policy |
| 05 | [`05_Build_Plan.md`](../BRE_PRD/draft2/05_Build_Plan.md) | Milestones M0–M6, critical path, team, Definition of Done, test strategy, risks |

**If two documents disagree, `00` wins.** Raise the conflict and fix the document that is wrong.

HTML editions with rendered diagrams: [`../BRE_PRD/html/index.html`](../BRE_PRD/html/index.html).

## Repository layout

Two independent projects, split per ADR-017 (which departs from LLD §1.1's single root):

| Path | What |
|---|---|
| [`../backend/`](../backend/) | The Gradle root — `libs/` (engine and libraries), `services/`, `tools/bre-cli`, `tests/`. Run `./gradlew` from here. |
| [`../ui/`](../ui/) | The npm root — `studio-web`, the React + TypeScript Rule Studio. |
| [`../BRE_PRD/`](../BRE_PRD/) | The specification. |
| `docs/`, `.github/` | Shared across both. |

There is no build file at the repository root; each project builds from its own directory.

## Architecture Decision Records

[`adr/`](adr/) holds ADR-001 to ADR-015, carried verbatim from Architecture §14, plus:

- [**ADR-016**](adr/ADR-016-trustt-platform-lib-in-services-only.md) — `trustt-platform-lib` is
  permitted in `services/*`, not in `libs/*`, and the reason D11 in `00` carries an amendment note.
- [**ADR-017**](adr/ADR-017-backend-ui-split.md) — the repository is split into `backend/` and
  `ui/` instead of LLD §1.1's single root.

## Where the specification is executable

The parts of the specification that are code rather than prose:

| Specification | Lives in |
|---|---|
| LLD §13 conformance vectors | [`../backend/tests/engine-conformance/vectors/`](../backend/tests/engine-conformance/vectors/) — 90 vectors, language-neutral JSON. [Format contract](../backend/tests/engine-conformance/vectors/README.md). |
| LLD §13 property 2 (fail-closed) | `../backend/tests/engine-conformance/src/test/java/dev/bre/conformance/failclosed/` |
| LLD §1.2 architecture rules | `../backend/libs/bre-engine-core/src/test/java/dev/bre/arch/` |
| LLD §1.2 rule 2 / §9.6 PII log guard | `../backend/tests/engine-conformance/src/test/java/dev/bre/conformance/PiiLogGuardTest.java` |
| D11 / ADR-016 repository boundary | the root `checkLibsAreMavenCentralOnly` task |

**All 90 conformance vectors pass** (2026-10-08). The engine implements the §13 semantics in full:
BEL, three-valued logic, decimals and dates, decision tables with every hit policy, scorecards with
adverse reasons, per-entity iteration, the four aggregation modes, deviation coverage and authority
resolution, knockout, fuel and champion/challenger bucketing.

**Open questions: [`OPEN-QUESTIONS.md`](OPEN-QUESTIONS.md).** Six places where the specification
does not fix something the code needed. **None blocks a vector any more** — Q1 (the Authority Matrix
channel) and Q2 (contradictory `DATA_MISSING_` reason codes) are resolved in the engine and need
only a documentation correction; the rest were settled with a recorded choice. Two §13 rows had to
be deviated from, and both deviations are written down beside the vector that makes them.
Per-family detail is in `NOTES-vectors-{missing,tables,decision}.md` beside the vectors.

## Building

See [`../CONTRIBUTING.md`](../CONTRIBUTING.md).
