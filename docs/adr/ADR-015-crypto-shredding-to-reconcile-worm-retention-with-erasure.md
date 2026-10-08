# ADR-015: Crypto-shredding to reconcile WORM retention with erasure

**Status:** Accepted for Draft 2.
**Source:** carried verbatim from `BRE_PRD/draft2/02_Architecture.md` §14.

- **Context.** DPDP erasure obligations versus immutable archives and backups.
- **Decision.** Identifying fact fields are encrypted under a per-data-principal key (DPK), which is wrapped by a tenant key. Erasure, once permitted after legal retention ends, deletes the DPK.
- **Consequences.**
  - WORM integrity is preserved.
  - Aggregates and non-identifying analytics survive erasure.
  - Requires a stable `principalRef` from callers (Appendix B) and a key table with its own backup-expiry policy.
- **Alternatives rejected.** Rewriting archives (breaks WORM). Tenant-level keys only (cannot erase one person).

---
