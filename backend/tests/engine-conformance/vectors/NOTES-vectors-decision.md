# Open questions from transcribing LLD §13 rows S8, P, S10, S11, S12, F, C, E

Every entry is something the LLD does not fix, or fixes only partially, that the
transcription had to settle. The vector carries the choice recorded here.

---

## 1. `expect.steps[]` step identity: `id` vs `ref` — all policy vectors

**Consulted:** `README.md:90` (`"steps": [ { "id": "…", "status": "NOT_APPLICABLE" } ]`)
vs `03_LLD.md:2783` and `03_LLD.md:446` (`flow.stages[].steps[].ref`, and the §6.2
response step object `{ stage, ref, kind, aggregate, status, entities }`).

The README's expect contract names the field `id`; everywhere in the LLD the
identity of a flow step is `ref`. **Chosen: `id`**, because the README is the
loader's contract and the already-written `S6-A-aggregation.json`, `S7-threshold.json`
files use `id` too. The policy IR fragments still use the real IR name `ref` inside
`flow.stages[].steps[]`, so a vector contains both spellings for the same thing.
Worth reconciling in the README before the runner is written.

## 2. `expect.steps[].entities[]` — field names not in the README

**Consulted:** `03_LLD.md:2804-2811` (§6.2 response entities), `03_LLD.md:2913-2916`
(§4.12 Explanation entities), `03_LLD.md:3071` (gRPC `string annotation = 5; // "" or "DEVIATION_APPROVED"`).

The README fixes `expect.value`, `status`, `reasonCode`, `decision` and `steps`, but
no per-entity shape, and S8/P-04/E need one. **Chosen:** the §6.2 + §4.12 vocabulary
verbatim — `entityType`, `entityRef`, `status`, `effectiveStatus`, `reasonCode`,
`deviationType`, `measure`, `worseWhen`, `requiredLevel`, `annotation`, `outputs`.

## 3. The approval reference carried by a `DEVIATION_APPROVED` annotation

**Consulted:** `03_LLD.md:1299` — `annotation DEVIATION_APPROVED(approvalRef = a.approvalRef)`;
`03_LLD.md:3071` models `annotation` as a bare string with no payload.

§4.9 says the annotation carries the `approvalRef`; the response and proto schemas show
no field for it. **Chosen:** `"annotation": "DEVIATION_APPROVED"` plus a sibling
`"approvalRef"` on the entity (S8-01, S8-07a, E-02). If the response schema settles on
embedding it elsewhere, these three vectors need the key moved.

## 4. Absence of an annotation is asserted indirectly — S8-02…S8-05, S8-07b

The not-covered vectors assert `effectiveStatus: "DEVIATION"` and `requiredLevel` and
simply omit `annotation`, rather than asserting an empty annotation. §4.9 makes the two
branches mutually exclusive (covered → PASS + annotation; else → DEVIATION +
requiredLevel), so `effectiveStatus` pins the branch. No vector asserts
`annotation == ""`.

## 5. Inline authority-matrix shape — S8, P, E

**Consulted:** `03_LLD.md:688` (`"authorityMatrix": { "code", "version", "levels": [], "entries": [] }`
— contents elided in the bundle example), `03_LLD.md:1322` (entries are
`(deviationType, productLine | '*', segment | '*', amountAbove, amountUpTo, level)`),
`03_LLD.md:1686-1731` (the `authority_matrix_meta` / `authority_levels` / `authority_entries` DDL).

The LLD never shows a populated `levels`/`entries` array in JSON. **Chosen:** camelCase
of the DDL columns — `levels: [{ levelCode, rank }]`,
`entries: [{ entryNo, deviationType, productLine, segment, amountAbove, amountUpTo, levelCode }]`,
plus `routingMode` from `authority_matrix_meta.routing_mode` (which the §6.2 response
echoes as `requiredApprovals[].routingMode`). `null` bounds are kept as explicit JSON
nulls because §1322 gives them meaning ("a null bound means unbounded"); this is not a
missing fact, so the no-explicit-null rule does not apply.

## 6. Authority bands used for P-04 and E

**Consulted:** `03_LLD.md:1322` ("≤ ₹10,00,000 / ₹10,00,000.01 – ₹25,00,000 / > ₹25,00,000"),
`03_LLD.md:2750` ("The authority amount of 3,00,000 falls in the ≤ ₹10,00,000 band, so
level `L2` is required").

The LLD pins the ≤ 10,00,000 band to L2 and §13 P-04 pins 1000000.01 to L3, but never
states the third band's level. **Chosen:** a third band `> 25,00,000 → CREDIT_HEAD`,
purely so the matrix covers `[0, ∞)` as CHK-011 requires. No vector asserts it.

## 7. Parameter encoding in the vector's `params` — E-01, E-02

**Consulted:** `03_LLD.md:668` ("Parameter values are always strings and are parsed by
`type`"), `03_LLD.md:680` (`INDEBTEDNESS_CAP` value `"200000.00"`), `03_LLD.md:678`
(`GROUP_DEVIATION_SHARE_REFER_PCT` type Integer, value `"20"`).

**Chosen:** both as JSON strings (`"200000.00"`, `"20"`), matching the parameter-set
asset. The Integer-typed percent is therefore `"20"`, not `20`.

## 8. `application.segment: null` is kept as an explicit null — E-01, E-02

**Consulted:** `03_LLD.md:2600` (the §6.2 request carries `"segment": null`),
`03_LLD.md:1327` ("a null segment matches only `'*'`").

The transcription rule says a missing fact is an absent field, never an explicit null.
**Chosen:** keep `"segment": null` anyway, in both the facts and the §2.3 policy header,
because the §6.2 request literally carries it and §4.9 gives a null segment distinct
matching behaviour from an absent one. These are the only explicit nulls in the facts of
any vector in these files.

## 9. The §2.3 `tests` array is dropped from E-01/E-02

§2.3's IR ends with `"tests": [ { … "facts": { "$fixture": "fixtures/jlg_vrm_c.json" } … } ]`.
A `$fixture` marker points at a file that does not exist and would make the vector
non-self-contained, and the IR's own test cases are not part of what E-01/E-02 assert.
**Chosen:** omit `tests` entirely.

## 10. S11-02 uses aggregate `WORST`, not the §2.3 `NONE`

**Consulted:** `03_LLD.md:593` (§2.3 scores with `{ "kind": "NONE" }`),
`03_LLD.md:1262` ("NONE: contributes nothing to the Decision, EXCEPT that any ERROR
entity contributes ERROR"), `03_LLD.md:2815` (the §6.2 response shows step `status: "PASS"`
for the NONE scorecard step).

Under `NONE` the step's own reported `status` for an ERROR entity is not fixed — the
response example shows a PASS step status for a NONE step. **Chosen:** `WORST`, so the
step status is unambiguously ERROR and the vector asserts
`{ status: ERROR, error: EVAL_SCORECARD_NO_BIN, decision: REFER }` in the same shape as
`D-07`. A separate vector is needed later for §4.7's NONE-plus-ERROR clause.

## 11. S12-01's third assertion is NOT transcribed

The §13 row reads: "later steps NOT_APPLICABLE / `SYS_SKIPPED_KNOCKOUT`; decision
DECLINE; **references to skipped steps return ∅**".

**Consulted:** `03_LLD.md:1270-1280` (§4.8's `decide` loop), `03_LLD.md:947` (BEL-016:
a result reference's target must be "a flow step in an **earlier stage**, or an earlier
step of the same stage"), `03_LLD.md:949-951` (a reference returns missing if the
referenced step was skipped by a knockout).

These three cannot be satisfied at once. §4.8 evaluates **every** step of the knockout
stage before testing for FAIL, then marks all remaining steps NA and breaks — so no step
that actually evaluates an expression lives in a stage later than the knockout stage,
and every reference target of an evaluated step has itself been evaluated. There is no
constructible policy in which an evaluated expression reads a skipped step's output.

`S12-01` therefore asserts only the two observable facts (NA + `SYS_SKIPPED_KNOCKOUT` on
every later step, decision DECLINE). The "∅" clause is left untranscribed rather than
guessed at. It needs either a correction to §4.8 or a statement that the ∅ rule is a
latent guarantee for `USE_DEFAULT` re-runs / replay rather than an observable one.

## 12. Non-canonical fact paths in the isolating vectors — S8, P, S11, F

`member.measuredIndebtedness`, `member.riskCategory`, `member.a/b/c`, `member.values`,
`application.one/zero/exposure` are not in the canonical lending facts of
`00 §3.9`. They are used deliberately so that each vector varies exactly one thing (rule
4 of the assignment) instead of re-deriving a measure through the full §2.3 variable
chain. The already-written `D-07` sets the precedent (`member.emi`, `member.income`).
E-01 and E-02 use only canonical paths.

## 13. `C-01` is neither an expression nor a decision vector

**Consulted:** `03_LLD.md:1348-1364` (§4.11 bucket function and its three vectors),
`03_LLD.md:3550` (the cell manifest's `challengers[] { deploymentId, bundleHash,
experimentId, bucketFrom, bucketTo }`).

The bucket function's inputs are `tenantId`, `applicationRef` and `experimentId`, and
its output is an integer bucket — not a `value` and not a decision. The README's
contract has no slot for either. **Chosen:**

- inputs in their real homes — `policy.policy.tenantId` (§2.3), `facts.application.applicationRef`
  (§6.2), and `experimentId` inside a `policy.challengers[]` array spelled as the cell
  manifest spells it;
- a new expect key `"bucket": <int>`.

`bucketFrom: 0, bucketTo: 10000` are filler so the challengers entry is well formed; no
vector asserts champion-vs-challenger routing. **All three bucket values were recomputed
from the §4.11 byte encoding and match the LLD table exactly** (`9b3f7a3b92430c81`→8193,
`f212be344116ef45`→1237, `80a4421df2b219ca`→6474), so the uncertainty here is only about
where the vector puts its inputs, not about the expected numbers.

## 14. `measure` stays a plain JSON string, while `outputs` are typed

**Consulted:** `03_LLD.md:887` ("Serialising results: `stripTrailingZeros().toPlainString()`,
as a JSON **string**"), `03_LLD.md:2807` (`"measure": "215000"`), and the
already-written `T-hit-policies.json`, which types step outputs
(`"outputs": { "points": { "int": 30 } }`).

**Chosen:** entity `measure` as the spec's own response encoding — a plain JSON string
(`"215000"`, `"216000"`, `"D"`) — and `outputs` with the README's typed-value encoding,
matching the peer files. S11-01's adverse reasons are therefore
`"outputs": { "reasons": { "list": [ { "text": "RA" }, { "text": "RB" } ] } }`.

## 15. F-01's step boundary is within one stage

The row says "step 2 of 4" without naming stages. **Chosen:** one non-knockout stage
with four steps, so the fuel exhaustion is not confounded with stage or knockout
behaviour. The 2,000-element list is `member.values` = integers 1…2000, giving a filter
cost of `1 + 2000 + 2000·c(p)` (§3.10) against `fuelLimit` 1000.
