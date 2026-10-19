# The pilot parallel run

**What this is for:** PRD §12.1 exit criterion 3 — *"A pilot tenant has made ≥ 4 weeks of production
or parallel-run decisions. Every outcome difference against the tenant's documented policy is
explained and resolved."*

Two halves. The harness is built and tested: `bre parallel-run`. The four weeks, the lender, and the
*resolution* of each difference are not things code supplies — resolution is a credit-policy
judgement only the lender can make. This document is the procedure around the harness.

---

## Why a parallel run and not a pilot

A pilot where the BRE's decision is the one acted on puts a borrower on the outcome of a policy
nobody has yet seen behave at volume. A parallel run does not: the lender's existing system decides,
the borrower is unaffected, and the BRE decides the same applications alongside. Four weeks of that
produces the one thing no amount of testing produces — the real population, with its real missing
fields, its real distribution and its real edge cases — at zero risk to an applicant.

The output is **not** "the BRE agrees 98% of the time". It is a list of the causes of the 2%, each
one resolved to either *"their documented policy says this and the bundle did not implement it"* or
*"the bundle is right and their system was doing something undocumented"*. The second answer is
common and is the most valuable thing a parallel run finds, because an undocumented behaviour in a
lending system is a finding in its own right whatever happens to this project.

---

## What the lender has to supply

One line of JSON per application, in a file. Not a database connection — a file, so the extract can
be reviewed, re-run, and kept as evidence.

```json
{"applicationRef":"APP-00417","asOf":"2026-09-03","theirDecision":"APPROVE","facts":{ … }}
```

| Field | Why it matters |
|---|---|
| `applicationRef` | Their own key. It is the only field in the report that lets somebody look a case up, and a difference nobody can look up cannot be resolved. |
| `asOf` | **The date their system decided, per case.** Not today. Deciding a four-week-old application at today's parameter values produces a difference caused by the run's own clock, and nothing in the report distinguishes it from one caused by the policy. The harness refuses a case with no `asOf` rather than defaulting. |
| `theirDecision` | `APPROVE`, `DECLINE` or `REFER`, as their system recorded it. |
| `facts` | The application, as `lending-facts@1.0.0`. This mapping is the real work. |

`theirReasons` is accepted and ignored for now. Their reason codes and ours are different
vocabularies, and mapping them is a second project; what matters first is the outcome.

### The mapping is the work, and the first week is about the mapping

Expect the first extract to produce mostly `INCOMPARABLE`. That is normal and it is progress: it
means the harness is telling you which fields the extract does not carry yet, by path. Iterate the
extract until `INCOMPARABLE` is a small and *understood* remainder. Only then is an agreement rate a
statement about the policy.

Do **not** start the four weeks before that. Four weeks of unmappable extract is four weeks of
nothing, discovered at the end.

---

## Running it

```bash
bre parallel-run bundle.json \
    --cases week-1.jsonl \
    --pub bundle-signing.x509 --kid <kid> \
    --out register-week-1.json
```

The bundle must be the **signed** bundle that would serve in production, verified by its key. A
parallel run against an unsigned or re-compiled bundle measures something that will never be
deployed.

Weekly, not at the end. A cause found in week 1 and fixed is three weeks of cleaner data; the same
cause found in week 4 is a cause you cannot tell apart from the three that arrived with it.

### Reading the output

The report groups differences **by cause**, commonest first:

```
  cases  theirs    ours      decided by             reason
   1184  APPROVE   DECLINE   GRP_JLG_SIZE           POL_GRP_TOO_SMALL
     37  APPROVE   REFER     MFI_HH_OBLIGATION      POL_OBLIGATION_SOFT_CAP
      2  DECLINE   APPROVE   SCR_SCREENING          -
```

**Work down the list, not along the cases.** One policy gap produces a difference on every
application it touches, so 1,184 cases is one conversation, not 1,184. The count is what tells a
committee which cause to settle first.

Five verdicts, and the distinction between the last one and the rest is the one that decides whether
the pilot is useful:

| Verdict | Meaning | Who fixes it |
|---|---|---|
| `AGREE` | Same outcome | — |
| `DIFFER` | Both decided, differently | Credit committee |
| `WE_REFERRED` | The BRE asked for a human; their system decided | Credit committee — often a deliberate difference, not an error |
| `THEY_REFERRED` | Their system asked for a human; the BRE decided | Credit committee |
| `INCOMPARABLE` | **The BRE could not evaluate the case at all** | The integration team, in the extract |

`INCOMPARABLE` is reported separately and must stay separate. §4.8 is fail-closed, so a fact the
extract did not carry makes the BRE *refer* — which in a report reads exactly like a credit
disagreement. It is not one: it is a missing column. Counting these as policy differences sends a
credit committee to argue about underwriting criteria over a CSV mapping, which is how a pilot
burns a month.

Both agreement rates are printed — over all cases, and over the comparable ones — because somebody
will quote one of them in a steering meeting and the two differ by however much of the extract did
not map.

### What is not in the report, deliberately

**No fact values** (§9.6). Step codes, reason codes, fact *paths*, decisions and counts only. A
parallel-run report circulates to a credit committee by email, which is the least controlled channel
in the whole pilot, and the measured value that triggered a difference is exactly the field that
would be personal data. Where a measure would help — *"declined at a ratio of 0.58"* — the report
names the step and the reason, and the reader looks that case up in the decision record, which is
access-controlled and audited.

---

## Resolving a difference

Per **cause**, not per case. For each line in the register, the lender records one of:

| Resolution | Then |
|---|---|
| **Their documented policy says this; the bundle does not implement it** | A policy change through the normal lifecycle — DRAFT → IN_REVIEW → APPROVED, with the parallel-run cause as the change note. Never an edit to an approved version. |
| **The bundle is right; their system was doing something undocumented** | A finding for the lender, independent of this project. Record it and move on; do not change the bundle to reproduce an undocumented behaviour. |
| **Both are defensible; the lender chooses** | Record the choice and who made it. This is the common outcome for `WE_REFERRED`: whether a marginal case refers or declines is a risk-appetite decision, not a correctness one. |
| **A parameter value differs** | A parameter change, not a policy change — and check whether it is tagged `REGULATORY`, in which case it needs the compliance owner rather than the credit committee. |

The register carries empty `resolution` and `resolvedBy` fields per cause, left blank on purpose. A
field the harness pre-filled with a guess is a field somebody signs without reading.

**Exit criterion 3 is met when every line in the final register has a resolution and a name against
it** — not when the agreement rate reaches a number. A 99% agreement rate with one unexplained cause
is a fail; 94% with every cause resolved and three of them deliberate is a pass.

---

## The evidence pack for sign-off

Keep, per week:

- the extract (`week-N.jsonl`) and its row count
- `register-week-N.json`, with resolutions filled in
- the bundle hash the week ran against — in every register, printed by the harness
- the parameter set version, if it changed mid-run

The bundle hash is the part most likely to be forgotten and the part that makes the evidence worth
anything: a register that does not say which bundle produced it is a list of claims about an unknown
policy. If the bundle changes mid-run — and it will, because fixing a cause means a new version —
the register before and after are separate evidence, and the committee needs to see which causes the
change closed.

---

## What this does not do

- **It does not run the four weeks.** Needs the lender and their extract.
- **It does not resolve anything.** It localises each difference to one step and one reason code, so
  that the judgement is about credit policy rather than about archaeology.
- **It does not map their reason codes to ours.** Outcomes only, for now.
- **It does not compare per-member outcomes in a group.** A JLG decision is a group decision and
  that is what both systems record; member-level comparison would need their system to expose a
  per-member verdict, which is not a given.
- **It does not read from a live feed.** A file, so the evidence is reviewable and the run is
  repeatable.
