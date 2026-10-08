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

## Q1 — The vector format has no channel for the Authority Matrix (blocking M1-09)

**This one will make a correct engine fail its own conformance suite.**

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

**Needs:** fix either the derivation rule or the L-03 row. S1-01 currently asserts
`status` only, so it stays true under either reading; L-03 asserts `DATA_MISSING_X` and
will need revisiting with the answer.

**Decide in:** LLD §4.6, then correct the §13 row.

## Q3 — S12-01's third assertion cannot be satisfied as written

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

## Already resolved, recorded so it is not re-litigated

- **`expect.steps[].id` vs `ref`, and rule `id` vs `code`.** The vector README originally
  said `id` for both; the IR says `ref` for a flow step (`03_LLD.md:446`) and `code` for a
  rule (`03_LLD.md:242-258`). Normalised to the IR spellings across all 90 vectors (35
  renames) and pinned in the vector README. Two words for one thing is a bug at the loader
  boundary.
- **`U` and `∅` are the same value.** `03_LLD.md:845` — "`U` means unknown, which is a
  missing Boolean." One `{"missing": true}` encoding covers both.
- **D11 vs `trustt-platform-lib`.** ADR-016, and D11 in `00` now carries the amendment.
