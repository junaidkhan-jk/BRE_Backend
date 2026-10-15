# Integrating with BRE

For a team building against this platform: what to call, in what order, and the four things that
will surprise you.

There are **two APIs** and confusing them is the most common first mistake.

| | What it is | Contract | Who calls it |
|---|---|---|---|
| **Decision API** | Facts in, decision out. The hot path. | [`decision-api.openapi.yaml`](../backend/libs/bre-contracts/src/main/contracts/decision-api.openapi.yaml) | Your loan origination system, per application |
| **Control Plane API** | Authoring, review, approval, deployment, audit. | [`control-plane.openapi.json`](api/control-plane.openapi.json) · live at `/swagger-ui.html` | Your admin tooling, or nothing — people use the Studio |

If you are integrating a loan origination system, you need the first one and almost none of the
second.

---

## 1. The shortest path to a decision

```bash
./devstack/up.sh --sandbox          # a running platform with a sandbox tenant, no Docker
```

```http
POST /v1/decisions
Content-Type: application/json
X-Tenant-Id: t_devstack

{ "tenantId": "t_devstack", "requestId": "APP-100001", "applicationRef": "APP-100001",
  "asOf": "2026-10-01", "environment": "DEV", "policyCode": "MFI_JLG_CREDIT_UW",
  "facts": { ... } }
```

You get back a `decision` of `APPROVE`, `DECLINE` or `REFER`, the reason codes, and a result per
rule that fired. `reference-policy/fixtures/` has a worked example of each outcome — start by
POSTing those verbatim and matching the responses, before sending your own facts.

---

## 2. The four things that will surprise you

### `asOf` is yours to set, and it decides the answer

`asOf` is the **business date the decision is made for**, not "now". The engine resolves every
effective-dated parameter against it: a cap that rises on 1 April is ₹2,00,000 for an `asOf` of
31 March and ₹3,00,000 for 1 April, from the same policy version.

This matters when you replay. A decision re-run with the same facts, the same policy version and
the same `asOf` reproduces **exactly** — that is a guarantee (D8), and it is what makes a decision
defensible to a regulator two years later. Re-run it with today's date instead and you get today's
answer to last year's application, which is a different thing that looks like the same thing. Send
the application's own date, store it, and replay with it.

### A `REFER` is not a rejection

Three outcomes, and the middle one is the one integrations get wrong:

- `APPROVE` — proceed
- `DECLINE` — a rejection. Carries up to four **customer-facing** adverse reason codes, which is
  what your rejection letter is built from
- `REFER` — a human must look at this. **Not a rejection.** It returns internal reasons only, and
  deliberately returns no customer reasons: there is nothing to tell the customer yet, and a
  system that renders a REFER as a decline rejects applications the lender never declined

A REFER also arrives when data is **missing**. The engine fails closed (D9) — it never guesses at
an absent fact — so an incomplete application REFERs rather than being quietly evaluated on
whatever was there. If your integration is producing far more REFERs than expected, the cause is
almost always a fact path you are not sending; the response names the path.

### Facts are not stored, by default

`store-facts` defaults to **false**, and the facts you send are PII. BRE keeps the decision, the
reason codes, the rule results and a hash of the facts — not the facts. So:

- The decision log can tell you *what was decided and why*, and cannot replay against the original
  facts unless you kept them
- **You keep the facts.** Your origination system is the system of record for the application
- Turning storage on needs the envelope encryption and crypto-shredding of §9.2/§9.5, which is M4
  work and not yet built. Until then, treat `store-facts: true` as unavailable

### Idempotency is on you, in both directions

`requestId` makes a decision request idempotent: retry the same one and you get the same decision
back rather than a second one.

Going the other way, if you consume the decision stream (§3a of
[EXTERNAL-CALLS.md](EXTERNAL-CALLS.md)), delivery is **at-least-once**. Every event carries a
stable `ce-id`, and you must deduplicate on it. A team that assumes exactly-once builds a double
disbursement.

---

## 3. Authentication

| | |
|---|---|
| Decision API | `X-Tenant-Id`, and **it is not authenticated today**. The decision service must not be exposed to an untrusted network — put it behind your gateway. This is the single most important line in this document |
| Control Plane API | `Authorization: Bearer <token>` — either a session token from `POST /v1/auth/login` or your identity provider's JWT |

For the control plane: **your identity provider says who someone is, and this installation says
what they may do.** Roles come from BRE's own tables keyed on the IdP's subject, never from a token
claim, so a user has to be provisioned here before they can act — a valid token for an unknown
subject is a 401. See §3c of [EXTERNAL-CALLS.md](EXTERNAL-CALLS.md) for why, and what to configure.

A 403 names the capability you are missing and which roles grant it, so it is actionable without
asking anybody.

---

## 4. If you are automating the control plane

Mostly you should not — the Studio exists for this. But if you are scripting a promotion pipeline,
the lifecycle is a state machine and the constraints are database triggers rather than UI rules, so
a client cannot work around them:

```
DRAFT  →  IN_REVIEW  →  APPROVED  →  ARCHIVED        a policy version (00 §3.3)
PENDING_APPROVAL → SCHEDULED → ACTIVE → SUPERSEDED    a deployment (00 §3.4)
```

Three constraints that will fail your first script, and each is deliberate:

1. **Segregation of duties.** A version's editor cannot review it. A deployment's approver can be
   neither the author, nor the reviewer, nor the requester — three distinct people for a PROD
   change. The commonest first failure is one service account trying to do all three.
2. **Submitting needs a change note and a committee reference** (FR-LIFE-002), and a coverage
   figure above the tenant's threshold. A suite of one happy-path case does not pass.
3. **Approved is immutable.** There is no edit. A change is a new version; a bad deployment is
   rolled back by a *new deployment* pointing at the previous bundle. That is what makes "what was
   serving at 14:30 last Tuesday" answerable.

An approved policy version pins its shared assets at exact versions (FR-MODEL-003), so changing a
parameter set never changes an approved version's behaviour. The consequence to plan for: pushing a
threshold change to production means a new asset version **and** a new policy version for every
policy that pins it. `GET /v1/assets/{kind}/{code}/usage` lists them.

---

## 5. Sandbox

```bash
./devstack/up.sh --sandbox
```

Creates a `sandbox` tenant with one account per role, so you can test a flow that needs an author,
a reviewer and a deployment approver without creating three users by hand — which is otherwise the
first thing segregation of duties forces you to do.

Everything external is substituted locally: PostgreSQL is embedded, the KMS is a local key, the
manifest long-poll is a file poll, the identity provider is an account table.
[EXTERNAL-CALLS.md](EXTERNAL-CALLS.md) lists every substitute and what has to be real in
production — read it before you plan a deployment, not after.

---

## 6. Where the contracts come from, and which to trust

- **`decision-api.openapi.yaml` is hand-written and authoritative.** It is the published contract
  for the endpoints you call, and it is the input to the implementation rather than a report of it.
- **`control-plane.openapi.json` is generated** from the service's own mappings by a test that
  writes it into this repository, so it cannot drift from the code. It is a faithful description of
  an internal API whose shape is still moving — accurate, but not a stability promise.

Neither is a substitute for the fixtures. If your integration matches
`reference-policy/fixtures/`, it works.
