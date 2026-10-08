# ADR-011: Parquet archive on WORM object storage

**Status:** Accepted for Draft 2.
**Source:** carried verbatim from `BRE_PRD/draft2/02_Architecture.md` §14.

- **Context.** 10-year retention, regulator-grade immutability, and back-tests over millions of decisions.
- **Decision.** Hourly Parquet files per tenant with manifests, on object storage with Object Lock (compliance mode), crypto-shredding for erasure (§9.3), and cross-region replication.
- **Consequences.** Cheap storage at petabyte scale; partition-pruned back-tests; erasure without rewriting WORM objects.
- **Alternatives rejected.** Keeping everything in PostgreSQL: cost, and vacuum at scale. Kafka infinite retention: not query-friendly, and no WORM guarantee.
