# ADR-009: REST and gRPC for the Decision API

**Status:** Accepted for Draft 2.
**Source:** carried verbatim from `BRE_PRD/draft2/02_Architecture.md` §14.

- **Context.** Callers range from modern microservices to legacy LOS platforms.
- **Decision.** OpenAPI 3.1 REST/JSON for reach. gRPC/protobuf for high-volume callers. Both use the same handler and the same engine (D12).
- **Consequences.** Two contracts to version, both generated from one domain model, with `buf breaking` and OpenAPI diff gates.
- **Alternatives rejected.** REST only: about 3–5× higher decode cost at volume. gRPC only: excludes many LOS platforms.
