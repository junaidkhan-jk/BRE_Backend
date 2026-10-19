# Disaster-recovery drill

**When you reach for this:** the semi-annual DR drill NFR-DR-01 requires, the quarterly restore
test NFR-DR-02 requires, or a real region loss.

This is a *procedure plus an evidence template*, not a description. The half of it that can run on
one machine already does: `./devstack/dr-drill.sh` executes the backup-and-restore drill end to
end and prints the evidence. The region-failover half needs two regions and is written here for the
team that has them.

---

## The one thing to understand before the drill

A restore of this system is **two artefacts, not one**.

Every personal field in the hot store and in the archive is ciphertext under a data key that is
itself stored only wrapped, under a key-encryption key the KMS holds and the database has never
seen (§9.2). That is deliberate: a stolen backup tape yields nothing. It also means the obvious
backup — the database — is **not sufficient to recover the data**, and recovering from it fails in
the worst available way:

- `pg_restore` reports success.
- The service starts.
- Decisions list, and `GET /v1/decisions/{id}` answers.
- Every fact snapshot is **permanently unreadable**, and no later restore recovers them.

Nothing errors, because nothing is wrong with the database. The backup was simply not the whole of
what had to be backed up. §9.5 calls this mechanism crypto-shredding and relies on it for the right
to erasure; from the DR side it is the same mechanism pointed at your own data.

**So the drill's pass criterion is never "the restore completed". It is "a sealed decision can be
read after the restore".** Step 7 of the script performs the incomplete restore deliberately, so
the failure is recognisable to whoever is following this at 3am.

| Artefact | Lives in | Restored by | Lost if |
|---|---|---|---|
| `bre_ctl` — policies, versions, bundles, deployments, audit | Control-plane PostgreSQL | PITR or `pg_restore` | the dump excluded the schema |
| `bre_dec` — decisions, sealed snapshots, **wrapped keys** | Decision PostgreSQL per cell | PITR or `pg_restore` | the dump excluded `tenant_data_keys` or `principal_data_keys` |
| KEKs | the KMS or HSM | the KMS's own recovery | the KMS region is gone and there is no cross-region key replica |
| Per-tenant integrity secrets (§9.2) | the KMS | the KMS's own recovery | as above — a lost one makes every stored fact HMAC report `INTEGRITY` |
| Bundles, manifests, Parquet archive | Object store, cross-region replicated | object-store replication | versioning or Object Lock was off |

The row that is most often wrong in practice is the **third**, and it is not a database question at
all. Confirm, before the drill and in writing, that your KMS replicates keys to the standby region
and that the standby's roles can use them. A KMS whose keys are single-region makes the second
region a decision service that cannot read anything it is handed.

---

## Part 1 — Backup restore including decryption (runs locally, today)

```bash
./devstack/up.sh --deliver     # registry, PostgreSQL, a deployed policy
./devstack/dr-drill.sh         # back up, destroy, restore, prove it decrypts
```

What it asserts, in order:

1. a decision is sealed with its facts, under a KEK that is on disk rather than in memory
2. the baseline replay matches and the facts are readable — *before* anything is broken
3. a dump of both schemas is taken, and the key material is copied as a separate artefact
4. **the dump is inspected for the key tables.** `pg_restore -l | grep tenant_data_keys`. A dump
   taken with the wrong `-n`, or a schema added later and not added to the backup, loses the keys
   silently and this is the only cheap place to catch it
5. both schemas are dropped — a total loss, not a simulated one
6. restore everything, restart, and read the decision: `matched=true` and the facts decrypt
7. restore the database *without* the key material: the decision record is intact and the facts are
   gone. **If step 7 ever passes with readable facts, stop.** Either the drill did not withhold the
   key, or something is storing key material where it must not be
8. restore the key and confirm readability returns

On a real installation substitute your own backup mechanism at steps 3 and 6 — PITR to a timestamp
rather than a `pg_dump`, and your KMS's key recovery rather than a file. Steps 2, 4, 6 and 7 are the
assertions and do not change.

### Two things that bite a restore onto REUSED infrastructure

Both were found by running the drill, both are invisible on a restore onto genuinely fresh
infrastructure, and both describe the situation a second restore attempt is in.

**1. Flyway's history outlives the schema it describes.** The registry keeps its migration history in
`public.flyway_schema_history` — not inside `bre_ctl`. So dropping `bre_ctl` leaves a history table
claiming version 16 is applied; Flyway then reports *"Schema public is up to date. No migration
necessary"*, creates nothing, and the restore fails several steps later with `schema "bre_ctl" does
not exist`. Drop that table along with the schema. (`bre_dec` keeps its history inside `bre_dec`, so
it re-migrates on its own.)

**2. `bre_ctl` V1 creates cluster roles unconditionally.** `CREATE ROLE bre_ctl_owner NOLOGIN` and
four siblings. A role is a **cluster** object, so dropping the schema leaves it behind and the next
migration fails with `role "bre_ctl_owner" already exists`. Either restore onto a fresh cluster, or
drop the roles first:

```sql
DROP OWNED BY bre_ctl_owner, bre_ctl_app, bre_ctl_platform, bre_ctl_auditor, bre_ctl_authn CASCADE;
DROP ROLE  IF EXISTS bre_ctl_owner, bre_ctl_app, bre_ctl_platform, bre_ctl_auditor, bre_ctl_authn;
```

Editing V1 to be idempotent is **not** the fix: it is applied on every installation, and changing it
would break Flyway's checksums everywhere.

### What a failed decryption looks like, and what it should not look like

An unopenable snapshot answers **HTTP 409** with `"reason"` on the problem body — `UNKNOWN_KEY` when
the key is simply not there, `SHREDDED` when the principal was erased (§9.5), `NOT_AUTHENTIC` when
the key does not match the data. A 409 because **no retry will ever help**.

A KMS that is merely unreachable answers **503 with `Retry-After`** (`reason: UNAVAILABLE`), because
the keys do exist and retrying is correct — §10.1 gives cached TDKs about an hour of headroom.

Branch your recovery on `reason`, not on the status alone. If you get a bare **500**, that is a
defect in the service rather than a key problem, and this runbook is the wrong one.

### What the local drill does not establish

PITR, WAL archiving, the 35-day retention NFR-DR-02 asks for, object-store versioning, Object Lock
and WORM, and restore under load. All need the infrastructure BD4 has not chosen. The drill
establishes the property every one of those depends on and none of them tests: that the set of
artefacts in the backup is sufficient to produce readable data.

---

## Part 2 — Region failover of the data plane (needs two regions)

**Not done. Needs a second region.** The design is §10.2; this is the procedure to execute against
it, and the evidence template to fill in.

The data plane is active-active, so this is not a promotion — both regions already serve. The drill
is about what the survivor does when the other stops, and the honest measure is **not** "did
traffic move" but "did any acknowledged decision get lost".

### Before the drill

- [ ] Both regions serving production traffic, and the HPA maximum in each sized for **100%** of
      total load. A survivor sized for 50% fails the drill by saturating rather than by failing over
- [ ] GSLB health checks confirmed to drain in 30–60 s (`NFR-DR-01`: data-plane RTO ≤ 1 min)
- [ ] MirrorMaker 2 lag measured, not assumed. The RPO claim is "≤ 5 s on permanent region loss" and
      it is exactly this number
- [ ] KMS keys usable from region B — see the warning above
- [ ] A load generator producing a known, counted stream of decisions with distinct `requestId`s,
      recording every response it receives. **This is the instrument.** Without it the drill can
      only observe that the service came back, which is the easy half
- [ ] Declared in advance: whether the tenant under test is in Sync-DR mode (§10.1), because the
      expected RPO differs — 0 rather than ≤ 5 s — and reading the result against the wrong
      expectation is how a drill passes while the guarantee is broken

### The drill

1. Start the counted decision stream against the GSLB name at a steady rate.
2. Record `t0`. Remove region A — withdraw its GSLB health, then stop its ingress. Do **not** stop
   the brokers first: the design claim is that in-flight decisions in region A either complete
   durably or are refused, and stopping storage before traffic skips the case that matters.
3. Record when the client's first error appears and when its last one does. The difference is the
   observed RTO.
4. Let the stream run for at least 10 minutes on the survivor alone. Watch CPU-bulkhead saturation
   and shedding (`bre_bulkhead_queue_depth`, 503 rate): a survivor that serves by shedding has not
   absorbed the load.
5. **Reconcile.** For every `requestId` the client holds a 2xx response for, confirm a decision
   record exists with that id. This is the RPO measurement and the only one that counts. A response
   the caller holds and the system cannot produce a record for is a D6 violation, whatever the
   dashboards say.
6. Restore region A. Confirm MirrorMaker catches up both ways and that the sinks de-duplicate rather
   than double-write (§10.2 — mirrored records de-duplicate on `decisionId`).
7. Separately: promote the **control plane's** warm standby in region B and time it to the point an
   operator can approve and activate a deployment. That is the ≤ 30 min RTO, and it is a different
   number from the data plane's.

### Evidence template

Fill in and attach to the drill record. Blanks are not acceptable results; "not measured" is, and
is worth more than a plausible number.

```
Drill date / regions          ____________  A: ________  B: ________
Sync-DR mode for the tenant   yes / no        (expected RPO: 0 / <= 5 s)
Decisions sent                ____________
2xx responses held by client  ____________
Decision records found        ____________    <-- must equal the line above
Unmatched 2xx (RPO breach)    ____________    <-- must be 0
First client error at t0 +    ______ s
Last client error at t0 +     ______ s        <-- observed data-plane RTO, budget 60 s
Survivor p99 during failover  ______ ms       (steady-state p99: ______ ms)
503s shed by the survivor     ____________
MirrorMaker lag at t0         ______ s        <-- the RPO claim rests on this
Duplicate records after A returned  ________  <-- must be 0
Control-plane standby promoted in   ______ min   budget 30 min
KMS keys usable in B          yes / no
```

### What would make the drill a failure even with traffic restored

- any 2xx the client holds with no decision record (D6, RPO)
- the survivor serving by shedding rather than by absorbing (HPA maximum undersized, §6.1.6)
- duplicate decision records after region A returns (sink de-duplication)
- KMS keys unusable in B — the region serves and can read nothing
- promotion of the control plane requiring a step nobody had written down

---

## If this is a real region loss, not a drill

Do the drill's **step 5** first and continuously: reconcile acknowledged responses against decision
records, and tell the lender which `requestId`s are in doubt. That list is the only thing a caller
cannot reconstruct for themselves. Everything else — latency, capacity, when A comes back — matters
less than an accurate answer to "which of my decisions did you lose", and because evaluation is
deterministic (D8), a caller holding a response can always have it re-recorded.

Then: [escalation.md](escalation.md) for severity and who to wake, and
[keys-and-signatures.md](keys-and-signatures.md) if the KMS is the part that is gone.
