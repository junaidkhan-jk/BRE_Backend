# Decisions are being refused

**Symptom:** `POST /v1/decisions` returns a problem with `POLICY_NOT_SERVED`, or every request from
one tenant fails while others are fine.

## First: this is fail-closed working, not a crash

D9 says the engine never guesses. A pod that cannot establish *exactly* which policy to serve
refuses rather than serving something close. So the question is never "why did it break" but
"which of the four things it needs is missing".

## 1. Does the pod have a manifest at all?

```bash
grep -iE "adopted manifest|no Active Pointer Manifest" devstack/var/decision-service.log | tail -5
```

- **"no Active Pointer Manifest adopted"** at startup and nothing since → the pod has never had a
  manifest. It is refusing everything, correctly. Go to [deployment-stuck](deployment-stuck.md).
- **"adopted manifest version N"** → it has one. Continue.

A pod keeps the manifest it has if a later one is unreadable (§8.5), so "it was working an hour ago"
does not mean the current manifest is good.

## 2. Is the policy in that manifest, for that tenant and environment?

```bash
python3 -m json.tool devstack/var/manifests/manifest.json | head -40
```

Check `entries[]` for your `tenantId`, `environment` and `policyCode`. All three must match the
request exactly — `PROD` and `prod` are different environments, and the tenant comes from the
request, so a caller sending the wrong `X-Tenant-Id` gets this exact error.

## 3. Is the bundle on disk, and does it verify?

```bash
ls -la devstack/var/bundles/ | grep "$(python3 -c "
import json;m=json.load(open('devstack/var/manifests/manifest.json'))
print(m['entries'][0]['champion']['bundleHash'].replace(':','-'))")"
```

Missing file → the manifest points at a bundle this pod cannot fetch. The registry wrote the
manifest and the bundle to the same directory, so one without the other means they are pointed at
different places. Check `bre.policy.bundles` against the registry's `bre.registry.bundle-dir`.

Present but refused → [keys-and-signatures](keys-and-signatures.md).

## 4. Is it the facts rather than the policy?

A refusal that names a *reason code* is not this problem. `DATA_*` reasons and a REFER decision mean
the policy ran and the facts were incomplete — which is the policy working. Validate the caller's
document:

```bash
bre validate-facts their-facts.json
```

It reports paths and never values, and exits non-zero so a caller's CI can gate on it.

## Escalate when

- The manifest is present, the bundle verifies, and the pod still refuses. That is a defect, not a
  configuration problem — capture the pod log and the manifest, and do not restart the pod, because
  the in-memory state is the evidence.
