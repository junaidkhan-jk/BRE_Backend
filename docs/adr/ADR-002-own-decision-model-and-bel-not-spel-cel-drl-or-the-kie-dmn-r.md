# ADR-002: Own decision model and BEL; not SpEL, CEL, DRL or the KIE DMN runtime

**Status:** Accepted for Draft 2.
**Source:** carried verbatim from `BRE_PRD/draft2/02_Architecture.md` §14.

- **Context.** Rule authors are business users. Money needs exact decimals. Draft 1's SpEL with `StandardEvaluationContext` allows arbitrary code execution (review B1). Draft 1's DSL cannot express decision tables (B3) or collection and time-window logic (B5).
- **Decision.** Own IR for rules, decision tables (with hit policies), scorecards, variables and flows. Own typed, decimal-native, sandboxed **BEL**, compiled at approval into closures run by `bre-engine-core` (D3).
- **Consequences.**
  - Full control of safety (no host calls, fuel), performance and studio UX (precise diagnostics).
  - We own a language: the grammar, type checker and conformance suite are long-lived assets that need investment.
  - DMN import/export is deferred to Phase 3.
- **Alternatives rejected.**
  - SpEL: unsafe by default and has no decimal discipline.
  - CEL: sandboxed but has **no decimal type**.
  - Drools DRL: not usable by business analysts; a heavy runtime.
  - KIE DMN/FEEL runtime: a standard and the closest fit, but heavy, with less control over diagnostics and sandboxing (external function definitions must be fenced off). It is kept as the reference semantics for hit policies and unary tests.
