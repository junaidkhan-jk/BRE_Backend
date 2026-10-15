# A decision looks wrong

**Symptom:** a borrower was declined who should have been approved, or approved who should not have
been. Somebody is asking for it to be changed.

## The first thing to say out loud

**A decision that has been made does not change.** Facts, policy version, bundle hash and `asOf` are
all on the record, so the decision is reproducible — and that is the property that makes it
defensible. The remedy for a wrong decision is a *new* decision under a corrected policy, recorded
alongside the old one. An installation where decisions get edited cannot answer "what did you decide
and why", and that is the question that matters.

So the job here is to find out **which** of three things was wrong, because the remedy differs.

## 1. Reproduce it exactly

```bash
curl -sS "$DECISIONS/v1/decisions/$DECISION_ID/explanation" -H "X-Tenant-Id: $TENANT" \
  | python3 -m json.tool
```

The explanation is replayed from the record: the same facts, the same bundle, the same `asOf`. If it
reproduces, the decision is a faithful consequence of its inputs and one of the three below is at
fault. If it does **not** reproduce, stop and escalate — that is a determinism defect and it is
more serious than one borrower's application.

Facts are masked (§9.4) and there is no unmask path. If you need the figures, that is a deliberate
privilege decision, not a runbook step.

## 2. Which of the three?

| What was wrong | How you can tell | Remedy |
|---|---|---|
| **The facts** | The explanation shows a value the caller should not have sent, or `DATA_*` reasons for facts the caller holds | The caller's integration. `bre validate-facts` on their document; nothing in BRE needs changing |
| **A threshold** | The rule fired correctly against the value, and the value should not have been the threshold | A new **asset version** (M3-03), reviewed by a second person. Note the effective date: if the threshold was wrong *from* a date, the new window starts there and past decisions stay as they were |
| **The policy** | The rule itself is wrong — the wrong comparison, the wrong scope, a missing guard | A new **policy version**: fix the source, add a test case that fails before the fix, submit, review, approve, deploy |

## 3. The case that is none of those

A REFER with `DATA_MISSING` on a fact the caller *did* send usually means a mistyped path. A
mistyped path reads as a missing fact — correctly and silently — so the policy is not at fault and
neither is the threshold.

```bash
bre validate-facts their-facts.json   # reports paths, never values; exits non-zero
```

The run endpoint reports the same warnings without deciding anything:
`POST /v1/policies/{code}/versions/{n}/run`.

## 4. Before you close it

Add the case as a **test case** on the next version. A wrong decision that nobody turned into a test
is a wrong decision waiting to recur, and the coverage gate (FR-TEST-003) only counts branches a
test has actually exercised.
