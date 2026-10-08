## What and why

<!-- What changed, and which specification section or milestone task it implements. -->

Implements: <!-- e.g. M1-01, LLD §3.6 -->

## Definition of Done (Build Plan §8)

- [ ] Follows the Draft 2 specification. **Any deviation is recorded in an ADR and `00` is updated.**
- [ ] Unit tests present. Engine work has conformance vectors and property tests where the behaviour is algebraic.
- [ ] CI green: ArchUnit, formatting, Error Prone, coverage gate, JMH thresholds, SBOM, licence scan.
- [ ] Run end to end in `devstack` (M2 onwards) — say how below.
- [ ] No secrets, PII or fact values in logs or fixtures. Synthetic fixtures only (LLD §9.7).
- [ ] API / event / schema changes reflected in `bre-contracts`, breaking-change checks pass.
- [ ] User-visible behaviour documented in the Authoring Guide or integration guide.

## If this adds an architecture rule

- [ ] It has a deliberately failing fixture proving it fires. A rule with no fixture proves nothing.

## Conformance vectors

<!-- Which LLD §13 vectors move from pending to passing, by id. -->

## How this was run end to end

<!-- devstack commands, or N/A before M2. -->
