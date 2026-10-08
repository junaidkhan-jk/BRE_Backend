# ADR-008: UUIDv7 decision IDs with locator bits

**Status:** Accepted for Draft 2.
**Source:** carried verbatim from `BRE_PRD/draft2/02_Architecture.md` §14.

- **Context.** IDs must be index-friendly (time-ordered), globally unique without coordination, and routable to the right cell and shard.
- **Decision.** UUIDv7 (RFC 9562). 16 bits of `rand_b` carry `cellId` and `shardId` (§6.5.4).
- **Consequences.** B-tree locality; direct routing for `GET`. The locator bits are internal and not a contract for callers.
- **Alternatives rejected.** UUIDv4: index fragmentation. Snowflake-style IDs: need worker-id coordination. Database sequences: a single point of contention.
