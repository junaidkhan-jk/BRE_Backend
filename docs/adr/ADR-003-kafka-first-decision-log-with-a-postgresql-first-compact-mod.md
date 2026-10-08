# ADR-003: Kafka-first decision log, with a PostgreSQL-first compact mode

**Status:** Accepted for Draft 2.
**Source:** carried verbatim from `BRE_PRD/draft2/02_Architecture.md` §14.

- **Context.** Draft 1 demands RPO 0 but writes the audit log asynchronously (review B6). One row per rule at 1,000 req/s × 100 rules is 8.6 billion rows a day.
- **Decision.** One record per decision, produced to `bre.decision.recorded.v1` with `acks=all` **before** responding. Sinks fan out to the hot store, archive and analytics. Compact mode uses a PostgreSQL transaction plus an outbox. Both sit behind the `DecisionLog` port (D6).
- **Consequences.**
  - The write path scales horizontally by partitions.
  - The response latency includes a cross-AZ replication round trip (§6.2, about 4 ms at p50).
  - Kafka becomes Phase 1 infrastructure (00 §3.7a).
  - Read-after-write through `GET` is eventually consistent (≤ 5 s), mitigated by the 15-minute response cache.
- **Alternatives rejected.**
  - Synchronous PostgreSQL insert at 10,000/s with large blobs: one primary becomes the bottleneck, with write amplification.
  - Asynchronous logging: violates RPO 0.
  - Writing directly to the object store: no low-latency durable append.
