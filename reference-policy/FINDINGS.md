# What the reference policy found

M6-01 assembles the Rule Authoring Guide's MFI examples (E1–E11, E17, E18, E21) into one
policy and runs the real toolchain over it. Ninety conformance vectors prove the engine
implements §13's semantics; this proves the engine can express a lender's actual credit policy,
which is a different and larger claim — and the first time the two were put together, it found
six things.

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

## Still reported, and correctly

**6. `MFI_SCORE_CUTOFF` is opaque (CHK-014).** Its cells compare against `param.SCORE_APPROVE_MIN`
and `param.SCORE_REFER_MIN`, so the table cannot be checked for gaps until the parameter set is
resolved. §4.13.1 says such a column is analysed once per parameter epoch; that needs the asset
snapshot, which is M2-03's registry work. The INFO finding is the honest answer meanwhile.

## What this policy does not yet prove

Lint, compile, bundle and verify all pass. **The policy has not been evaluated against facts**,
because that needs the asset snapshot — the parameter values, the three reference lists, the
reason catalogue and the authority matrix — which only the registry can resolve (M2-03). Until
then the policy is proven well-formed and signable, not proven to decide correctly.

That gap is the honest state of M6-01: the acceptance suite exists and is clean, and the half of
it that needs resolved assets waits on the registry.
