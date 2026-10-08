# ADR-007: Cell-based data plane

**Status:** Accepted for Draft 2.
**Source:** carried verbatim from `BRE_PRD/draft2/02_Architecture.md` §14.

- **Context.** A multi-tenant SaaS needs bounded blast radius, noisy-neighbour isolation and linear scale.
- **Decision.** Tenants are mapped to cells; each cell has its own pods, Kafka and hot store. Dedicated cells serve large tenants (D14).
- **Consequences.** Linear scale-out and fault isolation. The control plane, routing table and tenant moves need tooling (§6.5).
- **Alternatives rejected.** One global cluster: blast radius and noisy neighbours. A cluster per tenant: cost and operational load for small tenants.
