# The audit chain is broken

**Symptom:** `GET /v1/audit/verify` returns `intact: false`, naming a broken link or a sequence gap.

## Treat this as an incident, not a bug report

The chain is maintained by a database trigger, and two further triggers forbid `UPDATE`, `DELETE`
and `TRUNCATE` on `audit_log`. There is no application code path that can break it and no HTTP
endpoint that can write to it. So a broken chain means one of:

- somebody connected to the database as a role that can disable triggers (the table owner or a
  superuser) and changed or removed an entry;
- a restore from a backup took the table and the chain-head row to inconsistent points;
- storage corruption.

None of those is a code change. **Escalate to security before investigating further**, and do not
write to the database.

## 1. Get the facts, exactly

```bash
curl -sS "$REGISTRY/v1/audit/verify" -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

| Field | What it tells you |
|---|---|
| `firstBrokenSeq` | The first entry whose hash does not follow from its predecessor. Everything before it is still provably intact |
| `firstGapAfterSeq` | The entry after which a sequence number is missing — an entry was removed |
| `entries` | How many were walked |
| `headHash` | The current head |

The verification **recomputes** every hash with the trigger's own expression. It does not read a
stored "valid" flag, because a flag is exactly what somebody tampering would set.

## 2. Preserve the evidence

```bash
pg_dump --table=bre_ctl.audit_log --table=bre_ctl.audit_chain_heads "$PG_URI" > audit-evidence.sql
```

Take it before anything else. Then read the entries either side of `firstBrokenSeq` — the content of
what was altered is usually the point of the incident.

## 3. What cannot be done

**The chain cannot be repaired.** Recomputing the hashes forward from the break would produce a
chain that verifies and proves nothing — which is strictly worse than one that visibly does not
verify, because the next reader would believe it. The correct outcome is a broken chain, a recorded
incident, and the head hash of the last intact entry preserved externally.

## 4. The control that makes this detectable at all

Record the `headHash` somewhere outside this database — a ticket, a signed email, a notary — on a
regular cadence. Without an external anchor, somebody who can rewrite the table can rewrite the
whole chain consistently and the verification will pass. With one, they cannot rewrite history
before the anchor. The audit screen shows the head hash in full for exactly this reason.
