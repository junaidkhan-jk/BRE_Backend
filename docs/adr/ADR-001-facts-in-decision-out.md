# ADR-001: Facts in, decision out

**Status:** Accepted for Draft 2.
**Source:** carried verbatim from `BRE_PRD/draft2/02_Architecture.md` §14.

- **Context.** Draft 1 contradicts itself on whether the BRE fetches bureau, KYC and VRM data (review B4). A fetching engine cannot meet a 500 ms p99 if a bureau call takes 3 s. It cannot replay decisions exactly, and it would hold regulated bureau data in caches.
- **Decision.** The Decision API is a pure function of `(facts, bundle, asOf)` (D2). Data acquisition belongs to the caller, or to the Phase 3 Fact Connectors, which run as a separate component.
- **Consequences.**
  - Single-digit-ms evaluation; exact replay, simulation and back-test.
  - A smaller regulatory surface.
  - Callers must assemble complete fact snapshots. Fact-schema quality becomes a product concern, which the PRD and the DATA_MISSING monitor address.
- **Alternatives rejected.** The BRE calls data sources inline: latency, coupling, non-determinism. A hybrid with a cache: stale-data ambiguity and PII in the cache.
