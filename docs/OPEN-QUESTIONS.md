# Open questions for the specification

Places where the specification does not fix something the code needed. Each was
settled with a recorded choice so that M0 could finish, and each needs a decision in
the document named — **`00_Decisions_and_Canonical_Terms.md` is the tie-breaker**, so
where `00` is the right home, that is where the fix belongs.

These came out of transcribing LLD §13 into 90 executable vectors. Per-family detail,
with the line consulted for every choice, is in
`../tests/engine-conformance/vectors/NOTES-vectors-{missing,tables,decision}.md`.

Ordered by how much damage each does if left alone.

---

## Q1 — The Authority Matrix channel — RESOLVED IN PRACTICE, still needs blessing

**No longer blocking.** The S8 vectors inline the matrix into the policy fragment's
`assets.authorityMatrix`, using camelCased DDL column names, and the engine reads it from there.
All 14 affected vectors pass. What remains is a documentation question: §2.2 shows
`levels`/`entries` only as empty arrays, so the shape the vectors chose is not blessed anywhere.

Two sub-questions the implementation had to settle, both worth confirming:

1. **The JSON shape of `levels` and `entries`.** Adopted: `levels: [{levelCode, rank}]` and
   `entries: [{entryNo, deviationType, productLine, segment, amountAbove, amountUpTo, levelCode}]`,
   plus `routingMode`. A `null` bound means unbounded, per §4.9 — read as absent, never as zero.
2. **A fragment that declares no matrix at all.** The S7 vectors do this. Adopted: a policy
   declaring no matrix states no authority requirement, so there is no level clause to satisfy and
   coverage is decided on the remaining clauses. The alternative — treating every deviation as
   unapprovable — makes S7-03 and its siblings unreachable. A real policy always declares a matrix
   (CHK-011 requires it to cover the whole amount range), so this path is for fragments only. See
   `Coverage.apply`.

**Decide in:** LLD §2.2 (show a populated matrix) and §4.9 (say what no matrix means).

### Original statement, for the record

LLD §4.9 (`03_LLD.md:1293-1298`) resolves coverage **and authority** per entity before
aggregation, for every entity whose raw status is `DEVIATION`. `resolveAuthority` reads
the Authority Matrix — a versioned shared asset referenced through
`assets.authorityMatrix` (`03_LLD.md:155`).

A conformance vector can carry `params` and `lists`, and nothing else. Read literally, a
fragment with no matrix gives `level == none`, so **every** `DEVIATION` entity becomes
`REFER` / `DEV_NO_AUTHORITY` — and S7-02, S7-03, S7-06, S8-01…S8-07 and P-04 could never
produce the outcomes §13 states for them.

Affects: S7, S8, P, E — four families, 24 vectors.

**Needs:** either an `assets` channel in the vector format, or a documented rule that "no
matrix declared ⇒ authority is not resolved and the entity keeps its raw status". The
vectors currently assume the second and assert the §4.7 outcome §13 states.

**Decide in:** LLD §13 (vector format) and §4.9.

## Q2 — `DATA_MISSING_` reason codes disagree with their own derivation rule

`applyMissing` (`03_LLD.md:1199`) derives the reason code as `"DATA_MISSING_" +
SNAKE(firstMissingSymbol)`, and the worked example at `03_LLD.md:1203` gives
`group.villageRiskCategory` → `DATA_MISSING_GROUP_VILLAGE_RISK_CATEGORY` — the fact root
included.

But §13's own L-03 row (`03_LLD.md:3960`) expects `DATA_MISSING_X` for the symbol
`member.x`, which drops the root. One of the two is wrong.

**RESOLVED in the engine, and the transcription corrected.** S5-01 asserts
`DATA_MISSING_GROUP_VILLAGE_RISK_CATEGORY` — root included — which matches §4.6's derivation rule
and its worked example. Two §13 rows therefore contradict each other, and L-03 is the outlier. The
engine implements the derivation rule, and **L-03's vector was corrected** to
`DATA_MISSING_MEMBER_X`, with the reasoning recorded in the vector's own `description` so nobody
later mistakes it for a transcription slip.

**Still needs:** the §13 L-03 row corrected in the LLD itself, so the document stops contradicting
its own rule.

**Decide in:** LLD §4.6, then correct the §13 row.

## Q3 — S12-01's third assertion cannot be satisfied as written

**Still open.** S12-01 passes on its two observable assertions; the third is simply not
transcribed, because no expression can be written that reads a skipped step.

The S12-01 row asks that "references to skipped steps return ∅". But §4.8 evaluates every
step of a knockout stage before testing for `FAIL`, then marks the remaining steps
`NOT_APPLICABLE` and breaks — and BEL-016 requires a reference target to be in an earlier
stage, or an earlier step of the same stage. So no expression that can be written is ever
in a position to read a skipped step's output.

S12-01 asserts the two observable facts (later steps `NOT_APPLICABLE` /
`SYS_SKIPPED_KNOCKOUT`, decision `DECLINE`) and omits the third.

**Needs:** either §4.8's ordering or the ∅ rule corrected, or the assertion dropped from
the row as unreachable.

**Decide in:** LLD §4.8 and the §13 row.

## Q4 — `sum([])` has no fixed result type

§3.9 types `sum` as `(List<Num>) -> Num`, "Integer for `List<Integer>`", and `[] -> 0`. An
empty list literal has no element type, so whether `sum([])` is the Integer `0` or the
Decimal `0` is open. `count` and `distinctCount` are unambiguously Integer.

S2-01a asserts `{"int": 0}`. If the type checker gives `[]` a Decimal element type it
becomes `{"decimal": "0"}`.

**Decide in:** LLD §3.9, or the type checker's treatment of `[]` in §3.4.

## Q5 — Are IR *fragments* validated against the full IR schema?

`$defs/rule` (`03_LLD.md:245`) requires `code`, `name`, `scope`, `check`, `onFail`,
`onMissing` and `outputs`. The §13 vectors carry IR *fragments*, not whole policies, and
M8 (`03_LLD.md:628`) documents defaults only for `outputs` and `when` — not for `name` or
`onFail`.

Seven policy vectors omit `name`, `onFail` and `outputs`. In each, `onFail` is genuinely
unreachable (the check is either constantly true or unknown), so the omission is
semantically safe — but if the loader validates fragments against the full schema they all
need the fields added.

**Decide in:** LLD §13 — say explicitly whether a fragment is schema-validated, and which
defaults the loader supplies.

## Q6 — Two field names the response and proto schemas do not carry

Both settled in the vectors, both worth confirming:

- **`approvalRef` on a `DEVIATION_APPROVED` annotation.** §4.9 (`03_LLD.md:1299`) says the
  annotation carries the `approvalRef`, but the §6.2 response and the gRPC schema
  (`03_LLD.md:3071`) model `annotation` as a bare string with no payload. The vectors put
  `approvalRef` as a sibling field on the entity.
- **The Authority Matrix JSON shape.** `03_LLD.md:688` shows `levels: []` / `entries: []`
  empty everywhere. The vectors use camelCased DDL column names from
  `03_LLD.md:1686-1731`.

**Decide in:** LLD §6.2 and §4.9.

---

## Status, 2026-10-08

All 90 conformance vectors pass. Q1 and Q2 are resolved in the engine as described above and need
only a documentation correction; Q3, Q4, Q5 and Q6 remain open but none of them blocks a vector —
each was settled with a recorded choice the vectors now exercise.

## Already resolved, recorded so it is not re-litigated

- **`expect.steps[].id` vs `ref`, and rule `id` vs `code`.** The vector README originally
  said `id` for both; the IR says `ref` for a flow step (`03_LLD.md:446`) and `code` for a
  rule (`03_LLD.md:242-258`). Normalised to the IR spellings across all 90 vectors (35
  renames) and pinned in the vector README. Two words for one thing is a bug at the loader
  boundary.
- **`U` and `∅` are the same value.** `03_LLD.md:845` — "`U` means unknown, which is a
  missing Boolean." One `{"missing": true}` encoding covers both.
- **D11 vs `trustt-platform-lib`.** ADR-016, and D11 in `00` now carries the amendment.
- **Variables are resolved lazily and memoised per entity.** §4.2 specifies a compile-time
  dependency DAG and topological order; the engine resolves on demand with cycle detection
  instead. Same order, same answers, and a variable no component reaches costs no fuel. The cache
  is per entity because a MEMBER-scope variable means something different for each member.
- **Aggregation consumes effective statuses, not raw ones.** §4.9 resolves coverage before
  aggregation, so an approved deviation contributes a PASS to the step — while the raw DEVIATION
  stays on the entity record for the audit trail. `stpEligible` reads the raw status, which is why
  a covered deviation approves but is not straight-through (P-02).

## Q7 — `bre_ctl.audit_chain_heads` has no row-level security in §5.2

**Status:** deviation taken, needs confirmation.

§5.2's row-level-security block enables and forces RLS over an explicit array of 30 tables. The
array omits `audit_chain_heads`, whose primary key *is* `tenant_id` — so as specified, that table
is tenant-owned and unprotected.

Found by executing the DDL against PostgreSQL for the first time (M2-02). A reviewer reading the
`DO $$ … FOREACH t IN ARRAY ARRAY[…]` block would have to diff a thirty-element list against the
schema's table list by eye to notice.

**Why it matters.** The grants revoke `INSERT` and `UPDATE` on `audit_chain_heads` from
`bre_ctl_app`, so no tenant can corrupt another's hash chain through the application role. But
`GRANT SELECT ON ALL TABLES IN SCHEMA bre_ctl` includes it, so one lender's session can read
another lender's `last_seq` and `last_hash`. That is not a decision or a fact, but it is
cross-tenant audit state in a platform whose first design decision is tenant isolation (D1), and
it is readable by exactly the role every control-plane service connects as.

**Deviation taken.** `audit_chain_heads` is added to the RLS array in
`V7__bre_ctl_locking_rls_and_grants.sql`, with a comment pointing here. A test asserts that *every*
table in `bre_ctl` carrying a `tenant_id` column has RLS both enabled and forced, so the next table
added to the schema cannot repeat the omission silently.

**To confirm:** whether the omission was deliberate — for instance if the chain head is meant to be
maintained by a `BYPASSRLS` role and read by nobody else. If so, the fix is a comment in §5.2
saying so, not a policy. Either way §5.2 should change, because the current text reads as an
oversight.

## Q8 — `tenantId` pattern: no `t_` prefix

**Status:** resolved in code; noting it because the convention is ambiguous in practice.

§2.2 `$defs/tenantId` and §5.2's CHECK on `bre_ctl.tenants` agree exactly:
`^[a-z][a-z0-9_]{2,31}$`. Neither requires a `t_` prefix, although every example in the
specification uses one (`t_acme`).

`TenantContext` had been written to enforce `^t_[a-z0-9_]{1,30}$`, inferred from the examples. That
would have rejected a tenant legitimately called `hdfc_bank` at the application boundary while the
database accepted it. Corrected to the specification's pattern, with the regex and the CHECK now
documented as having to stay identical: a value the application accepts and the database refuses
fails on a real request, and the reverse writes a row that RLS will never read back.

**Worth deciding:** whether `t_` should in fact be mandatory. If it should, §2.2 and §5.2 both need
the tighter pattern — not just the application.

## Q9 — the two fact envelopes cannot be rejoined as §9.2 specifies

**Status:** deviated in code, deliberately; §9.2 needs a sentence.

§9.2 splits a fact snapshot by `x-bre-group`: identifying fields are sealed per data principal
under that person's DPK, non-identifying fields under the tenant's TDK. Both envelopes are written
to `decision_facts`, and a replay has to put them back together.

It cannot, as written. The canonical schema tags **`parties[].partyRef` itself as identifying**, so
a literal reading seals it under the DPK — and then nothing left in the non-identifying half says
which party each identifying group belongs to. Rejoining is impossible; so is rendering a
five-member group's explanation when one member has been erased.

`FieldGroupSplitter` therefore keeps `partyRef` in the non-identifying half as the join key, and
moves `principalRef` — the *cross-application* identity, which is the one worth protecting — into
the DPK group as tagged. The argument for it being safe: every per-entity result in
`results_envelope` already carries the same `partyRef` as its `entityRef` (§4.5), and that envelope
is TDK-sealed, so excluding `partyRef` from the facts half would break the join while leaving the
value exactly as exposed as it already is.

**To confirm:** that this is the intended reading. The alternative — keying the identifying groups
by array position — makes the join fragile under any re-ordering, and a joint application where two
parties share a `principalRef` makes position the only distinguishing key, which is worse.

## Q10 — identifying facts that belong to no data principal

**Status:** RESOLVED and implemented. It was not one question — the fields are three different
kinds of thing, which is why no single rule fit any of them.

§9.2 groups identifying fields by `principalRef`, falling back to `partyRef`. Four fields in the
published schema have neither. (An earlier version of this note listed six; `memberPartyRefs` and
`ownerPartyRefs` turned out not to be tagged `identifying` at all, which is worth recording because
part of the problem was imaginary.)

### 1. A pseudonymous reference — `application.applicationRef`

**It is a join key.** It identifies an *application*, not a person, and the decision record already
carries the same value TDK-sealed as its own reference. Sealing it under a DPK would break the join
between the two halves while leaving the value exactly as exposed — which is the identical argument
that already keeps `parties[].partyRef` in the non-identifying half (Q9). Crypto-shredding destroys
the *attributes* a reference points at, which is what makes the reference meaningless. That is what
pseudonymisation is, and §9.5 already relies on it for every `entityRef`.

### 2. Data about a household — `annualIncome`, `monthlyRepaymentObligations`

**A household is a data principal in its own right**, keyed by `householdRef`, with its own DPK.

This is financial data about a *set* of natural persons, so there is no single individual's key —
which is why it used to be dropped, and dropping it was the actual bug: a replay of a decision that
read household income saw it as missing, so the RBI income-ceiling rule REFERred where the original
had passed. The same decision, re-asked, answered differently.

Mechanically a household is now a party: its own key, its own envelope, its own erasure.
`householdRef` was already non-identifying in the schema, so it survives in the other half and
serves as the join key with no deviation needed. The principal id carries a `household:` prefix,
because a household ref and a party ref come from different namespaces in a caller's system and
`H-1` is a perfectly possible party ref — without the prefix, one household's income could be
sealed under a party's key, and shredding that party would either destroy the household's data or
leave it readable under a key meant to be gone.

**The erasure policy is the operator's, and it is the one judgement here.** A household's key is
destroyed when *every* member has been shredded. One member's withdrawal must not destroy four
others' decision record, and the aggregate stays while anybody it describes still has a
relationship with the lender. The alternative — wrapping a household key under each member's DPK —
was considered and rejected: it complicates the key hierarchy for no legal gain, since each
member's own attributes are already individually shreddable.

### 3. The lender's own employee — `deviationApprovals[].approvedBy`

**The schema tag is wrong.** This is the person who authorised a deviation: the maker-checker trail.
Sealing it under a borrower's DPK would mean a borrower's erasure request destroys the lender's
approval record, which a regulator would read as destruction of evidence. It is not the borrower's
personal data in any case — it is the employee's, held on the lender's own basis and retained for
audit. It stays in the non-identifying half and keeps its `pii` tag, so §9.4 still masks it in a
customer-facing explanation.

### Where the rule lives, and where it should live

In `FieldGroupSplitter`, which is where Q9's deviation already is. The right long-term home is the
schema itself, tagging the three categories distinctly — but `lending-facts@1.0.0` is published and
registered per tenant with a content hash, so changing its tags in place would make two
installations disagree about what version 1.0.0 says. That is a schema version bump, and it belongs
with fact-schema management (M3-08).

Anything still unassignable remains reported rather than guessed at: an identifying field with no
enclosing party and no enclosing household is a schema defect, and the caller is told rather than
having it quietly placed.

### A second bug this surfaced

Making the split seal household income was half the job. `rejoin` — the inverse — only knew about
parties, so the income was sealed correctly and **never read back**: a replay still saw it as
missing. The inverse has to know about every root the split treats as a principal, and now does.
Caught by round-tripping a document rather than by inspecting the split, which looked correct.

