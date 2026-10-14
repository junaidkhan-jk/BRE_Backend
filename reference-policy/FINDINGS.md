# What the reference policy found

M6-01 assembles the Rule Authoring Guide's MFI examples (E1–E11, E17, E18, E21) into one
policy and runs the real toolchain over it. Ninety conformance vectors prove the engine
implements §13's semantics; this proves the engine can express a lender's actual credit policy,
which is a different and larger claim — and the first time the two were put together, it found
six things. Running it against **facts** then found four more, three of them in the engine.

## Defects in the static checker, fixed

**1. A Boolean column was given an `OTHER` piece.** The gap analysis treats a column as
categorical when its cells only compare for equality, and adds an `OTHER` piece for "every value
nobody wrote down". That is right for free text and wrong for a Boolean, which has exactly two
values. The effect was that the specification's own E17 screening table — which is total over
`{true, false}` — was reported as having a gap at `(OTHER, false, false)`.

A table reported as broken when it is correct is worse than no check: the first thing an author
does is stop believing the checker. Fixed by closing the domain when every literal in the column
is `true` or `false`. An enum is closed the same way, but the engine cannot tell an enum from free
text without the fact schema, so only the Boolean case is closed.

**2. Unreachability was reported for opaque columns.** A column whose cells reference a parameter
or a variable is opaque: the analysis cannot see its bounds. Gaps were already skipped for those,
but reachability was not — and since every opaque cell reads as "any", every row after the first
looked already covered. `MFI_SCORE_CUTOFF` rows 3 and 4 were reported unreachable when they are
reachable. Fixed: opacity now skips both.

## Real gaps in the guide's own examples, fixed in this policy

**3. E18's `groupCycle` bins start at `= 1`.** Everything below 1 is uncovered. A `cycleNumber` of
0 is not hypothetical — it is what an unset field or an upstream data error looks like — and an
uncovered bin contributes **nothing, silently**, moving the score without anybody choosing to.
This policy adds a `< 1` bin with its own reason code.

**4. E18's `recentDelinquencies` bins start at `= 0`.** Same shape. A count cannot be negative,
but nothing in the type system says so, and a bin set that is total by construction costs one
line.

## A limitation worth naming

**5. §4.13.1's "drop open pieces containing no integer" is not implemented.** For an Integer or
Date column, the specification says to drop open pieces like `(1,2)` that contain no integer. The
analysis cannot do that, because it does not know a column's declared type — the same dependency
CHK-009 and CHK-017 wait on, the fact schema.

The visible consequence: bins written as `= 1` then `[2..3]` are reported as leaving `(1,2)`
uncovered, which for an Integer is empty and for a Decimal is real. This policy writes its numeric
bins half-open — `[1..2)`, `[2..4)`, `>= 4` — which is total for either type and is better style
regardless, because it removes the question.

## Defects found by evaluating the policy, not just compiling it

Assembling the policy proved it well-formed. Deciding with it proved something else, and these
four are the reason the distinction matters: **every one of them produced a plausible-looking
REFER**, which is indistinguishable from an integrator sending incomplete data. A suite that
only compiled the policy would never have reported any of them, and in production each would
have been investigated as a data-quality problem.

**7. Component outputs were never published between flow steps.** A scorecard computes a score;
the table one step below it reads `MFI_APP_SCORE_V1.score`. The outputs were computed, returned
on the result, and then dropped before the next step ran — so the reference policy referred
**every applicant** with `DATA_SCORE_MISSING` while the scorecard immediately above had just
scored them 455. Fixed in `FlowEvaluator`: a step's named outputs are bound into the context the
later steps see, **keyed by entity**. That keying is the part with teeth — a MEMBER-scope
scorecard produces one score per member, and binding the first member's for all of them would
decide one applicant's loan on another applicant's credit score with no outward sign. A forward
reference reads as Missing and fails closed (§3.9), so a mis-ordered flow cannot score against
a stale value.

**8. HOUSEHOLD scope was never bound.** It fell through to a single application-scope entity, so
`household.annualIncome` was unbound and E2's regulatory income ceiling — the RBI 3,00,000 cap,
the one rule in this policy that is not the lender's own choice — could not be evaluated at all.
Implemented: HOUSEHOLD iterates `households` in declaration order, which is the only order the
snapshot carries (unlike members, which `group.memberPartyRefs` orders).

**9. Facts were typed by their JSON shape, not by the fact schema.** A date arrives as a JSON
string and became `Text`, so `ageInYears(member.dateOfBirth, asOf)` and
`monthsBetween(group.formedOn, asOf)` were type errors on every real applicant. In the other
direction, any digits-only string became a `Decimal` — so a branch code of `"0012"` compared
equal to `"12"`. Replaced with `FactReader`, which takes each value's type from the schema, and
maps a bound entity to the collection it is a view of (`member.` → `parties[].`, §3.8) so the
single-entity form the §13 vectors use is typed too. An ill-formed value reads as Missing rather
than throwing: one bad field must not 500 a whole application, and naming the path is the
validator's job.

**10. `dpdHistory[].month` had no `format: date`.** E8 compares it against
`addMonths(asOf, -param.DPD_LOOKBACK_MONTHS)`, a Date, and its mask is `MONTH_ONLY` — both only
make sense for a date. Added to the schema.

## The assets, resolved

The limitation this document used to close on is gone. `bre psf bundle --assets` now resolves a
policy's parameter set, its reference lists and its Authority Matrix into the bundle, and
`reference-policy/assets.json` holds the reference policy's — the RBI figures where the Master
Direction sets one, a plausible lender's own limits elsewhere.

**They are resolved at bundle time, and that is the design, not a shortcut.** D2 makes a decision
a pure function of `(facts, policy, asOf)`. A parameter fetched live would mean two replays of the
same decision could differ because somebody edited a threshold in between, and the bundle hash
would no longer identify what was evaluated. Resolving them into the signed, content-addressed
bundle makes a parameter change a new bundle with a new hash — reviewable, deployable and
revertable like any other policy change.

Two consequences worth stating:

- **The Authority Matrix goes into the IR, not the bundle's `assets`.** That is where `IrCodec`
  reads it from, and PSF source can only carry the `CODE@version` reference. Left unresolved the
  engine sees a matrix with no levels — which is not "no matrix", it is a matrix under which
  nobody can approve anything, so every deviation became `DEV_NO_AUTHORITY` and the policy's
  deviation bands never applied. With it resolved, E7 behaves as the guide describes: one member
  over the soft cap routes to a named level; a fifth of the group refers.
- **The reason catalogue is still unresolved.** `assets.reasonCodes` is a reference and nothing
  reads it yet, so `customerReasons` is empty and only internal reason codes appear. That is a
  presentation gap, not a decision gap — the decision and its internal reasons are unaffected —
  and it waits on the catalogue's own storage (§5.2).

## Still reported, and correctly

**6. `MFI_SCORE_CUTOFF` is opaque (CHK-014).** Its cells compare against `param.SCORE_APPROVE_MIN`
and `param.SCORE_REFER_MIN`, so the table cannot be checked for gaps without the parameter values.
§4.13.1 says such a column is analysed once per parameter epoch.

This one changed status while the rest of this document was being written: the asset snapshot now
exists at bundle time, so the values *are* available — `psf bundle` resolves them a few lines after
it runs the static checks. Analysing the table against resolved parameters is therefore a concrete
piece of work rather than a blocked one, and it is **not** done: the checks still run before
resolution and still report the INFO. Doing it needs one decision first, which is why it is not a
one-line change — a gap found only under epoch *n* of a parameter set is a finding about that
epoch, not about the policy, so it has to be reported in a way that does not make a parameter edit
look like a policy defect.

## Finding 11 — the acceptance suite covers 28.6% of the policy, against M6-01's 90%

Measured, in CI, by `ReferencePolicyAcceptanceTest.coverageOfTheAcceptanceSuite` (FR-TEST-003):

| | covered | total |
|---|---|---|
| Rule statuses | 15 | 47 |
| Decision-table rows | 4 | 16 |
| Scorecard bins | 3 | 14 |
| **Pooled** | **22** | **77** — **28.6%** |

Three fixtures that each decide one complete application cannot do better, and the shape of what
is missing matters more than the number:

- **The `DATA_MISSING` branch of every rule is untested.** A complete fixture never omits a fact,
  so "the bureau did not answer" has never run once — and that is the branch that fires during an
  outage, on every application at the same time. It is the single most consequential untested path
  in the policy.
- **`GRP_SHG_SIZE` and `GRP_MIN_AGE` have never reached PASS.** All three fixtures are JLG, so the
  SHG group-size path is entirely unexercised in a policy whose product lines claim both.
- **Two of the three deviation paths are untested** — `POL_NTC_AMOUNT_CAP:DEVIATION` and
  `POL_AGE_AT_MATURITY:DEVIATION`. Those are the cases an approver is asked to authorise, so they
  are the ones where a wrong authority band is most expensive.

The assertion carries a floor *and* a ceiling (25–35%). The floor catches a fixture that stops
exercising a branch; the ceiling catches the happier failure, where somebody adds fixtures and
nobody updates this table. Either way the figure written here stays true.

Closing it is roughly one fixture per branch and is the remaining half of M6-01. It is recorded
here rather than fixed because inventing thirty fixtures to raise a number is how a coverage
target becomes a ritual — each one should be a case somebody can name.

## What this policy proves, and what it still does not

Lint, compile, bundle and verify pass. The policy **decides**, against documents that conform to
the published fact schema, and three cases are asserted in CI
(`ReferencePolicyAcceptanceTest`): a clean five-member JLG approves straight through with a
score of 455; one member on the negative list declines the whole application at the knockout
while the other four are still reported as screened (§4.8); and a soft-cap deviation on a fifth
of the group refers, with the approval routed to AREA_MANAGER by the amount band. `devstack/up.sh`
posts all three over HTTP against the running service, so the same three answers are checked
through the real API as through the engine.

Still not proven:

- **Customer-facing reasons.** Pending the reason catalogue above.
- **Scale.** The acceptance cases are five-member groups. M1-16's benchmark covers a 40-member
  group at 2.9 ms p99, but not with this policy.
- **The registry.** Bundles and the manifest are produced by the CLI and read from disk. The
  §8.2 long-poll, deployment records and parameter-set storage are M2-03 and M3.
