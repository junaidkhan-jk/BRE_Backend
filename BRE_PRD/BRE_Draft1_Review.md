# BRE Draft 1: Review and Plan for Draft 2

**Reviewed:** `BRE_BRD.pdf`, `BRE_HLD.pdf`, `BRE_LLD.pdf`, `BRE_SystemDesign.pdf` (all v1.0, June 2026)
**Review date:** 2026-10-01
**Basis:** a fresh, independent product. Every finding comes from the four documents themselves and from general lending, regulatory and engineering practice. No existing system or rule set was used as a reference.

---

## 0. Verdict

The four documents are a good skeleton: rule versioning, a lifecycle, deviation routing, an audit log and a cache. They are not yet a buildable spec. There are four headline problems:

1. **The design has security and correctness defects that would ship to production as written.**
   - Rule authors can execute arbitrary code.
   - A rule that errors lets the loan through.
   - The worked example gives the wrong answer for every input.
2. **The rule model is too thin for real lending policy.** Typical rules need collection functions, time windows and evaluation across several people or entities. Examples:
   - an MFI indebtedness cap summed across all lenders;
   - "no 30+ DPD in the last 12 months";
   - per-member checks rolled up to a group decision.

   Draft 1's flat `field OP value` DSL cannot express any of these. The caller would have to precompute everything, and policy would then live in the LOS, not in the BRE. That defeats the BRD's "single source of truth" goal.
3. **The four documents contradict each other** on:
   - sync vs async audit;
   - which outcome states exist;
   - which lifecycle states exist;
   - schema names;
   - whether the BRE fetches bureau data itself;
   - how rules reach production.
4. **There is no product definition.** Draft 1 does not say:
   - who uses or buys it;
   - whether it is multi-tenant;
   - what screens the business analyst gets;
   - what ships first.

   The BRD was written by engineering and reads like an HLD with a scope table.

Section 1 lists what to keep. Section 2 lists blocking defects. Section 3 covers gaps document by document. Section 4 proposes the Draft 2 structure. Section 5 records the decisions taken for Draft 2.

---

## 1. Keep these from Draft 1

- Separation of the **authoring plane** from the **execution plane**.
- **Immutable rule versions.** An activated version is never edited; a change creates a new version (FR-005).
- **PASS / DEVIATION / FAIL** outcomes, with the role each deviation routes to.
- **Deviation aggregation** into one escalation decision (FR-014).
- An **execution log** with input snapshot, outcome and the rule version evaluated.
- **Simulation before activation** (FR-019) and **back-testing** (FR-020).
- The **error-handling table** in LLD §7. It needs completing, not replacing.
- **Monthly partitioning** of the execution log.

---

## 2. Blocking issues (fix before anyone builds)

### B1. The `EXPR` operator lets rule authors run arbitrary code (critical, security)

LLD §2.2 evaluates rules with `new StandardEvaluationContext()`, and LLD §9 lets authors write raw SpEL such as `"#total_emi / #income < 0.5"`.

`StandardEvaluationContext` allows type references and method calls. A rule containing `T(java.lang.Runtime).getRuntime().exec('…')` would run on the BRE pod. In effect, every `RULE_AUTHOR` becomes a production shell user.

There is a second path: `buildSpelExpression(cond)` assembles expression strings from DSL values. That is an injection route even with no `EXPR` rules.

**Fix:**
- Never evaluate author-supplied text with a general-purpose expression language.
- Parse the DSL into an AST and compile it **once, at activation**. The LLD currently calls `parseExpression` on every evaluation.
- For computed expressions, use a sandboxed, non-Turing-complete, **decimal-native** language. Draft 2 defines one, BEL. CEL is sandboxed but has no decimal type, which rules it out for money and ratios.
- If SpEL must stay, use `SimpleEvaluationContext.forReadOnlyDataBinding()` at minimum.

### B2. A rule that errors lets the loan through (critical, credit risk)

Three statements in Draft 1 combine badly:
- LLD §7: *"SpEL evaluation exception → Rule marked as ERROR; do not fail entire request."*
- HLD §10: if the bureau is down, the rule is marked *PENDING_DATA*.
- LLD §2.1: `resolveOverallStatus()` returns FAIL if any rule failed, then DEVIATION if any deviated, and **`PASS`** otherwise.

So if the FOIR or blacklist rule throws an exception, or its data is missing, the application can come back as an overall **PASS** and be auto-approved.

**Fix:** define one outcome model with explicit precedence, and make it fail-closed. The first match wins:

1. Any `FAIL` → **DECLINE**
2. Otherwise any `REFER`, `DATA_MISSING` or `ERROR` → **REFER**. An errored or data-starved rule can never produce an approval.
3. Otherwise any unapproved `DEVIATION` → **APPROVE_WITH_DEVIATION**
4. Otherwise → **APPROVE**

`NOT_APPLICABLE` is excluded from the result. Draft 2 §3.5 has the full model.

The DDL stores `rule_status SMALLINT` with values 1, 2 and 3 only, so it cannot hold most of these states.

The LLD sample response (§3.2) already shows the gap. It reports `totalRules: 5`, but `passCount 3 + deviationCount 1 + failCount 0` adds up to 4. The fifth rule has a status nothing counts.

### B3. The worked example evaluates wrongly for every input

The SHG0003 DSL (LLD §1.1) wraps three conditions in `"operator": "AND"`. `evaluateSingleCondition()` (LLD §2.2) returns PASS when a leaf is true. When a leaf is false, it returns that leaf's own `outcome`.

| VRM category | Intended | Leaf results (IN A,B / EQ C / IN D,E) | AND as worst-wins | AND as first-non-pass |
|---|---|---|---|---|
| A or B | PASS | PASS / DEVIATION / FAIL | **FAIL** | **DEVIATION** |
| C | DEVIATION | PASS / PASS / FAIL | **FAIL** | **FAIL** |
| D or E | FAIL | PASS / DEVIATION / PASS | **DEVIATION** | **DEVIATION** |

The LLD never defines `resolveAnd`. Under either plausible reading, none of the five categories gets the intended answer.

The root cause is that the DSL mixes up "condition" and "outcome". SHG0003 is not an AND; it is a **first-match decision table**.

**Fix:**
- Add a decision-table construct with an explicit **hit policy**: FIRST, PRIORITY or COLLECT, as in DMN.
- Give every example in the spec a test table of inputs and expected outcomes.

### B4. Nobody has decided who fetches the data

The documents disagree:

| Document | Says |
|---|---|
| BRD §9.1 | Bureau, KYC and VRM data are *upstream inputs*. |
| HLD §5.1, §10; LLD §5, §7 | The BRE calls the bureau itself: SLA under 3 s, Resilience4j, 3 retries with backoff, responses cached for 24 h under `bre:bureau:cibil:{pan}`. |
| LLD §3.1 | LOS sends `cibil_score`, `foir` and the other values in `context`. |

This is the most important architectural decision in the product, and it is still open. Three problems follow from leaving it open:

- **Latency.** A bureau call with a 3 s SLA and 3 retries cannot fit inside a **500 ms P99** budget (NFR-001).
- **A second bureau pull.** System Design Diagram 8, stage 6, *re-runs all rules before disbursement*. If the BRE does the fetching, that is a second bureau inquiry, with its cost, inquiry footprint and consent scope.
- **Regulated data in a shared cache.** Bureau reports would sit in Redis under plaintext PAN keys. That raises questions under CICRA purpose limitation, the DPDP Act and data security.

**Recommendation:**
- Make the BRE a **pure decision function: facts in, decision out**. It never calls a bureau.
- Put data acquisition (bureau, KYC, dedupe, VRM) in a separate *fact-preparation* layer. That layer snapshots what it fetched, and the snapshot travels with the request.
- This makes simulation, back-testing and replay exact (AC-007), and makes the 500 ms target realistic.

The LLD sample payload shows why this matters. It sends `"foir": 0.42` together with `monthly_income: 18000` and `total_emi: 4200`, and 4200 / 18000 is 0.233. One fact arrives from two sources that disagree. Draft 2 must say which value MFI0001 reads, and who computes it.

### B5. The rule model cannot express typical lending rules

Here is an ordinary MFI guardrail rule:

```
total_indebtedness =
      SUM(outstanding of bureau tradelines
            WHERE account_type IN UNSECURED_TYPES AND status IN OPEN_STATUSES)
    + SUM(outstanding of self-declared loans not on the bureau)
    + proposed_amount          -- approved ?? recommended ?? requested, depending on stage
IF total_indebtedness > INDEBTEDNESS_CAP          → DEVIATION (authority by amount band)
IF COUNT(DISTINCT lender of open MFI tradelines) + 1 > LENDER_CAP → FAIL
```

Draft 1 has no way to write it. The missing constructs:

| Construct | Typical rule that needs it |
|---|---|
| **Collection functions with filters**: sum, count, max, distinct over lists | Total unsecured indebtedness; active lender count; highest DPD across tradelines |
| **Time windows** | "No 30+ DPD in the last 12 months"; "≤ 3 enquiries in the last 90 days"; "no write-off in 36 months" |
| **Multiple entities and roles per application** | Each JLG/SHG member checked, then aggregated to a group decision; co-applicant and guarantor checks in retail; **each** promoter in SME (SME0003 takes a single `promoter_id`) |
| **Reference lists** | Negative areas or pincodes, restricted sectors, account types counted as unsecured |
| **Parameters, separate from logic** | Bureau cut-off per product, FOIR cap, LTV cap, indebtedness cap. A threshold change should not mean editing rule logic. |
| **Fallback chains and stage-dependent inputs** | Loan amount = approved ?? recommended ?? requested |
| **Computed outputs**, not only pass or fail | Eligible amount = min(FOIR-based, LTV-based, product cap); maximum tenure from age at maturity |
| **Scores and match percentages** | Application scorecard; name-match % between KYC documents |
| **Applicability preconditions** | "LTV only if secured" (Diagram 8 says this, but nothing models it); NTB vs ETB variants |
| **Explicit missing-data handling** | New-to-credit applicants with no bureau score |

The BRD's MFI coverage also names only **SHG** rules. **JLG**, the main lending model of NBFC-MFIs, is not covered, and neither are individual MFI loans. There is no co-applicant or guarantor concept anywhere.

### B6. The audit design contradicts itself and would not scale

**Sync vs async:**
- HLD §10 says *RPO = 0; all executions are persisted synchronously before the response*.
- System Design Diagram 2, step 6, says *persist exec log (async)*. The Scaling table says *audit logged async via internal queue*.
- HLD §10 says *events go through a DB outbox*. The LLD code publishes to Kafka directly, outside any transaction.

**Volume.** The log stores **one row per rule**, and each row carries its own copy of `input_snapshot`. At the stated NFR:
- 1,000 req/s × 100 rules = **100,000 inserts/s**, or **8.6 billion rows a day**.
- The full input JSON is duplicated about 100 times per request.

Store **one decision record per evaluation**, holding the inputs once plus an array of rule results. Fan out per-rule rows to an analytics store through the outbox.

**Invalid DDL.** PostgreSQL rejects `id BIGSERIAL PRIMARY KEY` on a table declared `PARTITION BY RANGE (created_on)`, because a partitioned table's primary key must include the partition column. The table is also declared twice (LLD §1 and §8).

**"Immutable audit log" (NFR-006).** An ordinary Postgres table is not immutable. It needs:
- append-only grants (no `UPDATE` or `DELETE` for the application role);
- a per-record hash chain;
- archival to write-once storage (S3 Object Lock or equivalent).

### B7. Without policy-level versioning, decisions cannot be reproduced and rollback has no mechanism

Rules are versioned and activated **one at a time**. A credit-policy change that touches 12 rules therefore passes through 11 inconsistent intermediate states in production.

Several things are missing:
- No record of *which set of rule versions* produced a given decision.
- Effective dates are evaluated against the database's `CURRENT_DATE`, behind a 30-minute cache.

As a result, Draft 1 cannot answer:
- *An application started under policy v3, and v4 went live before disbursement. Which one applies?* Grandfathering is not specified.
- *Replay this decision from March exactly.*
- AC-006, *rollback within 60 s*. The state machine has no `SUPERSEDED → ACTIVE` transition, and the cache can keep serving a deactivated rule for 30 minutes (see H3).

**Fix:**
- Introduce a **Policy** (product × stage × segment).
- Introduce a **Policy Version**: an immutable bundle of rule versions, parameters and reference lists, identified by a content hash.
- Activation and rollback switch a single pointer **atomically**.
- Every decision records the policy-version hash, the engine version, and an `asOf` timestamp the caller can pin.

---

## 3. Gaps by document

### 3.1 BRD: product and business

| # | Gap | What Draft 2 should contain |
|---|---|---|
| R1 | **Product or internal platform?** The document still has placeholders ("Universal BRE Platform", "Your Organisation") and never mentions tenancy. | Vision and positioning; target customers (MFI, NBFC, bank); deployment model (SaaS, private cloud, or on-prem in the lender's DC); tenancy model; licensing. |
| R2 | **The business-analyst rule studio has no specification**, although it is the core promise (FR-001, AC-001). It appears only as a box in Diagram 5. | Personas: Policy Analyst, Approver, Risk, Auditor, Ops. Screens and journeys: author, test, compare versions, submit, approve, activate, roll back. Form-based vs table-based editing. How a non-technical user expresses "sum over tradelines". |
| R3 | **Rule constructs** are limited to IF-THEN-ELSE. | Rules, **decision tables** with hit policies, **scorecards** (bins, weights, reason codes), **derived variables**, **parameters**, **reference lists**, **computed outputs**, and **rule flows** for sequencing and branching (for example, stop before the bureau stage if KYC fails). See B5. |
| R4 | **Multi-entity evaluation.** `entity_type` exists but has no defined behaviour. | Rules evaluate at group, member, applicant, co-applicant, guarantor or promoter level. Member results aggregate to a group outcome (for example, "group FAILs if any member FAILs" or "if ≥ 20% DEVIATE"). Results are reported per entity. |
| R5 | **The outcome model is inconsistent** across documents: PASS/DEV/FAIL (FR-006), REVIEW (AML0001), PENDING_DATA (HLD), ERROR (LLD). REJECTED appears too (Diagram 3), but it is a lifecycle state, not an outcome. | One outcome enum with precedence (B2), plus `NOT_APPLICABLE`. Explicit missing-data handling per variable: fail, refer, or default. New-to-credit applicants handled as a named case, not as a side effect of `null < 700`. |
| R6 | **No reasons or explainability.** | A reason-code catalogue with internal and customer-facing text. The RBI Fair Practices Code requires lenders to convey the main rejection reasons in writing. Vernacular language support. A per-decision explanation trace. |
| R7 | **The deviation model is too simple.** The role hierarchy is hard-coded in Java (LLD §2.4), which breaks NFR-007. It silently defaults to `CU_L3` and includes `MD`, which the BRD never mentions. | A **delegation-of-powers matrix** held as data: approving authority by deviation type × loan-amount band. Configurable routing: highest authority only, or each deviation to its own approver. Who records deviation approvals (BRE or the LOS workflow). Whether approved deviations re-raise on re-evaluation. Expiry rules. |
| R8 | **Shadow runs and champion/challenger** are listed as a problem in §2.1, but no FR addresses them. | Shadow-run a candidate policy version on live traffic, without affecting outcomes, and produce a diff report. Champion/challenger traffic split by segment. |
| R9 | **No testing or safety checks before activation.** | Test cases stored with each rule and run automatically on submit and approve. **Impact analysis:** change in approval, deviation and reject rates over the last N days, plus the list of applications whose outcome would flip. **Static checks:** overlapping rows, coverage gaps, unreachable branches, references to unknown variables. |
| R10 | **No mechanism for promotion across environments.** Rules are database rows with different IDs in each environment, yet HLD §7.2 says DEV is "auto-deploy on commit", which assumes rules live in git. | A signed policy bundle exported from UAT and imported to PROD, with its content hash verified on import. A diff view between environments. An explicit choice of rules-as-data vs rules-as-code. Recommendation: data, with bundle export. |
| R11 | **Maker-checker.** "Dual approval" appears only in the HLD environment table. | Segregation of duties: an author cannot approve their own change. Number of approvals per environment and per rule criticality. Four-eyes review on parameter changes too. |
| R12 | **No regulatory section.** BRD MFI0001 computes FOIR per borrower; the RBI microfinance framework measures it against household income. | Cover: RBI *Regulatory Framework for Microfinance Loans* (2022) — household income ≤ ₹3 lakh, repayment obligations ≤ 50% of **monthly household** income. SRO guardrails (lender count, total indebtedness cap), which change periodically and so must be parameters. RBI Digital Lending Guidelines. **DPDP Act 2023**: consent, purpose limitation, retention, and what erasure means for input snapshots. PMLA/KYC record retention. RBI IT Governance Master Direction (2023): change management and audit trail. Model-risk governance if scorecards or ML are in scope. Hold every threshold as a parameter, and have compliance confirm current values. |
| R13 | **Bureau assumptions are inconsistent.** BRD §9.1 lists CIBIL, Equifax and CRIF, but the design hard-codes CIBIL (`RET0001`, cache key `bre:bureau:cibil:*`, "Credit Bureau (CIBIL)" in HLD §5.1). MFI lending commonly relies on bureaus other than CIBIL. | A bureau-agnostic fact model (tradelines, enquiries, a score with its source), mapped per bureau. |
| R14 | **Adjacent decisions** are not scoped. Pricing is excluded, but eligible-amount and limit calculation, priority-sector (PSL) classification and STP routing are decision logic too. | State explicitly which of these are in scope. |
| R15 | **Document hygiene.** | An owner and sign-off table; a change log; **MoSCoW priority** on every FR; **FR → AC traceability** (FR-010 to FR-020 have no ACs today); success KPIs (for example, rule-change lead time from 2–4 weeks to under 1 day, and % of changes made with no engineering involvement); risks; dependencies; open questions; a phased release plan. Glossary additions: JLG, NTB/ETB, NTC, DPD, DoP, STP, PSL. |

### 3.2 HLD

| # | Issue | Fix |
|---|---|---|
| H1 | System Design Scaling and Diagram 6 say *"Only 1 node runs all 50+ consumers via Redis SET NX PX leader election"*, with pods 2 and 3 as "standby, REST only". This defeats Kafka consumer groups. It makes the async path a single point of failure. It contradicts "stateless" and HPA scaling (NFR-004). Also, "50+ consumers" does not match the 5 topics the LLD defines. | Use normal consumer groups. Partition `bre.evaluation.request` by application ID. Every pod consumes. |
| H2 | Service discovery is listed as "Kubernetes / Eureka". | Pick one. On Kubernetes, use cluster DNS. |
| H3 | **Cache invalidation depends on Kafka.** If Kafka is down, a **deactivated rule keeps firing for up to 30 minutes**. That breaks AC-006 and is a compliance issue, yet HLD §10 claims a Kafka outage does not affect sync evaluation. If invalidation is keyed by the rule's own product type, a rule with `product_type = 'ALL'` clears only the `…:ALL:…` key, which no read path populates. The signature `invalidateCache(productType, ruleGroup)` implies exactly that keying. Effective-date boundaries also lag by up to 30 minutes. | Keep the **compiled policy snapshot in process**; rules are only kilobytes. Each pod polls a single `active_policy_version` pointer every few seconds, or uses Postgres `LISTEN/NOTIFY`, and swaps its snapshot atomically. Redis becomes optional and leaves the hot path. This meets the 60 s rollback target with no Kafka dependency. |
| H4 | Bureau and blacklist responses are cached in Redis under plaintext `{pan}` keys. | Remove these caches if B4 is accepted. Otherwise hash the keys, encrypt the values, and record purpose and consent references. |
| H5 | HLD §8.2 says PII is *masked* in logs **and** that inputs are *encrypted* at rest. Back-testing (FR-020) cannot run on masked data, and a PAN-based blacklist rule cannot be replayed. | Classify every field in the input schema. Store the full input **encrypted**, with decryption restricted to replay and back-testing. Show viewers only a **masked view**. |
| H6 | DR covers pod restarts only. The design has a single Postgres primary with one read replica, a single region, and no database failover, which cannot meet 99.95% (NFR-003). NFR-002 is also stated vaguely ("1,000 concurrent requests per second"). | Database HA: a synchronous standby with automatic failover. A DR-site plan. RTO and RPO per component. Restate NFR-002 as a peak RPS figure backed by volume sizing. |
| H7 | **The engine choice is not justified.** SpEL was picked without comparing alternatives, although the BRD explicitly allows Drools. | Write an ADR comparing two options: **(a)** your own JSON model compiled to an AST, with CEL for expressions; **(b)** DMN on Apache KIE/Drools, which brings standard decision tables, FEEL and existing modelling tools, but a heavier runtime. Draft 2 takes (a), with an in-house decimal-native expression language (BEL), because CEL has no decimal type. The reasons are control over the BA UI, sandboxing and latency. It uses DMN-style hit policies so a DMN export can be added later. |
| H8 | The stack is Java 17 + Spring Boot 3.x. | Java 25 is the current LTS and Spring Boot 4.x is generally available. A product starting in October 2026 should not begin on Java 17 + Boot 3.x. |

### 3.3 LLD

| # | Issue |
|---|---|
| L1 | The prose names the schema `bre_engine`; the DDL uses `schema_bre`. |
| L2 | **Missing tables:** policy and policy version; input schema / data dictionary (FR-012 has no design); derived variables; parameters; reference lists; deviation authority matrix; reason codes; test cases; simulation and back-test runs; approvals; tenant. |
| L3 | **FR-010 and FR-011 have no columns.** There is no execution order, `depends_on` or short-circuit flag, and the repository simply orders by `rule_category, version_number`. |
| L4 | `ruleGroup` is in the API but not the schema; the repository maps it to `applicability_stage`. The stage enum (`PRE_SANCTION / POST_SANCTION / DISBURSEMENT`) does not match Diagram 8's six stages. Stage names differ between lenders, so stages should be configuration, not an enum. |
| L5 | `deviation_role` exists both on the version row and on each DSL condition, and nothing says which one wins. `outcome_config JSONB` is never specified. |
| L6 | `loan_app_id` and `entity_id` are `BIGINT`. A product must accept the caller's external references as strings. |
| L7 | There is no optimistic locking (`row_version`) on rule edits, and no `tenant_id`. |
| L8 | **Effective dating has gaps.** Activating v2 with a future `effective_from` immediately supersedes v1, leaving a window with **no** active version. Nothing prevents overlapping windows. `DATE` compared with the database's `CURRENT_DATE` has no time zone. |
| L9 | **Numeric precision is unspecified.** JSON numbers bind to `Integer` or `Double`. Money and ratios need `BigDecimal` with **specified rounding**: is a FOIR of 50.004% a pass? |
| L10 | **Missing values fail silently.** SpEL treats `null` as less than everything, so a missing `cibil_score` becomes FAIL instead of PENDING_DATA or new-to-credit handling. |
| L11 | **The async consumer loses messages.** Catching the exception and not calling `ack` does **not** cause redelivery: the next acknowledged record commits past it, and the message never reaches a DLQ. Use `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`, put an idempotency key on the request, and write `evaluate`, the decision record and the outbox entry in one transaction. |
| L12 | **API gaps.** Only activate and deactivate exist; add submit, approve, reject and rollback endpoints, plus back-test, export (FR-018) and input-schema management. `PUT /rules/{id}` silently creating a new version is surprising; make it `POST /rules/{code}/versions`. Specify `Idempotency-Key`, correlation ID, `asOf`, tenant, an error contract (RFC 9457 problem+json), pagination and an OpenAPI file. The response should return the `policyVersion` hash and the engine version. |
| L13 | Placeholders are left in the samples, for example `"loanAppId": ENTITY_ID`. |

### 3.4 System Design

| # | Issue |
|---|---|
| S1 | Diagram 2, step 6, persists the log asynchronously, which contradicts RPO 0 (see B6). |
| S2 | Diagram 3 adds a `REJECTED` state that the BRD and LLD lack. Three transitions are missing: `REJECTED → DRAFT` for rework, `SUPERSEDED → ACTIVE` for rollback, and an expiry for an APPROVED version that is never activated. |
| S3 | Diagram 8 mixes product lines in one journey: RET0005 and the RET0001 CIBIL check inside an MFI flow, and SME0004 at stage 5. Journeys should be defined per product. Stage 6, "re-run all ACTIVE rules", needs explicit rules for bureau re-pulls (B4) and for deviations already approved (R7). |
| S4 | The "diagrams" are text tables. Draft 2 needs real C4 context, container and component diagrams, plus sequence and state diagrams, kept as Mermaid or PlantUML source so they can be versioned. |

---

## 4. Proposed shape of Draft 2

### 4.1 Document set

| Document | Replaces | Contents |
|---|---|---|
| **PRD** | BRD | Vision; personas; scope and non-scope; the **decision model** (§4.2); user journeys; FRs with MoSCoW priority and ACs; NFRs with volume sizing; a regulatory section; KPIs; a phased roadmap; risks; open questions. |
| **Architecture** (HLD and System Design merged) | HLD, SysDesign | C4 diagrams; data flows; ADRs (engine choice, facts-in, caching, audit, tenancy); security; DR; deployment. |
| **LLD** | LLD | Schema with valid DDL; a **DSL specification (JSON Schema plus grammar) with test vectors**; OpenAPI; AsyncAPI for events; an error catalogue; state machines. |
| **Rule Authoring Guide** | *(new)* | How a business analyst expresses each construct, with a worked example and expected-outcome table for each. |

### 4.2 Proposed core model

```
Tenant
 └─ Policy                (product × stage × segment, e.g. MFI-JLG / CREDIT_UW / NTB)
     └─ PolicyVersion     (immutable bundle, content hash; DRAFT→REVIEW→APPROVED→ACTIVE→RETIRED;
         │                 atomic activation and rollback; effective-dated; pinned per application)
         ├─ Variables       input (from the fact schema) | derived (expressions, collection functions)
         ├─ Parameters      e.g. INDEBTEDNESS_CAP, FOIR_CAP; maker-checked and effective-dated
         ├─ ReferenceLists  e.g. OPEN_STATUSES, UNSECURED_TYPES, NEGATIVE_AREAS
         ├─ Rules           precondition → condition → outcome + reason code + deviation type
         ├─ DecisionTables  inputs × rows → outcome/output, with hit policy
         ├─ Scorecards      bins × weights → score + reason codes
         └─ Flow            ordered stages, short-circuit points, entity aggregation
Decision      one immutable record per evaluation: facts (encrypted), outcomes per rule
              and entity, overall outcome, reasons, deviations, policyVersion hash,
              engine version, asOf, latency
```

### 4.3 Suggested phasing (adjust once the decisions in §5 are made)

| Phase | Delivers |
|---|---|
| **1 · MVP** | Facts-in evaluator. Rules, decision tables, derived variables, parameters and lists. Policy versioning with maker-checker. Per-rule test cases and simulation. Decision record. Sync REST API. Deviation matrix. Rule-studio screens for authoring, testing and approval. **One launch product line, whose full policy is written as the acceptance suite.** |
| **2** | Back-testing with impact analysis. Shadow mode. Async Kafka path. Scorecards. Signed promotion bundles. Reports and export. |
| **3** | Multi-tenant packaging. Champion/challenger. Fact connectors (bureau, KYC) as a separate component. Prebuilt regulatory rule packs. ML model hosting with model-risk controls. |

---

## 5. Decisions

The defaults are recorded in `draft2/00_Decisions_and_Canonical_Terms.md`, decisions D1–D15. Draft 2 is written against those defaults, and any of them can be overridden. In summary:
- multi-tenant product, also deployable single-tenant;
- facts in, decision out;
- in-house engine with the decimal-native BEL expression language;
- MFI group lending (JLG/SHG) as the launch vertical;
- control-plane / data-plane split with signed policy bundles;
- Kafka-first decision log;
- Java 25 + Spring Boot 4.x.
