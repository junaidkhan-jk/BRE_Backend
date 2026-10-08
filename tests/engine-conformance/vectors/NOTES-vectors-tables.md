# Open questions — S5 / T / S6 / A / S7 vectors

Transcribed from LLD §13 (`BRE_PRD/draft2/03_LLD.md:3934-4026`). Each entry is a
place where the LLD does not fix something the vector needed, with the line
consulted and the choice made.

## Format-level, affects every family here

### 1. `expect.steps[].id` vs `.ref`

The README's policy-outcome example writes `"steps": [ { "id": "…", "status": … } ]`
(`README.md:90`), but the IR calls a flow step's component pointer `ref`
(`03_LLD.md:404`) and the IR `testCase.expect.entities[]` also uses `ref`
(`03_LLD.md:432`). **Chose `id`**, because the README is the contract for *these*
files, and used the component `code` as its value. If the loader is written to
`ref`, every `steps` assertion in `S6-A-aggregation.json` and `S7-threshold.json`
needs the one-word rename.

### 2. `expect.outputs` for T-03

The README's value table (`README.md:53-63`) covers `expect.value` only, and its
policy-outcome list (`README.md:87-91`) has no slot for a component's named
output — but T-03 asserts a `points` value, not just a status
(`03_LLD.md:3984`). **Chose** `"expect": { "status": "PASS", "outputs": { "points": { "int": 30 } } }`,
borrowing the key name from the IR's `testCase.expect.outputs`
(`03_LLD.md:437`) and the typed-value encoding from `README.md:53`.

### 3. `params` value encoding (S7-06)

`README.md:21` says `params` are "bound under the `param.` namespace" and
`03_LLD.md:3944` shows only `"params": {}`. No encoding is fixed. **Chose** the
same typed-value envelope as `expect.value`, i.e.
`"params": { "GROUP_DEVIATION_SHARE_REFER_PCT": { "int": 20 } }`, since §13's
row calls it an Integer whole-number percent (`03_LLD.md:3997`) and a bare JSON
`20` would not distinguish Integer from Decimal.

### 4. Whether a `policy` fragment must carry a `flow`

`L-three-valued-logic.json` (L-03) and `D-decimal.json` (D-07) give only a
`rules` array with no `flow`, so the loader evidently synthesises a trivial flow.
But an `aggregate` has nowhere to live except a flow step (`03_LLD.md:404`).
**Chose**: S5 and T fragments carry only `decisionTables` and assert a bare
`status`/`reasonCode`; S6, A and S7 fragments carry `rules`/`decisionTables`
**and** a one-stage, one-step `flow` holding the `aggregate`, and assert `steps`.

### 5. Authority resolution for the DEVIATION entities (S6-01a, A-01b, S7-01..06)

`03_LLD.md:1293-1298` runs coverage **and authority** per entity before
aggregation, for every entity whose raw status is DEVIATION, and
`resolveAuthority` reads the Authority Matrix — a versioned shared asset
referenced by `assets.authorityMatrix` (`03_LLD.md:155`). **The vector format has
no channel for the matrix rows**: `README.md:15-27` offers `params` and `lists`
only. Read literally, a matrix-less fragment makes `level == none`, so every
DEVIATION entity would become `REFER / DEV_NO_AUTHORITY` and S7-02, S7-03 and
S7-06 could never return DEVIATION as §13 says they do.
**Chose**: write the fragments with no `assets`, and treat authority resolution
as supplying a level that the vector's deviations satisfy — i.e. assert the §4.7
aggregation outcome §13 states. The runner needs either a matrix channel in the
vector format or a documented "no matrix declared ⇒ authority not resolved"
rule; this is the one item here that will otherwise make correct engines fail.
(It also bites S7-03, whose approval must out-rank the required level:
`03_LLD.md:1312`.)

## Per-vector

### S5-04 — statuses on the two gap rows

§13 writes the rows as `[in ["A","B"]]` and `[= "C"]` with no outputs
(`03_LLD.md:3980`). **Chose** the statuses those same two rows carry in the §2.3
example table `GRP_VRM_CATEGORY` (PASS, and DEVIATION/`VRM_CATEGORY_C`/`POL_VRM_C`
— `03_LLD.md:540-541`), rather than invent an output-only table. No row matches,
so the choice cannot affect the asserted `EVAL_TABLE_NO_MATCH`.

### S5-04 / T-01 — whether a decision accompanies the ERROR

D-07's §13 row names a decision explicitly ("ERROR, `EVAL_DIV_ZERO`, decision
REFER") and `D-decimal.json` asserts it. S5-04's and T-01's rows name no
decision. **Chose** to assert `status` + `error` only, and not add a `decision`
key the table row does not state.

### T-02 — how three rows of different severity come to match at once

§13 says only "rows matched: PASS, DEVIATION, REFER" (`03_LLD.md:3983`).
**Chose** one input (`group.villageRiskCategory` = "A") and three overlapping
cells — `-`, `in ["A","B"]`, `= "A"` — so all three match. The severity order
asserted is §4.7's (`03_LLD.md:1245`).

### T-03 — the shape of the COLLECT table

§13 says "`points` outputs 10 and 20 match" (`03_LLD.md:3984`). **Chose** an
output-only table (no `status` on any row, per the §2.2 constraint at
`03_LLD.md:450`) over `group.cycleNumber`, rows `>= 1` → 10 and `>= 2` → 20, with
input 3 matching both and input 0 matching neither. The empty case relies on
`03_LLD.md:1222` scoping the `EVAL_TABLE_NO_MATCH` rule to non-COLLECT tables.

### S6-01b — how one entity reaches ERROR

§13 gives the statuses `[ERROR, FAIL]` but not their cause (`03_LLD.md:3985`).
**Chose** a rule whose `check` divides by `member.proposedAmount`, with that
amount 0 for the first member (`EVAL_DIV_ZERO`, per D-07) and a genuinely failing
ratio for the second.

### S6-01c, A-01a, S7-04 — how an entity reaches NOT_APPLICABLE

**Chose** `when: "member.proposedAmount > 0"` on the component with
`proposedAmount` set to `"0"` for those members. Note this must be *present* and
zero: an absent `proposedAmount` makes `when` unknown, which §4.6 routes to
`onMissing` and explicitly never to NOT_APPLICABLE (`03_LLD.md:1183`, S1-01).

### S7-05 — FAIL and DEVIATION from one component

A rule has a single `onFail.status` (`03_LLD.md:252`), so one rule cannot emit
both. **Chose** a MEMBER-scope decision table `MFI_INCOME_BAND` with a FAIL row,
a DEVIATION row and a PASS row. Consequence: §4.9's coverage predicate requires
`a.ruleCode == r.ruleCode` (`03_LLD.md:1301`) — for a decision table the LLD
never says what `ruleCode` is. Not load-bearing for S7-05 (no approvals), but it
is unresolved for any future table-sourced deviation.

### S7-03 — the approval fact

Shape taken verbatim from `00_Decisions_and_Canonical_Terms.md:147`:
`{deviationType, ruleCode, entityType, entityRef, approvedValue, approvedBy,
approvedLevel, approvalRef, approvedAt, expiresOn}`. Two fields are not fixed
anywhere: **`approvedAt`** — written as a plain date `"2026-09-28"`, though
"At" hints at a date-time; and **`approvedBy`** / **`approvalRef`** formats —
written as `"U-0007"` and `"APR-0001"`. None of the three participates in the
coverage predicate (`03_LLD.md:1300-1313`), so they cannot change the result.
`approvedValue` is `"4000"` with `worseWhen: LOWER` and measure 5000, which
satisfies `measure >= approvedValue`.

### MEMBER entity facts

`03_LLD.md:1156` enumerates MEMBER entities from "`members`", and
`03_LLD.md:753` defines `members` as "the parties with role MEMBER, in
`group.memberPartyRefs` order". There is no `members[]` root in the fact schema
(`00_Decisions_and_Canonical_Terms.md:181`). **Chose** to write every S6/A/S7
fact set as a `group.memberPartyRefs` list plus a matching `parties[]` of
`role: "MEMBER"` records, in the same order.
