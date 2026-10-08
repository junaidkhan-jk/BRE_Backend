# ADR-005: In-process compiled snapshot instead of a Redis rule cache

**Status:** Accepted for Draft 2.
**Source:** carried verbatim from `BRE_PRD/draft2/02_Architecture.md` §14.

- **Context.** Draft 1 reads rules from Redis on every request, with a 30-minute TTL and Kafka-driven invalidation (H3).
- **Decision.** Compiled bundles live in each pod's memory (an LRU by hash). The pointer table is swapped atomically. Redis/Valkey is not on the policy path at all.
- **Consequences.**
  - Lookup in about 10 µs with no network.
  - Convergence is governed by the manifest long-poll (≤ 30 s at p99, typically about 2 s).
  - Memory per pod must be budgeted (§6.3), with pod groups as the overflow strategy.
- **Alternatives rejected.** A Redis cache: extra hop, serialisation cost, invalidation races. Database reads with a short TTL: load and staleness.
