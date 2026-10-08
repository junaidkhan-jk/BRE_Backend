# BRE Platform: Draft 2, Decisions and Canonical Terms

**Status:** Draft 2 · 2026-10-01
**Applies to:** `01_PRD.md`, `02_Architecture.md`, `03_LLD.md` and `04_Rule_Authoring_Guide.md`. Every Draft 2 document uses the names, enums, numbers and paths defined here. If a document needs a term that is not here, it adds the term to its own "Additions to canonical terms" appendix rather than inventing a conflicting one.

**Basis:** this is a **fresh, independent product**. It is not derived from any existing rule set or codebase. It uses only open dependencies from Maven Central and npm.

---

## 1. Decisions taken for Draft 2

Each decision below is the default. You can override any of them; if you do, every document is updated to match.

| ID | Decision | Default taken | Why |
|---|---|---|---|
| D1 | **Product model** | **Multi-tenant from day one.** `tenant_id` appears on every row, cache key, event, metric and log line. The same artifacts ship as (a) multi-tenant SaaS hosted in India, or (b) a dedicated single-tenant install in a lender's VPC or data centre. | Tenancy costs little to build in and is very expensive to retrofit. Indian banks often insist on (b), and NBFC-MFIs often prefer (a). |
| D2 | **Facts in, decision out** | The Decision API is a **pure function** of `(facts, policy bundle, asOf)`. The BRE never calls a bureau, KYC, screening or core-banking system. **Fact Connectors** are a separate, optional component (Phase 3) that fetch data and produce fact snapshots. | Determinism makes replay, simulation and back-testing exact. It keeps latency in single-digit ms. It keeps regulated data such as bureau reports out of the decision engine. |
| D3 | **Engine** | Our own decision model, plus our own typed, **decimal-native, sandboxed** expression language, **BEL (BRE Expression Language)**, with FEEL-inspired syntax. It is compiled at approval time into an IR and executed by **`bre-engine-core`**, a pure Java library with no Spring dependency. DMN import/export comes in Phase 3. | SpEL can execute arbitrary code. CEL has no decimal type, which is unacceptable for money and ratios. Drools DRL is not friendly to business analysts. The KIE DMN runtime is heavy and gives us less control over the authoring experience; it remains a reference design. |
| D4 | **MVP launch vertical** | **MFI group lending (JLG and SHG).** Retail and MSME follow in Phase 2. | It has the hardest entity model (group → members → households). A model that handles it handles retail and MSME. |
| D5 | **Control plane / data plane split** | Policy bundles are **content-addressed** (SHA-256), **signed** (Ed25519) and immutable. The data plane loads bundles by hash. Activation is an atomic pointer flip. All pods must converge within **30 s**. | Gives atomic policy switches, instant rollback and tamper evidence. **No policy data is read from a database or Redis on the hot path.** The only shared-state hop is the optional idempotency key in Valkey. If Valkey is down the request still proceeds, because a duplicate is harmless: evaluation is deterministic and the sink de-duplicates on `(tenant_id, request_id)`. |
| D6 | **Decision log** | **Kafka-first durable write** before the response returns: `acks=all`, RF 3, `min.insync.replicas=2`, idempotent producer. This gives RPO 0 for every acknowledged decision. Sinks then copy decisions to a PostgreSQL **hot store**, an object-store **WORM archive** (Parquet + zstd, default retention 10 years) and analytics. The hot store keeps decision headers and results for 90 days, but facts for only 14 days (both configurable). Older facts are read from the archive for explanations and back-tests. **Compact mode** (small on-prem installs) instead writes to PostgreSQL first, with a transactional outbox. Both modes sit behind one port, `DecisionLog`. | The write path is the scaling bottleneck, and Kafka scales it horizontally. Compact mode keeps small installs simple. |
| D7 | **Explanations** | Store outcomes, reason codes and key values per decision. Regenerate the full evaluation **trace on demand** by deterministic replay of the stored facts against the stored bundle hash. | Roughly 10× less storage, and the trace is still exact. |
| D8 | **Determinism** | `BigDecimal` everywhere. Evaluation never reads the wall clock; it uses only `asOf`. No randomness: champion/challenger assignment is `hash(tenantId, applicationRef, experimentId) mod 10000`. The engine version is pinned in every decision. | Makes "replay this decision from March" exact, and makes simulation parity 100% by construction. |
| D9 | **Outcomes** | Rule status and the overall decision are separate enums (§3.5). The model is fail-closed: `ERROR` and `DATA_MISSING` can never produce `APPROVE`. | Closes the fail-open defect in Draft 1. |
| D10 | **Deviation approvals** | The BRE computes **which approvals are required**, using the authority matrix. The **caller's workflow** (LOS) runs the approval process. Approved deviations come back as facts (`deviationApprovals[]`), each with the approved value bound and an expiry. On re-evaluation an approved deviation is not raised again unless the underlying value has **worsened beyond the approved bound**. | The BRE stays stateless. Approvals are bound to a specific value, as credit policy requires. |
| D11 | **Stack** | Java 25 LTS · Spring Boot 4.x (virtual threads) · Gradle Kotlin DSL · PostgreSQL 17+ · Apache Kafka 4.x (KRaft) · Redis 7+/Valkey (optional: idempotency keys, rate limits) · S3-compatible object storage (MinIO on-prem) · Kubernetes · OpenTelemetry · Prometheus + Grafana · any OIDC provider (Keycloak by default) · Vault or cloud KMS, with HSM for PROD · React + TypeScript studio with the Monaco editor · RE2J for regex. **Maven Central and npm only for `libs/*`, which includes the engine** (enforced by the root `checkLibsAreMavenCentralOnly` task). **Amended 2026-10-08 (ADR-016):** `services/*` may additionally depend on `trustt-platform-lib`, resolved from the private Trustt Nexus, for service-edge concerns (auth, tenancy propagation, audit columns, Kafka and Redis helpers). The Nexus repository is declared in exactly one place, the `bre.spring-service` convention plugin. `bre-engine-core` keeps the D3 purity guarantee unchanged. | Current long-term-support versions with no proprietary licences. The `services/*` amendment avoids re-implementing five solved service-edge concerns in an organisation that already runs 23 services on that library; it stops at the `libs/` boundary so the engine, and anything a customer runs, stays clean. |
| D12 | **Interfaces** | Decision API over REST/JSON (OpenAPI 3.1) **and** gRPC/protobuf. Async work and events over Kafka using the CloudEvents 1.0 envelope (AsyncAPI 3.0). | gRPC suits high-volume system callers. REST suits everyone else. |
| D13 | **Residency and regions** | All data stays in India. The data plane runs **active-active across two regions** (or two availability zones in compact mode). The control plane runs active-passive. | Data residency, and 99.95%+ availability. |
| D14 | **Cells** | Tenants are assigned to **cells**. A cell is a set of decision pods, its own Kafka partitions and its own PostgreSQL shard. A large tenant gets a dedicated cell. The gateway routes requests by tenant. | Limits the blast radius and isolates noisy neighbours. Scale-out is "add a cell". |
| D15 | **Document format** | Markdown with Mermaid diagrams. Exported to PDF, DOCX or HTML on request. | The source can be diffed and versioned. |

---

## 2. Requirement numbering

- Functional: `FR-<AREA>-nnn`
- Non-functional: `NFR-<CAT>-nn`
- Acceptance criteria: `AC-<AREA>-nnn`

| AREA | Covers |
|---|---|
| MODEL | Decision model: variables, rules, decision tables, scorecards, flows, entities |
| AUTH | Authoring studio and the BEL editor |
| LIFE | Policy-version lifecycle, deployments, promotion, rollback |
| EXEC | Decision execution: sync, async, bulk |
| DEV | Deviations and the authority matrix |
| TEST | Rule tests, simulation, back-testing, impact analysis, shadow runs, champion/challenger |
| AUD | Decision record, audit trail, explanations, reason codes |
| INT | Integration contracts, SDKs, events |
| ADM | Tenancy, cells, quotas, environments, users |
| RPT | Reporting, MIS, monitoring of decision outcomes |
| GOV | Regulatory and model governance controls |

| NFR category | Meaning |
|---|---|
| PERF | Performance |
| SCALE | Scalability |
| AVAIL | Availability |
| DR | Disaster recovery |
| SEC | Security |
| PRIV | Privacy |
| OBS | Observability |
| OPS | Operability |
| COMP | Compliance |
| MAINT | Maintainability |

---

## 3. Canonical vocabulary

### 3.1 Organisation and scoping

| Term | Definition |
|---|---|
| **Tenant** | A lender organisation: bank, NBFC, NBFC-MFI, HFC or SFB. |
| **Environment** | DEV, UAT or PROD. The set of environments is configurable per tenant. Policy assets are promoted between environments as signed bundles. |
| **Cell** | A data-plane scaling and isolation unit (D14). |
| **Product line** | Tenant-configured code. Seeded examples: `MFI_JLG`, `MFI_SHG`, `RETAIL_PL`, `RETAIL_HL`, `RETAIL_LAP`, `GOLD`, `MSME_TL`, `MSME_WC`. |
| **Stage** | Tenant-configured code for a point in the loan journey where a decision is requested, for example `SOURCING`, `KYC`, `CREDIT_UW`, `SANCTION`, `PRE_DISBURSAL`. Stages are configuration, never an enum. |
| **Segment** | An optional tenant-defined sub-population, for example `NTB` or `ETB`. |

### 3.2 Policy assets

| Term | Definition |
|---|---|
| **Policy** | Identified by `(tenant, policyCode)`. Declares the product line(s), stage and segment it serves. Each Policy has many PolicyVersions. |
| **PolicyVersion** | The immutable unit of approval. It contains Variables, Rules, DecisionTables, Scorecards and a Flow. It pins exact versions of Shared Assets and of one FactSchema. Its versions are numbered 1, 2, 3 and so on. |
| **Shared Assets** | Tenant-level assets that are versioned independently and pinned by PolicyVersions: **Parameter Set**, **Reference List**, **Reason Code Catalogue**, **Authority Matrix**, **Fact Schema**. |
| **Bundle** | The compiled, signed, content-addressed artifact built when a PolicyVersion is approved. It contains the IR plus every pinned asset, and is identified by `bundleHash` (`sha256:…`). |
| **Deployment** | An append-only record that makes a bundle live for `(tenant, environment, policyCode)`. Each deployment has a mode and an `effectiveFrom`. The **active pointer** is the latest effective `CHAMPION` deployment. |
| **Deployment mode** | `CHAMPION` makes the live decision. `CHALLENGER(trafficBasisPoints)` makes the live decision for a deterministic slice of traffic. `SHADOW` is evaluated asynchronously and never affects outcomes. |
| **Variable** | A typed, named value. `INPUT` variables are read from a fact path. `DERIVED` variables are computed by a BEL expression. Variables form a DAG that is checked for cycles at compile time. |
| **Parameter** | A typed, named constant held in a Parameter Set, for example `INDEBTEDNESS_CAP = 200000.00`. Parameters are maker-checked and effective-dated. In BEL they are written `param.NAME`. |
| **Reference List** | A named set of values, for example `UNSECURED_ACCOUNT_TYPES`, written `list.NAME` in BEL. A list held in a bundle is capped at 100,000 entries; anything larger must be supplied as a fact (for example, a negative-list hit flag). |
| **Rule** | `code`, `name`, `scope`, an optional `when` precondition (BEL boolean), a `check` assertion (BEL boolean), an `onFail` result (`FAIL`, `DEVIATION` or `REFER`) with `reasonCode`, an optional `deviationType`, an `onMissing` policy, and optional `outputs`. |
| **Decision Table** | Named `inputs` (BEL), ordered `rows` of input conditions mapped to outputs or an outcome, and a `hitPolicy`: `FIRST`, `UNIQUE`, `PRIORITY` or `COLLECT(SUM, MIN, MAX, COUNT, LIST)`. Checked at compile time for overlaps and gaps. |
| **Scorecard** | `characteristics`, each with bins → points. Produces a `baseScore`, the total, a scaled score, and a reason code for the top N adverse characteristics. |
| **Flow** | An ordered list of **Stages**, each with **Steps** (references to rules, tables or scorecards). Each step has a `scope` and an aggregation. A stage can be marked `KNOCKOUT`, which short-circuits the flow on any `FAIL`. |
| **Scope** | The entity a step runs against: `APPLICATION`, `PARTY` (with a role filter), `GROUP`, `MEMBER`, `HOUSEHOLD`, `COLLATERAL`. Steps with a per-entity scope run once per matching entity. |
| **Aggregation** | How per-entity results roll up. `WORST` is the default. `ALL_MUST_PASS`; `THRESHOLD(count \| percent, status)`, for example "group REFERs if ≥ 20% of members DEVIATE"; `NONE`, which reports per entity only. |
| **Test Case** | Facts, an `asOf` date and the expected outputs, stored with the PolicyVersion. Every test case must pass before the version can be submitted for review. |

### 3.3 Policy-version lifecycle

`DRAFT → IN_REVIEW → APPROVED → ARCHIVED`, with two side transitions: `IN_REVIEW → DRAFT` when the reviewer requests changes, and `DRAFT → DISCARDED`.

- A version is editable **only** while in `DRAFT`. Approval builds and signs the bundle.
- **Segregation of duties:** the reviewer cannot be the author. Two separate tenant settings control approval counts:
  - `minPolicyReviewers`: approvals a PolicyVersion needs. Default 1.
  - `minDeploymentApprovers[environment]`: approvals a Deployment needs in that environment. Defaults: DEV 0, UAT 1, PROD 2.

  A deployment approver can be neither the version's author nor its reviewer.

### 3.4 Deployment lifecycle

`PENDING_APPROVAL → SCHEDULED → ACTIVE → SUPERSEDED`, with `ROLLED_BACK` and `CANCELLED` as side states.

- **Rollback** creates a new deployment that points to a previous bundle. Nothing is ever edited in place.
- **Emergency change:** a deployment can be marked `EXPEDITED`. It needs 2 approvers and creates a mandatory post-implementation review task.

### 3.5 Outcomes

**RuleStatus** is one of `PASS`, `DEVIATION`, `REFER`, `FAIL`, `NOT_APPLICABLE` (the `when` precondition was false), `DATA_MISSING`, `ERROR`.

**`onMissing`** is set per rule to `REFER` (default), `FAIL`, `NOT_APPLICABLE` or `USE_DEFAULT(true|false)`. `USE_DEFAULT` supplies a **Boolean** that stands in for the unknown `when` or `check` result. To default an *input* value, write `coalesce(x, 0)` in a variable instead.

**`onError`** is **not configurable**. An error always yields `ERROR`, which counts as `REFER` and raises a system alert.

The **Decision** (overall outcome) is one of `DECLINE`, `REFER`, `APPROVE_WITH_DEVIATION`, `APPROVE`. Precedence, first match wins:

1. Any `FAIL` → **DECLINE**
2. Otherwise any `REFER`, `DATA_MISSING` or `ERROR` → **REFER**
3. Otherwise any `DEVIATION` that is not covered by a valid `deviationApproval` → **APPROVE_WITH_DEVIATION**
4. Otherwise → **APPROVE**

`NOT_APPLICABLE` is ignored when computing the Decision. `stpEligible` is `true` only when the Decision is `APPROVE` and no rule produced `DEVIATION_APPROVED`.

A deviation that is covered by a valid approval is reported as `DEVIATION_APPROVED`. This is an annotation on the rule result, not an additional RuleStatus. It does not affect the Decision.

### 3.6 Deviations

| Term | Definition |
|---|---|
| **Deviation Type** | A tenant code such as `FOIR_EXCEEDED`, `INDEBTEDNESS_CAP`, `VRM_CATEGORY_C` or `AGE_AT_MATURITY`. |
| **Authority Matrix** | Maps `(deviationType, product line, amount band[, segment])` to a required authority level. Authority levels are tenant-defined and ordered, for example `L1 < L2 < L3 < CREDIT_HEAD < CRO < MD`. Matrices are versioned Shared Assets; **no role hierarchy is ever hard-coded**. |
| **Routing mode** | Tenant setting. `HIGHEST_ONLY` gives one approval at the highest authority required. `EACH` gives one approval per deviation, which can run in parallel. The mode in force is copied into each Authority Matrix version when that version is created, so that replay is exact. |
| **Deviation Approval (fact)** | `{deviationType, ruleCode, entityType, entityRef, approvedValue, approvedBy, approvedLevel, approvalRef, approvedAt, expiresOn}`. `entityType`/`entityRef` identify the entity: for example `MEMBER` plus the partyRef, or `APPLICATION` plus the applicationRef. An approval covers only that entity. |

### 3.7 Decision record

| Term | Definition |
|---|---|
| `decisionId` | UUIDv7. |
| `requestId` | The caller's idempotency key. Unique per tenant for 7 days; repeating it returns the original decision. |
| `applicationRef` | The caller's own identifier for the application, a string of at most 128 characters. Not interpreted by the BRE. |
| `correlationId` | W3C `traceparent`. |
| **Decision record contents** | tenantId, environment, policyCode, bundleHash, engineVersion, deploymentId, mode, asOf, receivedAt, completedAt, latency; the facts (encrypted; masked view for display); the Decision; per-rule, per-entity results (status, reasonCode, key values); deviations with required authority; outputs (for example `eligibleAmount`); reasons (customer-facing codes); stpEligible. |

### 3.7a Policy resolution, pinning and `asOf`

**Resolution.** A request either names `policyCode` explicitly, or the BRE resolves the policy from `(productLine, stage, segment)`:
- The most specific match wins: a policy with an exact segment beats one with no segment.
- **Uniqueness:** for each `(tenant, environment, productLine, stage, segment)` there can be at most one ACTIVE CHAMPION deployment. The registry rejects any deployment that would create two.

**Pinning (grandfathering).** The request field `pinning` is either `LATEST` (the default) or `{ "bundleHash": "sha256:…" }`.
- A pinned bundle is honoured only if it was ACTIVE as CHAMPION in that environment within the tenant's `grandfatherWindowDays` (default 90), measured back from the server's `receivedAt`, never from the client's `asOf`, so a pin cannot be back-dated. Otherwise the BRE returns the problem `pinned-bundle-not-allowed`.
- This lets an application that started under policy v3 finish under v3 when the lender's policy says so. Whether to pin is the caller's choice.

**`asOf`.** If the request omits `asOf`, it is set to `receivedAt`. Date functions evaluate in the tenant time zone (default `Asia/Kolkata`). The resolved `asOf` and time zone are stored in the decision record.

**Phasing note.** Kafka is Phase 1 infrastructure, because it carries the decision log (D6). The async request API on `bre.decision.requested.v1` is a Phase 2 feature. Compact mode needs no Kafka at all.

### 3.8 Reason codes

A **Reason Code** is `{code, internalText, customerText{lang→text}, category (KNOCKOUT | CREDIT | KYC | FRAUD | AFFORDABILITY | COLLATERAL | POLICY), adverse: boolean}`.

**Customer-facing reasons** are produced **only when the Decision is DECLINE**. They are taken from the adverse reason codes of the FAIL results, up to `customerReasonLimit` (default 4), ordered by rule priority. System codes are excluded. **Internal reasons**, meaning every non-PASS reason code, are always returned. A REFER is not a rejection, so it carries no customer reasons.

### 3.9 Fact schema (canonical lending facts v1, abridged)

```
application { applicationRef, productLine, productCode, stage, segment?, channel, branchCode,
              requestedAmount, recommendedAmount?, approvedAmount?, tenureMonths, repaymentFrequency,
              proposedMonthlyInstalment?, emiPerLakh?, purpose, asOf? }
parties[]   { partyRef, principalRef?, role (APPLICANT|CO_APPLICANT|GUARANTOR|PROMOTER|DIRECTOR|MEMBER),
              type (INDIVIDUAL|NON_INDIVIDUAL), dateOfBirth?, gender? [protected], householdRef?,
              proposedAmount?, proposedMonthlyInstalment?,   # member-level split of a group loan
              kyc{status, verifiedAt, method},
              income{monthlyGross?, monthlyNet?, verified, source}, declaredObligations[],
              bureauReports[]{bureau, scoreType, score?, noHit, pulledAt,
                 tradelines[]{lenderRef, accountType, lenderType, ownership, sanctionedAmount, currentBalance, instalmentAmount?,
                              overdueAmount, status, openedOn, closedOn?, reportedOn,
                              dpdHistory[]{month, dpd}}, enquiries[]{date, purpose, amount}},
              screening{sanctionsHit, pepHit, negativeListHit, matchScore?},
              bankStatement?{source(AA|UPLOAD), monthsCovered, avgMonthlyBalance, avgMonthlyCredits,
                             bounceCount, salaryCredits[]},
              ext{} }
group?      { groupRef, type (JLG|SHG), formedOn, cycleNumber, memberPartyRefs[], meetingFrequency,
              centreRef?, villageRiskCategory?, ext{} }
households[]{ householdRef, memberPartyRefs[], annualIncome, monthlyRepaymentObligations, ext{} }
collaterals[]{ collateralRef, type, marketValue, valuationDate, ownerPartyRefs[], ext{} }
business?   { constitution, vintageMonths, sectorCode, gstTurnover12m?, bankingTurnover12m?,
              financials[]{fiscalYear, revenue, ebitda, pat, depreciation, interestExpense,
                           currentAssets, currentLiabilities, totalOutsideLiabilities, tangibleNetWorth,
                           existingDebtService}, ext{} }
portfolio?  { sectorExposure{sectorCode, currentAmount, limitAmount, asOf}, ... }   # supplied by the caller; the BRE never queries it
deviationApprovals[] { see §3.6 }
consent     { consentRef, purposeCode }
```

Data rules for facts:
- All money is a decimal in INR with scale 2. A `currency` field is reserved for later.
- **Raw Aadhaar numbers are prohibited** in facts; send a vault reference or token instead.
- `principalRef` is the caller's stable, pseudonymous ID for the data principal (customer), used to target erasure and crypto-shredding of that person's data (per-data-principal keys). It is optional and must never be a natural identifier such as a PAN or mobile number.
- PAN is accepted only as a token or in encrypted form.
- Fields tagged `protected` (for example `religion`, `caste`, `gender`) cannot be used in rule logic unless a governance override is approved and recorded.

### 3.10 BEL (BRE Expression Language) essentials

| Area | Specification |
|---|---|
| **Types** | `Decimal`, `Integer`, `Boolean`, `Text`, `Date`, `DateTime`, `Period`, `Enum`, `List<T>`, `Record` |
| **Nulls** | Three-valued logic. Any operation or comparison involving a missing value yields **unknown**. If a `check` is unknown, the rule's `onMissing` policy applies. Helpers: `isMissing(x)`, `coalesce(a, b, …)`. |
| **Arithmetic** | Decimal arithmetic. Division uses an internal scale of 10 with HALF_EVEN rounding. `round(x, scale, mode)` rounds explicitly. **Comparisons use the unrounded value unless the author rounds.** |
| **Operators** | `+ - * /` · `= != < <= > >=` · `and or not` · `x in [..]` · `x in list.NAME` · `x between a and b` (inclusive) |
| **Collections** | `list[filter]` (the element is `it`) · `sum(list.field)` · `count(list)` · `min` · `max` · `avg` · `distinctCount(list.field)` · `any(list, cond)` · `all(list, cond)` · `first(list)` |
| **Dates** | `asOf` · `monthsBetween(a, b)` · `daysBetween(a, b)` · `ageInYears(dob, onDate)` · `addMonths(d, n)` · `addDays(d, n)` |
| **Text** | `startsWith` · `endsWith` · `contains` · `matches(re)` (RE2J, linear time) · `upper` · `lower` · `length` |
| **References** | Fact paths (`application.requestedAmount`) · scope variables (`party`, `member`, `group`, `household`, `collateral`) · variables by name · `param.X` · `list.X` |
| **Safety** | No host calls, no reflection, no loops or recursion, no user-defined functions in the MVP. Functions come from a closed registry only. A compile-time cost estimate is made. A runtime **fuel limit** (default 1,000,000 steps per decision) and a list-size cap (default 10,000) both produce `ERROR` when exceeded. |

**BEL additions** (adopted from the Authoring Guide):
- `if c then a else b`. If `c` is unknown, the result is missing.
- List literals `[a, b, c]` usable anywhere, so `min([x, y, z])` works.
- Unary minus.
- `round(x, scale, mode)`. `scale` may be negative (`-3` rounds to thousands). `mode` is one of `HALF_UP`, `HALF_EVEN`, `DOWN`, `UP`, `CEILING`, `FLOOR`.
- Parameters may appear inside unary tests (`<= param.X`) and inside `THRESHOLD(...)`.
- **Scope collections**: `parties`, `members` (parties with role MEMBER, in `group.memberPartyRefs` order), `households`, `collaterals`. A **per-entity derived variable is a virtual field of its entity record**, so a wider scope can aggregate it, for example `sum(members.memberUnsecuredOutstanding)` in a GROUP rule.
- **Result references**: `scorecard.<CODE>.score`, `scorecard.<CODE>.reasons`, `table.<CODE>.<output>`, `rule.<CODE>.<output>`. They are added to the dependency DAG. A reference may point only to an earlier stage, or to an earlier step within the same stage; any other reference is a compile error. Referencing a step skipped by a knockout yields missing.
- Decision tables accept `when` with the same semantics as rules. A scorecard characteristic may carry a `reasonCode`; a bin's own `reasonCode` overrides it.
- Rules, tables, scorecards and variables that read a `protected` field must carry `governance: { overrideRef }`.

**Binding evaluation semantics.** The LLD formalises these and the conformance suite tests them.

| # | Semantics |
|---|---|
| S1 | **Unknown `when`.** If the precondition is unknown, `onMissing` applies (default DATA_MISSING → REFER). It **never** yields NOT_APPLICABLE. |
| S2 | **Empty lists.** `sum`, `count` and `distinctCount` return 0; `any` returns false; `all` returns true; `min`, `max`, `avg` and `first` return missing. **Strict propagation:** if any projected element value is missing, `sum`, `min`, `max`, `avg` and `distinctCount` return missing. |
| S3 | **Filters are fail-closed.** If a filter predicate is unknown for **any** element, the filtered list is **missing**; the element is not silently excluded. To include or exclude elements with missing values, authors write total predicates, for example `isMissing(it.status) or it.status in list.OPEN`. The static check flags filters over optional fields. `any` and `all` use Kleene three-valued logic. *(This deliberately departs from FEEL, which drops such elements and could under-count debt.)* |
| S4 | **Projections through nested lists flatten**: `member.bureauReports.tradelines` is one list. |
| S5 | **Unary tests and missing values.** `-` matches any **present** value. Only `missing` matches a missing value; write `-, missing` to match both. **No matching row:** if any input was missing → `onMissing` (DATA_MISSING by default); if all inputs were present → `ERROR` (a table gap, which the static check should already have reported). |
| S6 | **WORST ordering:** FAIL > ERROR > DATA_MISSING > REFER > DEVIATION > PASS > NOT_APPLICABLE. |
| S7 | **THRESHOLD.** The denominator is the entities whose status is not NOT_APPLICABLE. Only the named status counts, excluding deviations already covered by a valid approval. If the threshold is not met, WORST applies. If it is met, the result is the more severe of the declared result and WORST, so one member's FAIL is never softened to REFER. `percent` takes whole numbers (20 = 20%); every other percentage-like parameter is a fraction (0.50). **ALL_MUST_PASS:** PASS only if every applicable entity PASSes. Any FAIL, REFER or DEVIATION makes it FAIL. If only DATA_MISSING or ERROR remain, they are kept as they are (→ REFER), never converted to a decline. Covered deviations count as PASS in every aggregation. |
| S8 | **Deviation coverage** requires all of: a match on `ruleCode`, `deviationType`, `entityType` and `entityRef`; `asOf ≤ expiresOn`; a current measure no worse than `approvedValue` per `worseWhen` (categorical measures must be equal); and `approvedLevel` ≥ the level now required. Approvals never cover FAIL. |
| S9 | **Division by zero** → ERROR. `measure` is evaluated only when the check fails; an error inside it → ERROR. `outputs` are evaluated whatever the check result; an unknown output is reported as missing and does not change the status. |
| S10 | **Dates.** `monthsBetween` counts completed months and `ageInYears` completed years, using `java.time.Period` semantics: a 29 February birthday is completed on 1 March in a common year. `asOf` is a Date in the tenant time zone. `dpdHistory[].month` is the first day of its month. `financials[].fiscalYear` is the FY end year (Integer). |
| S11 | **Scorecards.** Adverse reasons are ranked by shortfall from the characteristic's maximum points, counting only shortfalls above 0; ties go to declaration order. A value matching no bin → ERROR. |
| S12 | **Knockout.** Steps skipped because of a KNOCKOUT are reported as NOT_APPLICABLE with reason `SYS_SKIPPED_KNOCKOUT`, and so do not affect the Decision. |
| S13 | **Visibility.** A per-entity variable is readable as a scalar in its own scope and in narrower ones. Wider scopes read it through scope collections (see the additions above). |

### 3.11 Deployables and libraries

| Plane | Component | Responsibility |
|---|---|---|
| Library | `bre-engine-core` | IR model, BEL parser, type checker and compiler, evaluator, explainer, static checks. No framework dependencies. One code path for live decisions, simulation, back-testing and studio tests. |
| Library | `bre-fact-model` | Canonical fact schema (JSON Schema plus Java records) and validation. |
| Library | `bre-sdk-java` | Client SDK (REST and gRPC). Optionally embeds the engine for in-process evaluation. |
| Data | `decision-service` | Sync Decision API (REST + gRPC). Loads bundles by hash. Writes to `DecisionLog`. |
| Data | `decision-worker` | Async Kafka requests and bulk decision jobs. |
| Data | `decision-sink` | Kafka → PostgreSQL hot store, object-store WORM archive and analytics feed. |
| Data | `shadow-evaluator` | Consumes `bre.decision.recorded.v1` and re-evaluates against SHADOW bundles. Writes diffs. |
| Control | `studio-service` | Authoring API for policies, assets, tests, reviews and the audit trail. |
| Control | `policy-registry` | Bundle build, sign and store. Deployments, the active pointer, and the distribution endpoint that data-plane pods poll. |
| Control | `simulation-service` | Simulations, back-tests, impact analysis (worker pool). |
| Control | `studio-web` | React + TypeScript rule studio. |
| Control | `tenant-admin-service` | Tenants, cells, quotas, environments, settings. |
| External | OIDC IdP · KMS/HSM · Kafka · PostgreSQL · object store · OTel stack | |

### 3.12 Roles (RBAC)

| Role | May |
|---|---|
| `POLICY_AUTHOR` | Edit DRAFT versions and assets, run tests and simulations. |
| `POLICY_REVIEWER` | Approve a version or request changes. Cannot approve their own work. |
| `DEPLOYMENT_APPROVER` | Approve deployments to PROD, roll back. |
| `RISK_ANALYST` | Run back-tests and impact analyses, read decisions (masked view). |
| `AUDITOR` | Read everything, including the full audit trail. Unmask only with a recorded reason. |
| `OPS_VIEWER` | Dashboards and decision lookup (masked view). |
| `TENANT_ADMIN` | Users, roles, environments and tenant settings. |
| `PLATFORM_ADMIN` | Cells, tenants and quotas (operator side). |

System-client OAuth scopes: `decisions:evaluate`, `decisions:read`, `decision-jobs:submit`, `bundles:read`.

### 3.13 Interfaces

**REST, Decision API:**

| Method | Path | Purpose |
|---|---|---|
| POST | `/v1/decisions` | Synchronous decision |
| GET | `/v1/decisions/{decisionId}` | Fetch a decision |
| GET | `/v1/decisions/{decisionId}/explanation` | Trace, regenerated on demand |
| GET | `/v1/decisions?applicationRef=` | List decisions for an application |
| POST | `/v1/decisions:async` | Asynchronous decision; returns `202 Accepted` (Phase 2) |
| POST | `/v1/decision-jobs` | Submit a bulk decision job |
| GET | `/v1/decision-jobs/{jobId}` | Bulk job status |

**gRPC:** `bre.decision.v1.DecisionService/Evaluate`, `/Get`.

**REST, Studio** (prefix `/v1/studio`): `policies`, `policies/{policyCode}/versions`, `…/versions/{n}/{rules|decision-tables|scorecards|variables|flow|tests}`, `…/versions/{n}:submit`, `:approve`, `:request-changes`, `:simulate`, `parameter-sets`, `reference-lists`, `reason-codes`, `authority-matrices`, `fact-schemas`, `backtests`, `impact-analyses`.

**REST, Registry:** `/v1/deployments` (create, approve, rollback), `/v1/bundles/{bundleHash}` (export), `/v1/bundles:import`, `/v1/active-pointers`.

**Kafka topics** (CloudEvents; partition key `tenantId:applicationRef`):

| Topic | Carries |
|---|---|
| `bre.decision.requested.v1` | Async requests in |
| `bre.decision.completed.v1` | Async results out |
| `bre.decision.recorded.v1` | Every decision; the system of record in Kafka-first mode |
| `bre.deviation.raised.v1` | Deviations raised |
| `bre.deployment.activated.v1` | Deployments activated |
| `bre.policy.approved.v1` | Policy versions approved |
| `bre.studio.audit.v1` | Studio audit trail |
| `<topic>.dlq` | One dead-letter topic per consumer topic |

**Event encoding.**
- `bre.decision.recorded.v1` uses CloudEvents **binary mode with a protobuf payload**, because it is the high-volume system of record.
- Every other topic uses CloudEvents **structured JSON**.
- All schemas are registered in the schema registry, and evolution is backward-compatible within a major version.

**Async ingress by deployment type.**
- **Shared-cell SaaS tenants** never produce to platform Kafka directly. They call `POST /v1/decisions:async`, which returns `202 Accepted` with the `decisionId`. Results arrive by signed webhook (Phase 3) or `GET /v1/decisions/{decisionId}`.
- **Dedicated cells and single-tenant installs** may also produce directly to `bre.decision.requested.v1`, through per-tenant ACLs.

**Errors:** RFC 9457 `application/problem+json`, using stable `type` URIs `https://bre.dev/problems/<slug>` and the codes in the LLD error catalogue.

### 3.14 Policy Source Format (PSF v1)

PSF is the canonical, human-readable form of a PolicyVersion. It is YAML and maps 1:1 to the IR. The studio imports and exports it, and diffs between versions are shown in it. The LLD formalises it as a JSON Schema; the Authoring Guide uses it for every example.

```yaml
policy: MFI_JLG_CREDIT_UW
version: 3
productLines: [MFI_JLG]
stage: CREDIT_UW
factSchema: lending-facts@1.0.0
assets:
  parameterSet: MFI_PARAMS@7
  referenceLists: [UNSECURED_ACCOUNT_TYPES@2, OPEN_TRADELINE_STATUSES@1]
  reasonCodes: CORE_REASONS@5
  authorityMatrix: MFI_DOP@3

variables:
  - name: proposedAmount
    type: Decimal
    expr: coalesce(application.approvedAmount, application.recommendedAmount, application.requestedAmount)
  - name: memberUnsecuredOutstanding        # per-entity variable: one value per MEMBER
    scope: MEMBER
    type: Decimal
    expr: >
      sum(member.bureauReports.tradelines[
            it.accountType in list.UNSECURED_ACCOUNT_TYPES
        and it.status in list.OPEN_TRADELINE_STATUSES].currentBalance)

rules:
  - code: MFI_INDEBTEDNESS_CAP
    name: Member indebtedness within cap
    scope: MEMBER                           # APPLICATION | PARTY | GROUP | MEMBER | HOUSEHOLD | COLLATERAL
    when: true                              # optional precondition; false → NOT_APPLICABLE
    check: memberUnsecuredOutstanding + proposedAmount / count(group.memberPartyRefs) <= param.INDEBTEDNESS_CAP
    onFail:
      status: DEVIATION                     # FAIL | DEVIATION | REFER
      reasonCode: AFF_INDEBTEDNESS_CAP
      deviationType: INDEBTEDNESS_CAP
      measure: memberUnsecuredOutstanding + proposedAmount / count(group.memberPartyRefs)
      worseWhen: HIGHER                     # HIGHER | LOWER; used to match deviationApprovals[]
    onMissing: REFER                        # REFER (default) | FAIL | NOT_APPLICABLE | USE_DEFAULT(true|false)
    outputs:
      totalIndebtedness: memberUnsecuredOutstanding + proposedAmount / count(group.memberPartyRefs)

decisionTables:
  - code: GRP_VRM_CATEGORY
    scope: GROUP
    hitPolicy: FIRST                        # FIRST | UNIQUE | PRIORITY | COLLECT(SUM|MIN|MAX|COUNT|LIST)
    inputs:
      - { name: vrm, expr: group.villageRiskCategory }
    rows:                                   # cells are unary tests: = "C", in [..], < 700, [650..750), -, missing
      - { when: ['in ["A","B"]'], then: { status: PASS } }
      - { when: ['= "C"'],        then: { status: DEVIATION, deviationType: VRM_CATEGORY_C, reasonCode: POL_VRM_C } }
      - { when: ['in ["D","E"]'], then: { status: FAIL, reasonCode: POL_VRM_DE } }
      - { when: ['missing'],      then: { status: REFER, reasonCode: DATA_VRM_MISSING } }
      - { when: ['-'],            then: { status: REFER, reasonCode: POL_VRM_UNKNOWN } }

scorecards:
  - code: MFI_APP_SCORE_V1
    scope: MEMBER
    baseScore: 300
    characteristics:
      - name: groupCycle
        expr: group.cycleNumber
        bins:
          - { when: '= 1',     points: 20 }
          - { when: '[2..3]',  points: 45 }
          - { when: '>= 4',    points: 70 }
          - { when: 'missing', points: 0, reasonCode: SC_CYCLE_MISSING }
    adverseReasons: 3                       # report the top N lowest-scoring characteristics

flow:
  stages:
    - name: KNOCKOUT
      knockout: true                        # any FAIL here stops the flow → DECLINE
      steps:
        - { ref: SCREENING_HIT, roles: [MEMBER], aggregate: WORST }
    - name: ELIGIBILITY
      steps:
        - { ref: GRP_VRM_CATEGORY }
        - { ref: MFI_INDEBTEDNESS_CAP, aggregate: "THRESHOLD(percent >= 20, DEVIATION) -> REFER" }   # else WORST
    - name: SCORING
      steps:
        - { ref: MFI_APP_SCORE_V1, aggregate: NONE }

tests:
  - name: VRM C raises a deviation
    asOf: 2026-10-01
    facts: { $ref: fixtures/jlg_vrm_c.json }
    expect:
      decision: APPROVE_WITH_DEVIATION
      rules: { GRP_VRM_CATEGORY: DEVIATION }
```

**Unary-test syntax** (decision-table cells and scorecard bins), as in FEEL:
- comparisons: `< x`, `<= x`, `> x`, `>= x`, `= x`, `!= x`
- ranges: `[a..b]` inclusive; `(a..b)` exclusive; `[a..b)` and `(a..b]` half-open
- membership: `in [..]`, `in list.NAME`
- `-` matches any **present** value; `missing` matches a missing value; `-, missing` matches both (S5)
- several tests in one cell are joined with `,`, meaning OR

---

## 4. NFR design point (per region, per cell unless stated)

| NFR | Target |
|---|---|
| NFR-PERF-01 | Server-side latency for a 100-rule policy with ≤ 64 KB of facts, **including the durable decision write**: p50 ≤ 20 ms, p99 ≤ 100 ms |
| NFR-PERF-02 | Large case (1,000 rules, ≤ 1 MB facts, e.g. a 40-member JLG group): p99 ≤ 250 ms |
| NFR-SCALE-01 | 5,000 decisions/s sustained and 10,000/s burst per cell. Throughput scales linearly by adding pods, then by adding cells. Planning assumption: ≥ 800 decisions/s per 2-vCPU pod, to be confirmed by benchmark. |
| NFR-SCALE-02 | Bulk jobs: ≥ 1,000,000 decisions per hour per cell |
| NFR-SCALE-03 | Back-test of 1,000,000 historical decisions in ≤ 30 min |
| NFR-AVAIL-01 | Decision API 99.95% monthly with one region; 99.99% with two-region active-active. Studio 99.5%. |
| NFR-DR-01 | **RPO 0** for acknowledged decisions on any broker, node or availability-zone failure. **Full-region loss:** RPO ≤ 5 s (asynchronous cross-region replication), or RPO 0 in the optional **Sync-DR mode** (a stretched cluster, at a higher latency cost). Data-plane RTO ≤ 1 min (active-active). Control-plane RTO ≤ 30 min. |
| NFR-OPS-01 | Activation and rollback converge on every pod within ≤ 30 s at p99 |
| NFR-COMP-01 | Hot store holds decisions for 90 days (configurable). The WORM archive holds them for 10 years by default (configurable per tenant). Security logs are kept ≥ 180 days in India, per the CERT-In directions. |

---

## 5. Draft 2 document set

| File | Content |
|---|---|
| `00_Decisions_and_Canonical_Terms.md` | This file |
| `01_PRD.md` | Product requirements |
| `02_Architecture.md` | HLD and system design, scalability, security, DR, ADRs |
| `03_LLD.md` | Schema, BEL grammar, IR, APIs, events, algorithms, error catalogue |
| `04_Rule_Authoring_Guide.md` | Worked examples for business analysts |
