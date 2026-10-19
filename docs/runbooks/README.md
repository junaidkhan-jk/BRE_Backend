# Runbooks

One file per thing that goes wrong. Each is written to be followed by somebody who has been woken
up, has not read the design documents, and has fifteen minutes.

| Runbook | When you reach for it |
|---|---|
| [Decisions are refused](decisions-refused.md) | `POLICY_NOT_SERVED`, every request failing, a pod that will not serve |
| [A deployment did not take effect](deployment-stuck.md) | Approved and nothing changed; SCHEDULED past its date; the manifest did not move |
| [A decision is wrong](decision-wrong.md) | A borrower was declined who should not have been, or approved who should not have been |
| [Keys and signatures](keys-and-signatures.md) | `BAD_SIGNATURE`, a key rotation, a bundle a pod refuses |
| [The audit chain is broken](audit-chain-broken.md) | `/v1/audit/verify` reports a gap or a broken link |
| [Database and migrations](database.md) | Flyway failed, row-level security is hiding rows, the restricted role cannot write |
| [Disaster-recovery drill](dr-drill.md) | The semi-annual DR drill, the quarterly restore test, or a real region loss |
| [Severity, escalation and the rota](escalation.md) | Deciding how bad this is, who to wake, and when to stop trying |

## What is NOT here, and why

**The names on the rota.** M6-04 asks for a rota, and a rota is a list of names and shift
boundaries agreed by the people on it. Inventing one would produce a document that looks like a
commitment nobody made — worse than no document, because somebody would rely on it at 3am. What
*is* written down, in [escalation.md](escalation.md), is everything around the names: the severity
classes, which signals page and at what burn rate, and the five mechanical escalation triggers.
Those come from the specification rather than from preference. The names, the shift hours and the
response targets are marked TO AGREE and left blank.

**Anything requiring M4.** There are no Kafka, Valkey or Kubernetes runbooks, because there is no
Kafka, Valkey or Kubernetes. See `docs/EXTERNAL-CALLS.md` for what is substituted locally and what
has to be real in production.

## The two rules that apply to every runbook here

1. **Never edit an approved artefact to fix a decision.** A policy version, a bundle, an asset
   version and an audit entry are all immutable by design and by database constraint. The fix for a
   wrong policy is a new version; the fix for a wrong deployment is a rollback, which is a new
   deployment. An installation where somebody has learnt to `UPDATE` their way out of an incident
   cannot answer "what was serving at 14:30 last Tuesday", and that question arrives from a
   regulator rather than from an engineer.

2. **A decision that has already been made does not change.** Facts, policy and `asOf` are all on
   the record, so re-deciding reproduces it exactly. If a borrower was wrongly declined, the remedy
   is a new decision under a corrected policy — not a rewrite of the old one.

## Severity and escalation

In [escalation.md](escalation.md), with the rota template at the end of it.
