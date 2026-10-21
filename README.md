# BRE 2.0 — Backend

A multi-tenant business-rules and credit-decisioning platform for Indian lenders. The Decision API
is a **pure function of `(facts, policy bundle, asOf)`** — the engine never calls a bureau, a KYC
provider or a core-banking system. That determinism is what makes replay, simulation and
back-testing exact.

The MVP vertical is MFI group lending (JLG/SHG). The UI lives in a separate repository:
**[BRE_UI](https://github.com/junaidkhan-jk/BRE_UI)**.

**Status:** 63 of 68 build-plan tasks done, 795 tests green. The five remaining need people rather
than code — a penetration tester, a pilot lender, a compliance officer, three credit-policy
analysts and a second region. See [`docs/html/status.html`](docs/html/status.html).

---

## Quick start

Three commands, from nothing to a decision. **No Docker, no database to install, no `sudo`.**

```bash
git clone https://github.com/junaidkhan-jk/BRE_Backend.git
cd BRE_Backend
./devstack/up.sh --registry
```

The first run downloads a Java 25 toolchain and a real PostgreSQL server binary, so expect a few
minutes. After that it is about 90 seconds. When it finishes it has signed a policy, deployed it,
and decided the three reference fixtures — so "it started" and "it decides correctly" are
separate, visible claims.

Everything below is the long version.

---

## 1. Clone

```bash
git clone https://github.com/junaidkhan-jk/BRE_Backend.git
cd BRE_Backend
```

The UI is a separate repository and is not a submodule. Clone it alongside if you want the studio:

```bash
git clone https://github.com/junaidkhan-jk/BRE_UI.git
```

---

## 2. The database — user, password, schemas

**For local development there is nothing to create.** `devstack/up.sh` downloads and runs a real
PostgreSQL 18.6 server (`io.zonky.test:embedded-postgres`) in a temporary directory, on a random
port, and throws it away on `--stop`. It is a genuine server rather than an in-memory stand-in,
because the schema uses declarative partitioning and row-level security and neither survives a
fake.

If you want to point the services at **your own PostgreSQL** instead, this is what they need.

### Schemas

| Schema | Owner | What it holds |
|---|---|---|
| `bre_ctl` | `policy-registry` | Policies, versions, bundles, deployments, approvals, users, audit chain |
| `bre_dec` | `decision-service` | Decisions, sealed fact snapshots, wrapped keys, outbox |

### Roles

The Flyway migrations create these themselves — **do not create them by hand**:

```
bre_ctl_owner      NOLOGIN              owns the schema
bre_ctl_app        NOLOGIN              the application role (RLS applies)
bre_ctl_platform   NOLOGIN BYPASSRLS    operator actions only
bre_ctl_auditor    NOLOGIN              read-only
bre_ctl_authn      NOLOGIN BYPASSRLS    the login lookup
```

They are `NOLOGIN` on purpose. Grant one a password only for the account a service connects as:

```sql
ALTER ROLE bre_ctl_app LOGIN PASSWORD '<your-password>';
```

> **Two warnings, both learned the hard way.**
>
> **Migrate as a superuser, run as `bre_ctl_app`.** A superuser *bypasses row-level security
> entirely*, so running the service as one hides every tenant-isolation mistake.
>
> **Never set `spring.flyway.schemas`.** `V1` does `CREATE SCHEMA bre_ctl AUTHORIZATION
> bre_ctl_owner` itself. Naming it in configuration makes Flyway create it first, owned by the
> wrong role, which silently breaks `FORCE ROW LEVEL SECURITY` and the grants.

### Connecting your own database

```bash
--spring.datasource.url=jdbc:postgresql://localhost:5432/bre \
--spring.datasource.username=bre_ctl_app \
--spring.datasource.password=<your-password> \
--spring.flyway.enabled=true
```

Credentials are **placeholders marked `CHANGE ME`** in `application.yaml`. Put real ones in the
environment or a local properties file — never in the repository.

---

## 3. Extra setup — Docker, Kafka, Redis, a KMS

**None of it is needed locally, and that is deliberate.** Plenty of developer machines have no
Docker under corporate policy, and "one command" should not mean "one command, after an afternoon
with IT". The devstack substitutes:

| Production | Locally | Where it is written down |
|---|---|---|
| Docker / Kubernetes | nothing — plain JVM processes | — |
| PostgreSQL cluster | embedded PostgreSQL, downloaded from Maven Central | `devstack/up.sh` |
| Kafka | the decision log runs in-memory or on PostgreSQL | `docs/EXTERNAL-CALLS.md` |
| KMS / HSM | a local AES key; optionally a JCEKS file | `docs/EXTERNAL-CALLS.md` |
| Registry long-poll | a 2-second file poll | `docs/EXTERNAL-CALLS.md` |
| Identity provider | a local account table | `docs/EXTERNAL-CALLS.md` |

Every substitution is listed in **[`docs/EXTERNAL-CALLS.md`](docs/EXTERNAL-CALLS.md)** with what has
to be real in production. Read it before deploying anything.

Docker is used in CI only, for Testcontainers.

---

## 4. Java version

**Java 25** (ADR-012), and **you do not have to install it.** Gradle provisions the toolchain:

```
Java toolchain   25        declared in backend/build-logic
Gradle           9.6.1     via the committed wrapper — use ./gradlew, never a system gradle
Spring Boot      4.1.1
PostgreSQL       18.6      embedded, downloaded on first run
```

A system JDK of 17 or newer is enough to launch Gradle; it fetches 25 under `~/.jdks` or
`~/.gradle/jdks` and the devstack scripts find it (`devstack/jdk25.sh`).

To check:

```bash
cd backend && ./gradlew -q javaToolchains | head -20
```

> Spring Boot 4 split its auto-configuration into separate artifacts (`spring-boot-flyway`,
> `spring-boot-kafka`). A missing one **fails silently** — Flyway simply never runs and every query
> fails against a schema nobody created. A build check catches it:
> `./gradlew checkSpringAutoConfigurations`.

---

## 5. UI version

The UI is in **[BRE_UI](https://github.com/junaidkhan-jk/BRE_UI)**. In short: **Node ≥ 20.19**
(22.x tested), React 19.2, Vite 7.1, TypeScript 5.9, Monaco 0.57. Its README has the detail.

---

## 6. Starting the backend

### The devstack (recommended)

```bash
./devstack/up.sh              # decision service only, in-memory log
./devstack/up.sh --registry   # + PostgreSQL + the policy registry + an admin account
./devstack/up.sh --deliver    # as --registry, plus a DURABLE decision log and outbox delivery
./devstack/up.sh --sandbox    # as --registry, plus one account per role, for maker-checker
./devstack/up.sh --seed       # keys, bundle and manifest only — no service
./devstack/up.sh --stop       # stop everything
```

| Flag | Use it when |
|---|---|
| `--registry` | you want the studio, the governance API, maker-checker |
| `--deliver` | you need the **hot store** — replay, explanations, the back-test, the outbox |
| `--sandbox` | you are walking the approval path and need five roles |

Ports (override with environment variables):

```bash
BRE_PORT=8080            # decision service
BRE_REGISTRY_PORT=8081   # policy registry
BRE_RECEIVER_PORT=8082   # stand-in downstream, --deliver only
```

Set them if something already holds 8080:

```bash
BRE_PORT=18080 BRE_REGISTRY_PORT=18081 ./devstack/up.sh --registry
```

Default admin: **`admin@lender.local` / `devstack-admin-password`** (override with
`BRE_ADMIN_EMAIL` / `BRE_ADMIN_PASSWORD`). `--sandbox` adds
`author@ / reviewer@ / approver@ / viewer@ / auditor@sandbox.invalid`, password
`sandbox-password-1`, printed to `devstack/var/policy-registry.log`.

**These are local development credentials.** `--sandbox` must never be enabled anywhere real; the
service says so on startup.

### Build and test by hand

```bash
cd backend
./gradlew build                                  # compile, unit, integration, ArchUnit, 795 tests
./gradlew :tests:engine-conformance:test         # the 90 LLD §13 conformance vectors
./gradlew spotlessApply                          # formatting (warnings are errors here)
```

### Verify it works

```bash
curl -sS -X POST http://127.0.0.1:8080/v1/decisions \
  -H 'Content-Type: application/json' -H 'X-Tenant-Id: t_devstack' \
  -d @- <<'JSON' | python3 -m json.tool
{"tenantId":"t_devstack","requestId":"smoke-1","asOf":"2026-10-01",
 "environment":"DEV","policyCode":"MFI_JLG_CREDIT_UW",
 "facts": { } }
JSON
```

Use a real fixture rather than `{}`:

```bash
./devstack/e2e.sh        # the whole walk: author, approve, deploy, decide, audit
```

Other scripts worth knowing:

```bash
./devstack/chaos.sh            # kill a dependency, assert what §10 says should happen
./devstack/dr-drill.sh         # back up, destroy, restore, prove the data still decrypts
./devstack/deliver-demo.sh     # push and fetch, including what happens when downstream breaks
./devstack/fuzz-campaign.sh    # the 24-hour Jazzer campaign
```

### Offline, with no service at all

```bash
cd backend && ./gradlew :tools:bre-cli:installDist
BRE=backend/tools/bre-cli/build/install/bre/bin/bre

$BRE psf lint   reference-policy/mfi-jlg-credit-uw.psf.yaml
$BRE psf bundle reference-policy/mfi-jlg-credit-uw.psf.yaml --key k.pkcs8 --kid dev \
                --assets reference-policy/assets.json -o bundle.json
$BRE decide bundle.json --facts reference-policy/fixtures/approve-5-member-jlg.json \
            --pub k.x509 --kid dev --as-of 2026-10-01
$BRE parallel-run bundle.json --cases cases.jsonl --pub k.x509 --kid dev   # pilot parallel run
```

---

## 7. Starting the UI

Full instructions in the [BRE_UI README](https://github.com/junaidkhan-jk/BRE_UI). Against a running
backend:

```bash
cd ../BRE_UI
npm ci
VITE_USE_FIXTURES=false VITE_REGISTRY_URL=http://127.0.0.1:8081 npm run dev
```

Then open the printed URL and sign in with the admin account above.

`VITE_USE_FIXTURES` defaults to **true**, which serves sample data. Set it to `false` or the studio
shows a demo. It announces itself when it is showing samples, and an end-to-end test asserts that
banner on every screen.

---

## 8. Pre-data scripts and seeding

**No SQL to run by hand.** Three layers seed themselves:

**1. Schema — Flyway, automatically.** 16 migrations for `bre_ctl`, 7 for `bre_dec`, run at startup
when `spring.flyway.enabled=true` (the devstack sets it). `clean` is **disabled** — `flyway clean`
on a lender's control plane would destroy the audit trail.

**2. Accounts — the bootstrap.** `--registry` creates tenant `t_devstack` and an admin.
`--sandbox` adds one account per role. Both are configuration, not scripts.

**3. Policy and reference data — `devstack/e2e.sh`,** which `up.sh --registry` runs for you. It
installs the parameter sets and reference lists from
[`reference-policy/assets.json`](reference-policy/assets.json), creates the policy, saves
[`reference-policy/mfi-jlg-credit-uw.psf.yaml`](reference-policy/mfi-jlg-credit-uw.psf.yaml),
compiles it, approves it, signs a bundle, deploys it, and decides the four fixtures against the
deployed policy — comparing each with the answer the policy's own tests assert. **If a fixture
decides differently the script fails.** That comparison once caught a pod serving a two-day-old
policy while the registry reported the new one ACTIVE.

The reference policy is the acceptance suite: 19 rules, decision tables, a scorecard, and the
RBI microfinance caps as `REGULATORY` parameters.

### Fact snapshots are off by default

`bre.decision-log.store-facts` defaults to **false**, because fact snapshots are the applicant's
personal data. Replay, the explanation endpoint and the back-test all need them, so turn it on
deliberately:

```bash
--bre.decision-log.store-facts=true
```

Without it those features correctly report that they checked nothing — `meaningful: false` — rather
than reporting "no impact", which is what an approver hopes to hear.

---

## Layout

```
backend/               the Gradle root — run ./gradlew from here
  libs/                engine and libraries (Maven Central only, no Spring)
  services/            decision-service, policy-registry, decision-sink, …
  tools/bre-cli        the offline CLI
  tests/               conformance vectors, property tests, performance
devstack/              one-command local stack, chaos, DR drill, fuzz campaign
reference-policy/      the MFI JLG policy, its assets and fixtures — the acceptance suite
docs/                  runbooks, threat model, compliance register, ADRs, status
BRE_PRD/               the specification (PRD, architecture, LLD, build plan)
deploy/                Helm chart, Strimzi, CloudNativePG, Argo CD
```

## Documentation

| Read it for | |
|---|---|
| [`docs/README.md`](docs/README.md) | the map — start here |
| [`docs/runbooks/`](docs/runbooks/) | one file per thing that goes wrong, for somebody woken at 3am |
| [`docs/THREAT-MODEL.md`](docs/THREAT-MODEL.md) | trust boundaries and the gaps, stated plainly |
| [`docs/COMPLIANCE.md`](docs/COMPLIANCE.md) | the PRD §10 control register, checked in CI |
| [`docs/EXTERNAL-CALLS.md`](docs/EXTERNAL-CALLS.md) | what is substituted locally and what must be real |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | the Definition of Done |

## Before you deploy this anywhere

Three things from the threat model, stated here because they are easy to miss:

1. **The Decision API has no authentication.** `X-Tenant-Id` is an unverified header. The service is
   designed to sit behind a gateway that authenticates callers. Do not expose it to an untrusted
   network.
2. **The development KMS protects nothing.** Key-encryption keys live in process memory (or a local
   file). Supply a real `KmsPort` before the system holds real applicants.
3. **Legal hold is not implemented** (FR-AUD-009). Retention is configurable and nothing enforces
   it, so data is retained past its stated period. Build the hold **before** the purge job, or the
   first purge is the incident. Recorded in `docs/COMPLIANCE.md`.
