# ADR-017: the repository is split into `backend/` and `ui/`

**Status:** Accepted, 2026-10-08. A deliberate deviation from LLD §1.1.
**Source:** not carried from Draft 2 — this records a decision taken at M0/M2 that the
specification does not contain.

## Context

LLD §1.1 (`BRE_PRD/draft2/03_LLD.md:27-62`) specifies **one** Gradle multi-project build at the
repository root, with the web studio as a sibling directory inside it:

```
bre-platform/
├── settings.gradle.kts
├── build-logic/   libs/   services/   tools/   tests/
└── apps/studio-web/        # React + TypeScript (npm)
```

That layout puts an npm project inside a Gradle root. It builds, but the two toolchains share a
root directory while sharing nothing else: `./gradlew build` at the root says nothing about whether
the studio compiles, the daemon-JVM pin and the version catalogue are meaningless to the front end,
and a front-end developer clones a tree whose top level is a Java build.

The requested structure is a backend/UI split.

## Decision

Two independent projects, one repository:

```
BRE/
├── backend/        # the Gradle root: its own wrapper, settings, version catalogue
│   ├── build-logic/   libs/   services/   tools/   tests/
│   └── gradlew  settings.gradle.kts  gradle.properties
├── ui/             # the npm root
│   └── studio-web/
├── BRE_PRD/        # the specification
├── docs/  .github/  CODEOWNERS  CONTRIBUTING.md
```

**There is no build file at the repository root.** Each project builds from its own directory, and
neither can silently depend on the other's toolchain. The module names inside `backend/` are
unchanged from LLD §1.1, so every `libs/…` and `services/…` path in the specification still
resolves — only the prefix moved.

The move was made with `git mv`, so file history is preserved rather than appearing as a delete and
an add.

## Consequences

- `./gradlew` runs from `backend/`, not the repository root. `CONTRIBUTING.md` says so, and the CI
  workflow sets `working-directory: backend` and `build-root-directory: backend`.
- The two projects can be built, versioned and reviewed independently. `CODEOWNERS` gains a `/ui/`
  entry.
- Gradle's `rootProject` is now `backend/`, which matters for one thing: the PII log guard scans
  `bre.repo.root` for `*/src/main/java/**`. That resolves to `backend/`, which is where all Java
  lives — so the scan's coverage is unchanged. If Java ever appears outside `backend/`, the scan
  must be repointed, and its own `scanCoversTheRepository` test is what will notice.
- **LLD §1.1 is now wrong about the repository root.** The layout diagram needs a note, and `00`
  does not need amending because it fixes no directory layout. Unlike ADR-016 this deviation is
  cosmetic: it changes no dependency rule, no module boundary and no enforced property. The §1.2
  dependency matrix, the engine's purity ceiling and the `libs/*` Maven-Central boundary all still
  hold exactly as written.
- A future `tools/devstack` (Build Plan §4) that starts the services and the studio with one
  command now spans two roots, so it lives at the repository root rather than inside either.

## Alternatives rejected

- **Keep LLD §1.1 verbatim** — one Gradle root with `apps/studio-web` inside it. Fewest documents
  to correct, but it is the layout the request was asking to change.
- **One Gradle root at the repository top, with modules under `backend/`** (`include(":backend:libs:bre-engine-core")`).
  Keeps a single `./gradlew` at the top, but it makes `ui/` subordinate to a Java build it has
  nothing to do with — the half-measure that gets the directory names without the independence.
- **Two separate repositories.** Genuinely independent, but it splits the specification from the
  code that implements it and doubles the review surface for a change that spans both. Worth
  revisiting only if the studio gets its own release cadence.
