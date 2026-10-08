# BRE Platform: Draft 2

Draft 2 replaces the four Draft 1 PDFs in the parent folder. They are a fresh, independent product specification.

| # | File | Replaces | Read it for |
|---|---|---|---|
| 00 | [00_Decisions_and_Canonical_Terms.md](00_Decisions_and_Canonical_Terms.md) | — | Decisions D1–D15. The single vocabulary that every other document follows: enums, IDs, paths, topics, roles, fact schema, BEL, and the Policy Source Format. |
| 01 | [01_PRD.md](01_PRD.md) | BRE_BRD.pdf | What we are building and for whom. Requirements with priorities and acceptance criteria. Regulatory scope. Release plan. |
| 02 | [02_Architecture.md](02_Architecture.md) | BRE_HLD.pdf, BRE_SystemDesign.pdf | How it scales and stays up: control plane and data plane, capacity model, security, DR, ADRs. |
| 03 | [03_LLD.md](03_LLD.md) | BRE_LLD.pdf | What to build: BEL grammar, the IR, DDL, APIs, events, algorithms, error catalogue, test vectors. |
| 04 | [04_Rule_Authoring_Guide.md](04_Rule_Authoring_Guide.md) | *(new)* | How a business analyst writes, tests and ships a policy. Worked examples across MFI, retail and MSME. |
| 05 | [05_Build_Plan.md](05_Build_Plan.md) | *(new)* | How we build it from scratch: milestones M0–M6 with tasks and exits, critical path, team and effort, Definition of Done, test strategy, risks, and the decisions needed to start. |

**HTML edition:** [../html/index.html](../html/index.html) has browsable, self-contained pages for every document, with the diagrams rendered. Regenerate them with `docs/BRE_PRD/.build/build_html.py`.

**Background:** [../BRE_Draft1_Review.md](../BRE_Draft1_Review.md) is the review of Draft 1 that Draft 2 answers. Each Draft 2 document ends with a traceability appendix that maps the review findings to the sections that resolve them.

**Precedence:** if two documents disagree, `00` wins. Raise the conflict, and fix the document that is wrong.

**Status (2026-10-01):** all five documents are written, about 9,400 lines in total, and have had a consistency pass. Every conflict raised by the individual writers is resolved in `00`: approval counts, pinning, `asOf`, async ingress, event encoding, hot-store retention, the RPO qualification, and BEL semantics S1–S13.

What has and has not been checked:
- The YAML and JSON blocks parse.
- The LLD's SQL parses with pglast, but has not been executed against PostgreSQL.
- The Mermaid diagrams have not been rendered.
