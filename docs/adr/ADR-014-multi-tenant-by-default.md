# ADR-014: Multi-tenant by default

**Status:** Accepted for Draft 2.
**Source:** carried verbatim from `BRE_PRD/draft2/02_Architecture.md` §14.

- **Context.** The product must serve SaaS tenants and single-tenant installs from one codebase (D1).
- **Decision.** `tenant_id` is everywhere (rows, keys, events, metrics, logs, encryption context). A single-tenant install is simply `tenantCount = 1`.
- **Consequences.** One artifact set. Isolation tests are part of CI. A slight overhead in key design.
- **Alternatives rejected.** Single-tenant code with retrofitted tenancy: historically the most expensive migration a SaaS can face.
