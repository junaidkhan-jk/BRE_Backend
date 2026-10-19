# Threat model

What an attacker could try against BRE, what stops them, and — the half that matters — **what
does not**. Written to be useful to a penetration tester on their first morning, so every
"mitigated" row names the mechanism and every gap is stated rather than implied.

The asset worth attacking is not the software. It is **a lender's credit policy** (their
thresholds are commercially sensitive and took years to tune), **their borrowers' personal data**,
and **the integrity of decisions** — which is the one a regulator asks about.

---

## 1. Trust boundaries

| # | Boundary | Who is on the far side | Authenticated today |
|---|---|---|---|
| B1 | Decision API (`/v1/decisions`) | The lender's origination system | **No.** `X-Tenant-Id` is a header anybody can set |
| B2 | Control plane (`/v1/policies`, `/v1/platform/…`) | Credit analysts, platform operators | Yes — session token or OIDC JWT |
| B3 | Bundle import (`POST /v1/bundles`) | **Another installation** | Signature, against a named trusted exporter |
| B4 | Kafka topics | Other services; a dedicated cell's tenant | Broker ACLs (deployment-provided) |
| B5 | Decision delivery / audit stream | The lender's downstream, a SIEM | Outbound only; bearer token the lender sets |
| B6 | Database | The services | Role per service, row-level security per tenant |
| B7 | KMS | The decision service | Out of scope here; the KMS is the trust root |

**B1 is the one to read twice.** The decision service has no authentication at all. The tenant is
a header, so anyone who can reach the port can decide as any tenant and read any tenant's
decisions. This is deliberate and documented (`docs/EXTERNAL-CALLS.md`): the service is designed
to sit behind a gateway that authenticates. It is also the single highest-impact finding available
to a tester, and it should be confirmed as *deployed* behind a gateway rather than assumed.

---

## 2. What an attacker would try

Grouped by what they are after rather than by STRIDE letter, because the question a tester asks is
"what can I get", not "which category is this".

### 2.1 Decide something that was never approved

The whole product's integrity rests here.

| Attack | What stops it |
|---|---|
| Submit a policy bundle of your own | A bundle is content-addressed and **signed**; a pod verifies the signature against configured public keys before loading. An unsigned or re-signed bundle is refused (`BAD_SIGNATURE`) |
| Point a pod at a different manifest | The Active Pointer Manifest is signed too, and the pod holds only the **public** key — a decision pod cannot mint one |
| Edit an approved policy in place | Database triggers: an APPROVED version is immutable, and `DRAFT→APPROVED` directly is refused. A change is a new version |
| Approve your own policy | Segregation of duties, enforced in the database. An editor cannot review; a deployment approver can be neither author, reviewer nor requester |
| Promote yourself to approve it | **Closed in M3-08**: you may grant a role only if you already hold every capability it confers |
| Tamper with the audit trail | Hash-chained, with `UPDATE`/`DELETE`/`TRUNCATE` triggers refusing. `GET /v1/audit/verify` recomputes rather than reading a stored flag |

**Gap:** the signing key is a local Ed25519 key in every environment that has not configured a
real KMS, and the service logs a warning naming itself as a finding. A tester should check whether
production actually has one.

### 2.2 Read another lender's data

| Attack | What stops it |
|---|---|
| Ask for another tenant's decision by id | Every query is tenant-scoped and the tables are under `FORCE ROW LEVEL SECURITY`. A correct id under the wrong tenant is a 404 |
| Set `X-Tenant-Id` to someone else | **Nothing, on B1.** See above — this is the gateway's job and is the finding to confirm |
| Read another tenant's policy through the control plane | The tenant comes from the *session*, never a header or path, except on the platform API — which requires `PLATFORM_MANAGE` |
| Escape the archive by tenant id | Archive keys are resolved and refused if they leave the root |
| Read the hot store directly | One role per service; RLS applies to the table owner too (`FORCE`) |

### 2.3 Get at personal data

| Attack | What stops it |
|---|---|
| Read facts from a decision record | Facts are **not stored by default**. When they are, they are sealed per §9.2 under a tenant key and a per-principal key |
| Steal the database and decrypt | Only wrapped keys are in the database; unwrapping needs the KMS, a different system |
| Read PII from logs | §9.6: messages name fields and never values. An ArchUnit rule forbids passing a facts document to a logger |
| Read PII from metrics | Forbidden label list, plus a bounded-value check on failure reasons — an exception *message* cannot become a label |
| Read PII from the decision topic | The event carries no facts. Kafka-first mode **refuses to start** with `store-facts` on |
| Recover an erased borrower's data | Crypto-shredding: the key is destroyed, with no soft delete |

**Gap:** `X-Forwarded-For` is recorded on audit entries and is attacker-controlled. It is evidence,
not identity, and is documented as such — but a tester should confirm nothing downstream treats it
as authentication.

### 2.4 Take the service down

| Attack | What stops it |
|---|---|
| Flood one tenant's traffic | CPU bulkhead plus per-tenant fair share; over-share requests are shed with 503 + `Retry-After` |
| Send a huge or deeply nested facts document | Schema validation and a fuel budget. **Fuzzed**: `BundleParserFuzzTest` asserts a nested document does not overflow the stack |
| Send a poison async message | Dead-lettered on the first attempt rather than retried, so the partition is not stalled |
| Exhaust the connection pool | Found and fixed: the relay no longer holds a transaction across an HTTP call, and the decision log no longer leaks three connections per decision |
| Expensive policy | Fuel budget per decision; the engine refuses rather than running for ever |

**Gap:** there is no per-tenant **rate limit** in the service. §6.4 puts that at the gateway with a
Valkey-backed token bucket, which is not built. The in-pod fair share bounds *concurrency*, not
request rate, and a tester should expect to saturate a tenant's quota because nothing enforces one.

### 2.5 Supply chain

| Attack | What stops it |
|---|---|
| Malicious dependency | Maven Central and npm only (D11), enforced by a build check on `libs/*` |
| Malicious GitHub Action | Every action pinned to a commit SHA, not a tag, and checked by `devstack/check-ci.sh` |
| Tampered bundle from another installation | Signature verified against a **named** trusted exporter; the importing side countersigns |

---

## 3. For the penetration tester

Start here, in this order:

1. **Confirm B1 is behind a gateway in the deployed environment.** Everything else in this
   document assumes it. If the decision API is reachable, nothing below matters much.
2. **Try to grant yourself a capability.** The escalation path through role granting was closed in
   M3-08; confirm there is not a second one.
3. **Try to deploy without the approvals.** The constraints are database triggers, so the UI is not
   the control — try the API directly, and try direct SQL if you have a database credential.
4. **Try to read a tenant's data as another tenant** through every endpoint, not only the obvious
   ones. RLS is the backstop; the question is whether any query bypasses the session's scope.
5. **Check the key material.** Local dev keys in production would make every signature meaningless.

What the 24-hour fuzzing campaign (`devstack/fuzz-campaign.sh`) already covers: the BEL parser and
the bundle parser, against malformed single inputs. It covers **nothing** about authorisation,
business logic, or sequences of valid requests — a fuzzer sends one input to one function.

## 4. What this document is not

It is not a penetration test, and writing it does not complete M6-02. It is the input an external
tester should be given on day one so they spend their time on the parts that are actually weak
rather than rediscovering the architecture. The test itself needs a tester and a scoped,
authorised engagement.
