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

## Architecture Decision Records

[`adr/`](adr/) holds ADR-001 to ADR-015, carried verbatim from Architecture §14, plus:

- [**ADR-016**](adr/ADR-016-trustt-platform-lib-in-services-only.md) — `trustt-platform-lib` is
  permitted in `services/*`, not in `libs/*`. The one deliberate deviation from the specification
  so far, and the reason D11 in `00` carries an amendment note.

## Where the specification is executable

The parts of the specification that are code rather than prose:

| Specification | Lives in |
|---|---|
| LLD §13 conformance vectors | [`../tests/engine-conformance/vectors/`](../tests/engine-conformance/vectors/) — 90 vectors, language-neutral JSON. [Format contract](../tests/engine-conformance/vectors/README.md). |
| LLD §13 property 2 (fail-closed) | `../tests/engine-conformance/src/test/java/dev/bre/conformance/failclosed/` |
| LLD §1.2 architecture rules | `../libs/bre-engine-core/src/test/java/dev/bre/arch/` |
| LLD §1.2 rule 2 / §9.6 PII log guard | `../tests/engine-conformance/src/test/java/dev/bre/conformance/PiiLogGuardTest.java` |
| D11 / ADR-016 repository boundary | the root `checkLibsAreMavenCentralOnly` task |

Open questions raised while transcribing the vectors — places where the LLD does not fix something
a vector needed — are recorded in `NOTES-vectors-*.md` beside them. They are worth reading before
M1: several need a decision in the LLD itself.

## Building

See [`../CONTRIBUTING.md`](../CONTRIBUTING.md).
