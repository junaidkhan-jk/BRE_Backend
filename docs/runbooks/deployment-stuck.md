# A deployment did not take effect

**Symptom:** somebody approved a deployment and nothing changed. Or it says SCHEDULED and its date
has passed. Or the manifest version did not move.

## 1. What does the deployment itself say?

```bash
curl -sS "$REGISTRY/v1/deployments?envCode=PROD" -H "Authorization: Bearer $TOKEN" \
  | python3 -m json.tool
```

| Status | What it means | What to do |
|---|---|---|
| `PENDING_APPROVAL` with `approvals` < `approvalsRequired` | It is waiting for people, not for the system | Get the approvals. PROD needs two, and the approver can be neither the author, the reviewer, nor the requester |
| `SCHEDULED` and `effectiveFrom` is in the future | Working exactly as asked | Nothing. It activates on its own |
| `SCHEDULED` and `effectiveFrom` has passed | The activator has not claimed it | Step 2 |
| `ACTIVE` but pods serve the old policy | The manifest moved and the pods have not | Step 3 |
| `CANCELLED` / `ROLLED_BACK` | Somebody undid it | Read the audit trail: `/v1/audit?subjectKey=<deploymentId>` |

## 2. SCHEDULED past its date

The activator polls every 30 seconds and claims each due deployment with `FOR UPDATE SKIP LOCKED`.

```bash
grep -iE "scheduled deployment activated|could not look for due deployments" \
  devstack/var/policy-registry.log | tail -10
```

- **"could not look for due deployments"** → the registry cannot read the due list. The exception is
  logged with it. The two causes seen in practice: the `bre_ctl_authn` role lost its `SELECT` on
  `deployments`, or the database is unreachable.
- **Nothing at all** → the timer is not running. Check that the registry started with scheduling
  enabled; a pod with no `@EnableScheduling` logs nothing and waits forever.
- **The clock** → the comparison is against the *database's* `now()`, not the JVM's. A pod whose
  clock is ahead will not activate anything early, by design.

## 3. ACTIVE but pods serve the old policy

```bash
grep "adopted manifest version" devstack/var/decision-service.log | tail -3
python3 -c "import json;print(json.load(open('devstack/var/manifests/manifest.json'))['manifestVersion'])"
```

If the file's version is higher than the last line the pod logged, the pod has not picked it up
within its poll interval. Locally that is a 2-second file poll; in production it is §8.2's
long-poll. A pod that *refuses* a newer manifest logs why — a bad signature, or a version that went
backwards, which is refused deliberately because an older manifest is either a replay or an
out-of-band rollback.

**Do not restart the pod to "fix" this.** A restart hides whether adoption was working, and the
in-memory manifest is the evidence of what it was serving.

## 4. Nothing to roll back to

A rollback needs an earlier ACTIVE deployment in the same environment. A first deployment has none,
and the refusal says so. The remedy is forward: approve a corrected version and deploy it.
