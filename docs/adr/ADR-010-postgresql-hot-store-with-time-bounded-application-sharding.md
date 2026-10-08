# ADR-010: PostgreSQL hot store with time-bounded application sharding

**Status:** Accepted for Draft 2.
**Source:** carried verbatim from `BRE_PRD/draft2/02_Architecture.md` §14.

- **Context.** Up to about 9.5 TB of 90-day serving data per design-max cell, with simple access patterns (by ID, by application).
- **Decision.** PostgreSQL 17+, partitioned by day and sub-partitioned by tenant hash. Application-level sharding is used beyond about 2.5 TB per shard. Resharding is time-bounded (new epoch; old data ages out), so there is no migration.
- **Consequences.** A familiar operational model and RLS for tenant isolation. Cross-shard queries are limited to lists by `applicationRef`.
- **Alternatives rejected.**
  - Cassandra/Scylla: no RLS, weaker ad-hoc investigation.
  - Distributed SQL (YugabyteDB): kept as the fallback if online history beyond 90 days becomes a requirement.
  - Elasticsearch: not a system of record.
