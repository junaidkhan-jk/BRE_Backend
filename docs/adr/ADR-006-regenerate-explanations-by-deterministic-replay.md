# ADR-006: Regenerate explanations by deterministic replay

**Status:** Accepted for Draft 2.
**Source:** carried verbatim from `BRE_PRD/draft2/02_Architecture.md` §14.

- **Context.** Storing a full trace per decision would multiply record size by about 10, against a 25 M/day design point.
- **Decision.** Store outcomes, reason codes and key values. Regenerate the trace on demand from the stored facts, bundle hash and engine major, and verify that the replayed outcome equals the recorded one (D7).
- **Consequences.**
  - About 10× less storage.
  - An explanation costs one evaluation (milliseconds).
  - Engine majors must be retained as replay images for the archive retention period.
- **Alternatives rejected.** Storing every trace (cost). Storing no trace and no replay capability (fails audit).
