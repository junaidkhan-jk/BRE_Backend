# Compliance control register

**What this is for:** PRD §12.1 exit criterion 6 and M6-07 — *"Compliance sign-off on the regulatory
parameters and the PRD §10 controls."* Signed by a compliance officer, which means this document has
to be checkable by one rather than taken on trust.

So every row names **where the control is implemented** and **the test that proves it**, and
`ComplianceRegisterTest` asserts in CI that every path named below actually exists — it does the
same for the other documents whose claims are anchored to code (`docs/USABILITY-PROTOCOL.md`,
`docs/PARALLEL-RUN.md`, `docs/THREAT-MODEL.md`, `docs/runbooks/dr-drill.md`). A register whose references have rotted is worse than none: it reads like evidence and
asserts things that are no longer true. The test is the only thing that stops that happening
silently.

**Status vocabulary.** `IMPLEMENTED` means code plus a named test. `PARTIAL` means the control works
and a stated part of it does not. `PHASE 2` / `PHASE 3` means the PRD itself defers it, with the
priority quoted. **`GAP`** means it is in Phase 1 scope and is not built — read those first.

---

## Read these first

### GAP — legal hold (FR-AUD-009, Must, Phase 1)

§10.4 (PMLA/KYC retention) and §10.6 (DPDP erasure) both rest on legal hold, and **there is no legal
hold.** `bre_ctl.tenants` carries `hot_retention_days` and `facts_retention_days` with CHECK
constraints, so retention is *configurable*; what does not exist is a per-application hold, or the
purge job the hold would override. AC-AUD-009 — *"a decision under legal hold survives a retention
purge, and one without a hold does not"* — cannot be run.

Consequences to state plainly to compliance: nothing is being deleted today, because no purge runs,
so there is no risk of destroying something under hold. The risk is the other way — data is retained
past its stated retention, which is a DPDP storage-limitation exposure rather than a PMLA one. The
order to build it in matters: the purge job must not ship before the hold, or the first purge is the
incident.

### Stricter than specified — consent (FR-GOV-007, Must, Phase 1)

FR-GOV-007 says a tenant *can make* `consent.consentRef` and `purposeCode` mandatory per stage.
`lending-facts@1.0.0` lists `consent` in its top-level `required`, so they are **always** mandatory
and a tenant cannot switch them off. That satisfies §10.6's lawful-processing requirement more than
the requirement asks, and it is a deviation either way: compliance should record that it is content
with the stricter behaviour, because reverting to per-tenant optionality later would be a
loosening and would need its own approval.

### The regulatory parameters, for confirmation

Every value is **illustrative** and needs confirming against the current RBI Master Direction. These
two are the ones §10.2 makes regulatory; the rest of `MFI_PARAMS@1` is a plausible lender's own
credit policy and is not compliance's to own.

| Parameter | Value in the reference set | Source | Compliance owner confirms |
|---|---|---|---|
| `MFI_HH_ANNUAL_INCOME_MAX` | ₹300,000.00 | RBI Master Direction on microfinance loans, 2022 — household income ceiling | the ceiling and whether it has been revised |
| `MFI_HH_OBLIGATION_CAP_PCT` | 50.00 (percent of monthly household income) | same Direction — repayment-obligations cap | the percentage, and the definition of "obligations" the lender applies |

The PRD names these `MFI_HH_ANNUAL_INCOME_CEILING` and `MFI_HH_OBLIGATION_CAP_PCT`. The first is
spelled `..._MAX` in the reference set; the names differ and the meaning does not. Left as it is
rather than renamed, because a parameter name is part of a signed bundle's content address and
renaming one is a new policy version — but it is a thing to notice when reading the PRD alongside the
policy.

**`MFI_HH_OBLIGATION_CAP_PCT` was added for this register.** §10.2 sets two regulatory limits and the
reference policy implemented only the income ceiling. The obligation cap was named in the PRD,
acknowledged in the reference policy's own comment as regulatory, and absent from the parameter set
and the rules alike — while `household.monthlyRepaymentObligations` sat in the fact schema, read by
nothing. Since the reference policy is the acceptance suite, the suite did not cover one of the two
caps the MFI vertical is built on. `MFI_HH_OBLIGATION_CAP` and a fixture that breaches it now exist.

In a real deployment these live in `bre_ctl.parameters`, where `regulatory = true` forces both
`source_ref` and `next_review_on` by CHECK constraint. A resolved asset file has nowhere to put
those, which is why this table does.

---

## §10.1 Fair Practices Code — reasons for rejection

| Requirement | Status | Implementation | Proof |
|---|---|---|---|
| Every DECLINE carries customer-facing adverse reason codes, capped | IMPLEMENTED | `backend/libs/bre-engine-core/src/main/java/dev/bre/engine/decide/Reasons.java` — `customerReasons` returns at most `settings.customerReasonLimit`, in disclosure order (priority, stage, step) | `backend/libs/bre-engine-core/src/test/java/dev/bre/engine/decide/ReasonsAndApprovalsTest.java` |
| A REFER carries internal reasons only, because it is not a rejection | IMPLEMENTED | same — `decision != DECLINE` yields an empty customer list | same, `§4.10 internal reasons` |
| Reason codes are versioned and reviewed in a catalogue | IMPLEMENTED | `backend/services/policy-registry/src/main/java/dev/bre/registry/asset/AssetRepository.java`; immutable `asset_versions` with a review trail | `backend/services/policy-registry/src/test/java/dev/bre/registry/SharedAssetLifecycleTest.java` |
| The dossier proves which reasons applied | PARTIAL | the decision record stores every rule outcome and its reason code, and replay regenerates the explanation (`ExplanationController`) | `backend/services/decision-service/src/test/java/dev/bre/decision/explain/ReplayServiceTest.java` — **the assembled dossier document (FR-AUD-011) is not built; the data behind it is** |

## §10.2 RBI microfinance framework

| Requirement | Status | Implementation | Proof |
|---|---|---|---|
| `HOUSEHOLD` scope and household facts | IMPLEMENTED | `backend/libs/bre-engine-core/src/main/java/dev/bre/engine/decide/FlowEvaluator.java` runs a HOUSEHOLD component once per household | `backend/tests/engine-conformance/vectors/S6-A-aggregation.json` |
| Household income ceiling | IMPLEMENTED | `reference-policy/mfi-jlg-credit-uw.psf.yaml` — `MFI_HH_INCOME_CEILING` | `backend/tests/engine-conformance/src/test/java/dev/bre/conformance/ReferencePolicyAcceptanceTest.java` |
| Obligations capped as a share of household income | IMPLEMENTED | same — `MFI_HH_OBLIGATION_CAP`. Written as `obligations * 1200 <= annualIncome * pct` so a zero or missing income fails or refers rather than dividing by zero | same, `householdObligationCapDeclines` — one household of five breaches it and the application declines, which is what makes `WORST` the right aggregate for a regulatory cap |
| Board-approved household-income assessment policy | NOT A PRODUCT CONTROL | the lender's own board policy. The product carries the parameters and the audit trail of changes to them | — |
| Regulatory parameters tagged `REGULATORY` with source and review date | IMPLEMENTED | `backend/services/policy-registry/src/main/resources/db/migration/V2__bre_ctl_policies_and_shared_assets.sql` — `CHECK (NOT regulatory OR (source_ref IS NOT NULL AND next_review_on IS NOT NULL))` | `backend/services/policy-registry/src/test/java/dev/bre/registry/SharedAssetLifecycleTest.java` |
| Overdue regulatory reviews alert the compliance role | **PARTIAL** | the `next_review_on` date is recorded and constrained; **no alert or dashboard surfaces an overdue one** (AC-GOV-005's second half) | — |

## §10.3 RBI digital lending guidelines

| Requirement | Status | Implementation | Proof |
|---|---|---|---|
| An auditable decision record with replay | IMPLEMENTED | `backend/services/decision-service/src/main/java/dev/bre/decision/log/OutboxDecisionLog.java`; durable write before the response (D6) | `backend/services/decision-service/src/test/java/dev/bre/decision/db/SealedFactsStoreTest.java` |
| Replay reproduces the decision exactly | IMPLEMENTED | `backend/services/decision-service/src/main/java/dev/bre/decision/explain/ReplayService.java` — pins the recorded `bundleHash` and `asOf`, and **compares** rather than presenting the replay as what happened | `backend/services/decision-service/src/test/java/dev/bre/decision/explain/ReplayServiceTest.java` |
| Facts held only as needed, with consent references | IMPLEMENTED | `bre.decision-log.store-facts` is **off by default**; `consent` is required by the schema | `backend/libs/bre-fact-model/src/test/java/dev/bre/facts/FactSchemaTest.java` |
| Storage in India | NOT A PRODUCT CONTROL | a deployment choice (BD4). `docs/EXTERNAL-CALLS.md` names every external dependency a deployment has to place | — |
| Explanation and reasons available for grievance redressal | IMPLEMENTED | `GET /v1/decisions/{id}/explanation`, masked per §9.4 | `backend/services/decision-service/src/test/java/dev/bre/decision/explain/ReplayServiceTest.java` |

## §10.4 KYC and PMLA record retention

| Requirement | Status | Implementation | Proof |
|---|---|---|---|
| Retention configurable per tenant | IMPLEMENTED | `backend/services/policy-registry/src/main/resources/db/migration/V1__bre_ctl_schema_roles_and_platform.sql` — `hot_retention_days`, `facts_retention_days`, with `facts <= hot` enforced | `backend/services/policy-registry/src/test/java/dev/bre/registry/db/BreCtlMigrationTest.java` |
| Long-term archive | IMPLEMENTED | `backend/services/decision-service/src/main/java/dev/bre/decision/archive/ParquetDecisionArchive.java` — partitioned tenant/date/hour, refuses to overwrite an existing object | `backend/services/decision-service/src/test/java/dev/bre/decision/archive/ParquetDecisionArchiveTest.java` |
| **Legal hold per application** | **GAP** | — | — |
| Purge after retention unless held | **GAP** | — | — |

## §10.5 Aadhaar handling

| Requirement | Status | Implementation | Proof |
|---|---|---|---|
| The fact schema prohibits raw Aadhaar numbers | IMPLEMENTED | `backend/libs/bre-fact-model/src/main/java/dev/bre/facts/ProhibitedData.java` — pattern plus **Verhoeff checksum**, so a 12-digit value that is not a valid Aadhaar is not a false positive | `backend/libs/bre-fact-model/src/test/java/dev/bre/facts/ProhibitedDataTest.java` |
| The scan covers every string, including `ext` | IMPLEMENTED | same; `ext` is explicitly in scope, which is where an unvalidated identifier actually ends up | same |
| Rule logic may not read an `aadhaar`-tagged field | IMPLEMENTED | `backend/libs/bre-engine-core/src/main/java/dev/bre/engine/bel/check/TypeChecker.java` — `Diagnostic.AADHAAR_FIELD` at compile time, so a policy that reads one cannot be bundled | `backend/libs/bre-fact-model/src/test/java/dev/bre/facts/TypeCheckerAgainstSchemaTest.java` |
| KYC facts carry status, method and a vault reference only | IMPLEMENTED | `backend/libs/bre-fact-model/src/main/resources/dev/bre/facts/lending-facts-1.0.0.schema.json` — the `kyc` block has no number field to put one in | `backend/libs/bre-fact-model/src/test/java/dev/bre/facts/FactSchemaTest.java` |

## §10.6 DPDP 2023

| Principle | Status | Implementation | Proof |
|---|---|---|---|
| Lawful processing with consent | IMPLEMENTED, **stricter than specified** — see above | `consent.consentRef` and `purposeCode` are in the schema's top-level `required` | `backend/libs/bre-fact-model/src/test/java/dev/bre/facts/FactSchemaTest.java` |
| Purpose limitation and minimisation | PARTIAL | the schema declares a group and tags per field, and `store-facts` is off by default | `backend/libs/bre-fact-model/src/test/java/dev/bre/facts/FactSchemaTest.java` — the unused-field report (FR-GOV-008) is **Phase 3, Could** |
| Retention limited to purpose | **GAP** | retention is configurable; nothing enforces it — see §10.4 | — |
| Erasure | IMPLEMENTED for the mechanism | `backend/services/decision-service/src/main/java/dev/bre/decision/crypto/JdbcKeyStores.java` — destroying a data principal's key makes their identifying fields unreadable in the hot store, the archive and backups alike, while the decision record stays auditable | `backend/services/decision-service/src/test/java/dev/bre/decision/db/SealedFactsStoreTest.java`, and `devstack/dr-drill.sh` step 7 demonstrates the same mechanism from the backup side |
| Erasure *handling* — requests recorded, holds take precedence | PHASE 2 (FR-GOV-009, Should) | `backend/services/decision-service/src/main/java/dev/bre/decision/api/ErasureController.java` performs the shred; the request log and hold precedence are not built | — |
| Security safeguards | IMPLEMENTED | §9.2 envelope encryption; `docs/THREAT-MODEL.md` states the boundaries and the gaps | `backend/services/decision-service/src/test/java/dev/bre/decision/crypto/FactsSealerTest.java` |
| Breach notification | NOT A PRODUCT CONTROL | the lender is the data fiduciary. `docs/runbooks/escalation.md` carries the severity classes and triggers; the notification clock is contractual | — |
| Processor role | NOT A PRODUCT CONTROL | a DPA in the commercial pack | — |

## §10.7 RBI IT governance and outsourcing

| Area | Status | Implementation | Proof |
|---|---|---|---|
| Maker-checker on every policy and parameter change | IMPLEMENTED | `backend/services/policy-registry/src/main/resources/db/migration/V7__bre_ctl_locking_rls_and_grants.sql` — enforced by database trigger, not application code, so it holds against any client | `backend/services/policy-registry/src/test/java/dev/bre/registry/PolicyLifecycleTest.java` |
| Segregation of duties | IMPLEMENTED | `tg_policy_review_sod`, `tg_asset_review_sod`, `tg_deployment_approval_sod` — an author cannot approve their own work | same, and `backend/services/policy-registry/src/test/java/dev/bre/registry/SharedAssetLifecycleTest.java` |
| Immutable, tamper-evident audit trail | IMPLEMENTED | `backend/services/policy-registry/src/main/java/dev/bre/registry/audit/AuditLog.java` — a hash chain per tenant, with `tg_audit_chain` maintaining the head | `backend/services/policy-registry/src/test/java/dev/bre/registry/AuditCoverageTest.java` |
| Every mutating operation is audited | IMPLEMENTED | — | `backend/services/policy-registry/src/test/java/dev/bre/registry/AuditCoverageTest.java` enumerates the endpoints and fails if one writes without an audit entry |
| RBAC | IMPLEMENTED | `backend/services/policy-registry/src/main/resources/db/migration/V11__ownership_rls_and_login_lookup.sql` — row-level security per tenant, fail-closed when no tenant scope is set | `backend/services/policy-registry/src/test/java/dev/bre/registry/db/BreCtlMigrationTest.java` |
| MFA for approver roles | **GAP** | OIDC sign-in exists (`backend/services/policy-registry/src/main/java/dev/bre/registry/auth/`), and whether the provider enforces MFA is the provider's setting. **Nothing in the product requires it for an approver** | `backend/services/policy-registry/src/test/java/dev/bre/registry/OidcSignInTest.java` |
| Access reviews | PHASE 2 (FR-GOV-006, Should) | — | — |
| Business continuity | PARTIAL | active-active is designed (§10.2 of the architecture); the restore drill runs and is evidenced | `devstack/dr-drill.sh`, `docs/runbooks/dr-drill.md` — **region failover is untested; it needs a second region** |
| Right to audit, exit support, sub-processor disclosure | NOT A PRODUCT CONTROL | contractual | — |

## §10.8 CERT-In directions

| Requirement | Status | Implementation | Proof |
|---|---|---|---|
| Incident reporting within the prescribed window | PARTIAL | `docs/runbooks/escalation.md` has the severity classes and five mechanical escalation triggers; **the rota's names and the reporting clock are marked TO AGREE** and are the lender's to set | — |
| ICT logs retained ≥ 180 days in India | NOT A PRODUCT CONTROL | a deployment choice (BD4). The application emits structured logs and §9.6 keeps fact values out of them, which is what makes a 180-day retention safe to configure | `backend/tests/engine-conformance/src/test/java/dev/bre/conformance/PiiLogGuardTest.java` |

## §10.9 Model-risk governance

| Requirement | Status | Implementation | Proof |
|---|---|---|---|
| The version and parameters of every model are pinned in the bundle and recorded per decision | IMPLEMENTED | a scorecard's bins and points are compiled into the IR and resolved into the bundle hash; the decision record stores that hash | `backend/services/decision-service/src/test/java/dev/bre/decision/db/SealedFactsStoreTest.java`, `backend/tests/engine-conformance/vectors/S11-scorecards.json` |
| Model register | PHASE 2 (FR-GOV-002, Should) | — | — |
| PROD blocked for a scorecard without a current validation | PHASE 2 (with the register) | — | — |
| Model monitoring (PSI, Gini/KS) | PHASE 3 (FR-GOV-003, Could) | — | — |

## §10.10 Fair lending and protected attributes

| Requirement | Status | Implementation | Proof |
|---|---|---|---|
| Fields tagged `protected` are locked from rule logic | IMPLEMENTED | `backend/libs/bre-engine-core/src/main/java/dev/bre/engine/bel/check/TypeChecker.java` — `Diagnostic.PROTECTED_FIELD` at **compile time**, so a policy reading one cannot be bundled and the control cannot be bypassed at run time | `backend/libs/bre-fact-model/src/test/java/dev/bre/facts/TypeCheckerAgainstSchemaTest.java` |
| Governance overrides are explicit, approved, time-bound | IMPLEMENTED | `backend/services/policy-registry/src/main/resources/db/migration/V2__bre_ctl_policies_and_shared_assets.sql` — `governance_overrides` with approvals | `backend/services/policy-registry/src/test/java/dev/bre/registry/SharedAssetLifecycleTest.java` |
| Static check CHK-006 (protected field read without an override) | **NOT WIRED** | the type checker enforces it; the policy-level static check CHK-006 reports "needs the fact schema's protected tags resolved per component". `bre psf lint` lists it as not checked by this build | run `bre psf lint` on any policy: the gap is printed |
| Fair-lending analysis | PHASE 3 (FR-GOV-010, Could) | — | — |

## §10.11 Priority-sector lending

| Requirement | Status | Implementation | Proof |
|---|---|---|---|
| `pslCategory` as a computed output from a decision table | **GAP** | decision tables with computed outputs work (X5's mechanism exists); **no `pslCategory` output is defined in the reference policy** | — |

---

## What a compliance officer is being asked to sign

Not "the platform is compliant" — a platform is not compliant, a lender is. Specifically:

1. **The two regulatory parameter values**, or corrections to them, with a source reference and a
   next-review date for each.
2. **The `IMPLEMENTED` rows**, as adequate for the control named — having followed the test
   references, or having had somebody follow them.
3. **The `GAP` rows as known and accepted for the pilot**, with a date by which each is closed.
   Legal hold is the one to argue about: everything else on the list either defers by PRD priority
   or is a deployment choice.
4. **The stricter-than-specified consent behaviour**, as acceptable.

What this register deliberately does not do is grade itself. There is no percentage at the bottom,
because a control register with a score invites the score to be managed.
