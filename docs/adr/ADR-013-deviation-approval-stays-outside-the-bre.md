# ADR-013: Deviation approval stays outside the BRE

**Status:** Accepted for Draft 2.
**Source:** carried verbatim from `BRE_PRD/draft2/02_Architecture.md` §14.

- **Context.** Draft 1 is ambiguous about who owns deviation approval and expiry (review R7).
- **Decision.** The BRE computes the required approvals from a versioned **Authority Matrix**. The caller's workflow runs the approval. Approvals return as `deviationApprovals[]` facts, bound to an approved value and an expiry (D10).
- **Consequences.** The BRE stays stateless and deterministic. Re-evaluation re-raises a deviation only if the measured value worsens beyond the approved bound. Callers must persist approvals and send them back.
- **Alternatives rejected.** A BRE-owned approval workflow: stateful, which breaks replay purity, and duplicates every LOS's workflow engine.
