# Severity, escalation and the rota

The runbooks beside this file say what to do. This one says **who is woken, how fast, and when to
stop trying and escalate**. It is the other half of M6-04, and it is derived from the specification
rather than invented: every threshold below traces to `02_Architecture.md` §11.5 or `00_Decisions`
§NFR. Where a number is the operating team's to choose it is marked **TO AGREE** and left blank.

## Severity

Severity is decided by **what the lender loses**, not by how hard the problem is to fix. A
mystifying bug that costs nothing is SEV-3; a trivial misconfiguration that silently approves
ineligible borrowers is SEV-1.

| | Meaning | Examples | Page? |
|---|---|---|---|
| **SEV-1** | Wrong decisions, or decisions lost | A 200 with no committed record (§11.5 "Durable record" — the budget is zero, any violation is SEV-1). A policy serving that was never approved. A tenant seeing another tenant's data. An audit chain that will not verify. | Immediately, primary **and** escalation together |
| **SEV-2** | No decisions | Decision API returning 5xx past the 14.4× burn rate over 1 h and 5 min. Every request `POLICY_NOT_SERVED`. The database unreachable. | Immediately, primary |
| **SEV-3** | Degraded, decisions still correct | Latency past 100 ms p99 at 14.4× burn over 1 h. A deployment not converging within 5 min. Delivery backlog growing. | Primary, in hours |
| **SEV-4** | Visible but not costly | Studio down (99.5% target, 3.6 h monthly budget — ticket, never a page). A single dead-lettered decision event. Coverage below threshold on a draft. | Ticket |

**The SEV-1/SEV-2 ordering is deliberate and is the one thing on this page to internalise.** No
decisions is an outage and everybody notices within minutes. *Wrong* decisions are worse and
nobody notices for weeks: a borrower who should have been declined is disbursed, and the loss is
realised at the first missed instalment. D9 fail-closed exists for this reason — the engine refuses
rather than guesses — so a SEV-1 is nearly always a human or configuration error rather than a
crash.

## What pages, from the SLOs

Straight from §11.5. These are alert definitions, not aspirations.

| Signal | Ticket | Page |
|---|---|---|
| Decision API 5xx | 6× burn over 6 h and 30 min | 14.4× burn over 1 h and 5 min |
| Decision API latency p99 > 100 ms | — | 14.4× burn over 1 h |
| 200 response with no committed record | — | **any occurrence** |
| Deployment convergence > 30 s | each breach | > 5 min |
| Sink freshness > 5 s | 6× burn | lag > 10 min |
| Shadow ERROR rate | > 0.1% | — |
| Studio availability | below 99.5% | never |

Multi-window burn rates rather than a flat threshold, because a flat one either pages on a
two-minute blip or waits half the monthly budget. Both windows must be breached to page.

## Response targets

**TO AGREE** with the lender, against NFR-AVAIL-01 (99.95% monthly = 21.9 minutes of budget for
the whole month). The 99.95% number is what makes the acknowledge target tight: a 30-minute
acknowledgement would spend the entire month's budget on one incident.

| | Acknowledge | Update the lender | Mitigate |
|---|---|---|---|
| SEV-1 | | | |
| SEV-2 | | | |
| SEV-3 | | | |
| SEV-4 | | | |

## Escalate when

Not "when you are stuck" — that is a judgement nobody makes well at 3am. These are mechanical:

- **15 minutes** on a SEV-1 or SEV-2 with no identified cause. Escalation is not an admission; it
  is the design.
- The runbook's first remedy did not work. Each runbook ends with where to escalate.
- The fix would **edit an approved artefact**. Stop. That is never the remedy (see the two rules in
  [README.md](README.md)), and an incident where somebody is reaching for `UPDATE` needs a second
  person before it needs a fix.
- The fix needs a **key**. The KMS break-glass holder is Security and is never the deployment
  approver.
- An out-of-hours policy change looks necessary. That is a credit-risk decision, not an engineering
  one, and 00 §3.3 still requires two approvers who are not the editor. There is no severity that
  suspends segregation of duties.

## The rota itself

Still **TO AGREE**, and still not invented here — the reasoning in [README.md](README.md) stands: a
rota is a commitment by the people named in it, and a plausible-looking one in a repository is
worse than none because somebody relies on it at 3am.

| Field | Who decides | Value |
|---|---|---|
| Primary | Operating team | |
| Secondary | Operating team | |
| Escalation | Engineering lead | |
| Shift hours and handover time | Operating team | |
| Who may approve an EXPEDITED deployment out of hours | **Credit risk, not engineering** | |
| Who holds the KMS break-glass | Security — never the deployment approver | |
| Paging tool and its escalation chain | Operating team | |

Two rows carry a real segregation requirement rather than a preference, and both are easier to
settle now than during an incident: the EXPEDITED approver, and the break-glass holder.

## After an incident

A SEV-1 or SEV-2 gets a written post-mortem, blameless, within five working days. It must answer
one question the runbooks cannot: **what made this reach production.** For anything in the SEV-1
column that means asking whether a control failed — an approval that was overridden, a coverage
gate that was below threshold, a reviewer who was also an editor. Those are recorded in the audit
chain, so the answer is retrievable rather than remembered:

    GET /v1/audit?entityType=POLICY_VERSION&entityId=<id>
    GET /v1/audit/verify
