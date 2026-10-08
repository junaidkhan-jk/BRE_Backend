# ADR-004: Signed, content-addressed bundles and a signed active-pointer manifest

**Status:** Accepted for Draft 2.
**Source:** carried verbatim from `BRE_PRD/draft2/02_Architecture.md` §14.

- **Context.** Rule-by-rule activation leaves policies inconsistent mid-change, and decisions are not reproducible (review B7). Kafka-driven cache invalidation can leave a deactivated rule live for 30 minutes (H3).
- **Decision.** Approval produces an immutable bundle `sha256` signed with Ed25519. Activation and rollback are monotonically versioned, signed manifest flips. Pods verify both before use (D5).
- **Consequences.**
  - Atomic switches; rollback in seconds; tamper evidence.
  - The same artifact runs in UAT and PROD.
  - Requires HSM-backed key management and a trust store in pods.
- **Alternatives rejected.** Rows in a shared database read by the data plane: coupling and no tamper evidence. Git-based rules: a business-user mismatch and no runtime atomicity guarantee.
