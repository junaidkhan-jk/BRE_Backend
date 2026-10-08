# ADR-012: Java 25 and Spring Boot 4 with virtual threads and a CPU bulkhead

**Status:** Accepted for Draft 2.
**Source:** carried verbatim from `BRE_PRD/draft2/02_Architecture.md` §14.

- **Context.** Draft 1 targets Java 17 and Spring Boot 3.x (review H8). The hot path mixes I/O waits (the Kafka ack) with CPU-bound evaluation.
- **Decision.** Java 25 LTS and Spring Boot 4.x. Virtual threads for request handling. An explicit CPU bulkhead around evaluation. Generational ZGC; AOT cache.
- **Consequences.** Simple blocking code at high concurrency; flat p99. The team must follow virtual-thread hygiene: no `ThreadLocal` caches; use `ScopedValue`.
- **Alternatives rejected.** Reactive (WebFlux): complexity with no throughput gain once virtual threads are available. Java 17: misses `ScopedValue`, generational ZGC by default, compact headers and the AOT cache.
