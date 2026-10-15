# What this application calls, and what stands in for it locally

Written because "mock the external systems" turned out to have a surprising answer: **at runtime
today, this application makes no outbound network call except to PostgreSQL.** That is not an
accident or an unfinished state — it follows from D2, and it is the single most important fact for
anyone integrating with it.

Verified, not assumed:

```
$ grep -rl 'HttpClient|RestClient|RestTemplate|WebClient|URLConnection|Socket(' \
      --include=*.java libs services tools | grep -v /test/
(no matches)

$ grep -E 'kafka|redis|valkey|s3|aws|minio|okhttp|retrofit|feign' gradle/libs.versions.toml
(no matches)
```

No HTTP client is on any service's classpath. There is nothing to mock, because there is nothing
being called.

---

## 1. The thing most integrators get wrong

**BRE does not fetch the credit bureau. It does not call a KYC provider, a dedupe service, a
scorecard API, or a core banking system.**

D2 makes a decision a pure function of `(facts, policy bundle, asOf)`. The caller — your LOS, your
origination app, your batch job — gathers everything a decision needs and sends it in one document.
BRE evaluates and answers.

```
   your LOS ──┬──▶ credit bureau          ┐
              ├──▶ KYC / dedupe           │  you call these,
              ├──▶ core banking           │  you assemble the answers
              └──▶ internal scorecards    ┘
                        │
                        ▼  one facts document
                  POST /v1/decisions ──▶  BRE  ──▶  decision + reasons
```

This is the reason replay works. A decision recorded today can be re-decided in three years and
produce the same answer, because every input is in the record. A bureau call inside the engine
would make that impossible — the bureau's answer changes, so the replay would differ, and an
auditor asking "why was this declined" would get today's answer to a question asked in 2026.

It also means **there is no bureau mock to write.** Facts arrive in the request body. To test with
different bureau data, change the facts; `reference-policy/fixtures/*.json` are three worked
examples, and `bre validate-facts` checks a document against the published schema before you send
it.

The one apparent exception is not one: the engine reads `param.*` and `list.*` — thresholds and
reference lists such as a negative list. Those are **resolved into the bundle at approval time**,
not fetched at decision time, precisely so a replay cannot drift. §8.4.

---

## 2. What is actually called at runtime

| # | Dependency | Protocol | Direction | Needed by | Local substitute |
|---|---|---|---|---|---|
| 1 | **PostgreSQL** — control plane (`bre_ctl`) | JDBC 5432 | out | policy-registry | **Embedded PostgreSQL 17** (`io.zonky.test:embedded-postgres`). A real server binary, downloaded once, no Docker |
| 2 | **PostgreSQL** — decision log (`bre_dec`) | JDBC 5432 | out | decision-service, only when `bre.decision-log.mode=POSTGRES` | Same embedded server; or `mode=IN_MEMORY`, which needs no database at all |
| 3 | **Active Pointer Manifest** | §8.2 long-poll over HTTPS | out | decision-service | **A file on disk, polled every 2 s** (`ManifestWatcher` + `FilePointerSource`) |
| 4 | **Bundle object storage** | S3 GET | out | decision-service | **A directory** (`FileBundleStore`), content-addressed by bundle hash |
| 5 | **KMS / HSM** — sign bundles and manifests | KMS API | out | policy-registry | **A locally configured Ed25519 private key**, which logs a warning naming itself as a finding if it appears in a deployed environment |
| 6 | **KMS / HSM** — wrap data keys (§9.2) | KMS API | out | decision-service | `LocalDevKms` — real AES-256-GCM key wrapping, not a pass-through, so the envelope format is genuinely exercised |
| 7 | **Identity provider (OIDC)** | OIDC | out | policy-registry | **A local account table** (V8), keyed on the same `subject` column `role_assignments` already uses, so an IdP's subjects replace these rows without changing an authorisation rule |
| 8 | **A tenant's shared assets** — parameter sets, reference lists, reason codes, authority matrices | JDBC 5432 | out | policy-registry | The same embedded PostgreSQL. **APPROVED versions only**, which is how AC-MODEL-007 is enforced. An `assets-<tenant>.json` file is the offline fallback for `bre psf bundle --assets`, and the registry logs when a threshold came from one |

That is the complete list. Rows 1 and 2 are the only ones that are a network call today; 3–7 are
all in-process or filesystem substitutes.

### Inbound — what calls BRE

| Endpoint | Caller | Auth today |
|---|---|---|
| `POST /v1/decisions` | your LOS or batch job | **None.** `X-Tenant-Id` is unauthenticated — see the warning below |
| `GET /v1/decisions/{id}/explanation` | your ops tooling | None, and the response is masked (§9.4) with no unmask path |
| `/v1/policies/**`, `/v1/deployments/**`, `/v1/audit/**`, `/v1/tenant/settings` | Rule Studio, your admin tooling | Bearer session token; default-deny filter with a three-path allow-list |
| `/v1/auth/**` | Rule Studio | Public by necessity (login, logout, me) |

> **The decision service is unauthenticated and takes the tenant from a header.** Anyone who can
> reach the port can decide as any tenant. It must sit on a private network behind something that
> authenticates the caller and sets that header from an authenticated identity. This is a known
> gap, not a local-only shortcut: M3-09 covers it, and until it closes, do not expose the decision
> service to an untrusted network.

---

## 3. Not built, so nothing calls it yet

Named here so "we mocked it" is never mistaken for "we built it". All of M4.

| Dependency | What it is for | Status |
|---|---|---|
| **Kafka** | `bre.decision.v1` — the durable decision stream (M4-01) | Not built. The decision log has `IN_MEMORY` and `POSTGRES` modes; there is no Kafka mode and no Kafka dependency on any classpath |
| **Valkey / Redis** | Idempotency keys, rate limits, adaptive concurrency (M4-05) | Not built. Idempotency is a unique index in the decision log |
| **Object storage (write-once)** | The Parquet archive, 10-year retention (M4-03) | Not built. Bundles live in a directory |
| **OpenTelemetry collector** | Traces, metrics, SLOs (M4-08) | Not built |
| **Kubernetes / Argo / Strimzi / CloudNativePG** | How any of this is deployed (M4-09) | Not built |

---

## 4. Running the whole thing locally

```bash
./devstack/up.sh --registry      # embedded PostgreSQL + registry + decision service,
                                 # then hands over to e2e.sh for the full walk
./devstack/up.sh --sandbox       # the same, plus a `sandbox` tenant with one
                                 # account per role, for walking maker-checker
./devstack/e2e.sh                # the end-to-end walk on its own, against a running stack
./devstack/up.sh --stop          # stop everything
```

`e2e.sh` proves, in order, through the real HTTP APIs:

1. an author signs in
2. the real 328-line reference policy compiles, and its test cases pass
3. coverage is **measured**, and the submit gate refuses a suite below the threshold
4. a tenant administrator lowers that threshold — and the before and after are both audited
5. submit refuses without a change note and a committee reference (FR-LIFE-002)
6. an `IN_REVIEW` version refuses an edit
7. approval signs a bundle, with the administrator's segregation-of-duties override audited
8. deployment publishes a signed Active Pointer Manifest
9. **a running decision pod adopts that manifest without a restart**
10. the deployed policy returns APPROVE / DECLINE / REFER exactly as its own test cases assert
11. a decision's masked explanation reads back
12. the hash-chained audit log verifies — every hash recomputed, no gaps

The Rule Studio against the live registry:

```bash
cd ui/studio-web
VITE_USE_FIXTURES=false VITE_REGISTRY_URL=http://127.0.0.1:8081 npm run dev
```

Without `VITE_USE_FIXTURES=false` the studio serves hard-coded sample data and says so in a banner
on every screen. That banner is a control, and it is asserted by an end-to-end test.

---

## 5. Each substitute, and what has to change for production

| Substitute | What it gets right | What it does not, and why that matters |
|---|---|---|
| Embedded PostgreSQL | A real PostgreSQL 17 server — row-level security, triggers, `FOR UPDATE SKIP LOCKED`, `sha256()` all behave exactly as in production. Migrations run as a superuser and the service connects as `bre_ctl_app`, which is the only arrangement in which RLS is genuinely exercised | One node. No replication, no failover, no backup. Nothing about DR is tested (M6-03) |
| File + 2 s poll for the manifest | Signature verification and the monotonic-version rule are the real ones, and they live in `FilePointerSource` — the watcher only asks | Propagation is up to 2 s rather than milliseconds, and one machine rather than a cell. `PointerSource` is the seam: an HTTP source changes `ManifestWatcher` and nothing else |
| Directory for bundles | Content-addressed, so a wrong file cannot masquerade as the right one | No immutability guarantee. A write-once bucket cannot be edited; a directory can |
| Local signing key | Ed25519, the real algorithm, real signatures, two distinct keys enforced (§8.6) | **The private key is on disk and in a process's memory.** A production key is generated in, and never leaves, the KMS. The service logs this as a finding at startup |
| `LocalDevKms` | Real AES-256-GCM key wrapping, so the §9.2 envelope is genuinely exercised | The key-encryption key is in memory. No rotation policy, no audit of key use, no crypto-shredding guarantee beyond the process |
| Local accounts | PBKDF2-HMAC-SHA256 at 600,000 iterations with the cost stored beside the hash; opaque server-side sessions stored as a digest, so a session can actually be revoked | No MFA, no SSO, no federation, no client-credentials flow. Those belong to the IdP |
| `IN_MEMORY` decision log | The decision path works end to end | **Every decision is lost on restart.** Not durable, so not replayable, so not auditable. Use `POSTGRES` for anything you intend to look at later |

---

## 6. Build-time, not runtime

| Dependency | Why | Note |
|---|---|---|
| Maven Central | Every library | D11 |
| npm registry | The studio's dependencies | D11 |
| `nexus.trustt.com` | `trustt-platform-lib`, for `services/*` only | The recorded D11 deviation — ADR. `libs/` and `bre-engine-core` stay Maven-Central-only, and a Gradle check enforces that no `libs/*` project declares another repository. **Credentials are read from `~/.gradle/gradle.properties` or the environment and must never appear in a build file.** |
| Adoptium JDK 25 | Gradle toolchain | Downloaded on first build |
| Embedded PostgreSQL binary | Local tests and devstack | Downloaded once, cached |
| Playwright browsers | The studio's end-to-end tests | `npx playwright install` |

---

## 7. How to be sure this list is still true

```bash
# Outbound HTTP clients anywhere in production code — expect no output
grep -rlE 'HttpClient|RestClient|RestTemplate|WebClient|URLConnection|Socket\(' \
  --include=*.java backend/libs backend/services backend/tools | grep -v /test/

# Message brokers, caches, cloud SDKs in the version catalogue — expect no output
grep -nE 'kafka|redis|valkey|s3|aws|minio|okhttp|retrofit|feign' backend/gradle/libs.versions.toml

# What the engine is allowed to touch at all — ArchUnit enforces this, and the rules
# ship with deliberately failing fixtures that prove each one fires
backend/libs/bre-engine-core/src/test/java/dev/bre/engine/arch/
```

The engine's purity is not documentation — it is a test. `dev.bre.engine..` may not import
`java.time.Clock`, `System.currentTimeMillis`, `Instant.now`, `LocalDate.now`, `Random` or
`ThreadLocalRandom`, and no logging call anywhere may take a `FactsDocument`, `RecordV` or
`JsonNode`. Those rules are why "the engine calls nothing" stays true as the code grows.
