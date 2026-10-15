# Keys and signatures

**Symptom:** `BAD_SIGNATURE`, `MISSING_SIGNATURE`, a bundle a pod refuses, or a key rotation due.

## What is signed, and by which of the three keys

| Artefact | Key | Who verifies it |
|---|---|---|
| **Bundle** — what a policy contains | The installation's bundle key | Every decision pod, against its own trust store |
| **Active Pointer Manifest** — what to serve | The *cell's* manifest key, a different key | Every decision pod |
| **Imported bundle** — another installation's artefact | ORIGIN by the exporter, countersigned IMPORT by us | Pods, under our own trust store |

§8.6 keeps the bundle and manifest keys distinct deliberately: a compromise of the thing that says
*what to serve* is not automatically a compromise of the thing that says *what a policy contains*.
The registry refuses to start if one key is configured for both.

## 1. A pod refuses a bundle

```bash
grep -iE "signature|BAD_SIGNATURE|trust" devstack/var/decision-service.log | tail -10
```

- **"the bundle carries no signature"** → it was never signed. Almost always a bundle built by a
  registry with no signing key configured; that registry logs a loud warning at startup.
- **"does not verify over this content"** → the key is trusted and the signature does not match.
  Either the bytes changed after signing, or the wrong key signed it.
- **"not trusted"** → the `kid` is not in the pod's trust store. Check `bre.policy.trusted-keys`
  against the registry's `bre.registry.bundle-signing-kid`. This is the usual cause after a
  rotation.

A bundle's address is `sha256:` over its canonical content, so **re-serialising a bundle breaks its
signature**. Never pretty-print one in transit.

## 2. Rotating the bundle key

The trust store holds the current *and previous* `kid`s, which is what makes a rotation safe:

1. Add the new key to every pod's `trusted-keys` and deploy that. **Pods first.** A registry
   signing with a key no pod trusts produces bundles nothing can serve.
2. Point the registry's `bundle-signing-kid` at the new key. New bundles are signed with it.
3. Leave the old `kid` in the trust store until no ACTIVE or grandfathered deployment references a
   bundle signed with it. The grandfather window is a tenant setting, 90 days by default — a
   decision replayed inside that window still needs the old bundle to verify.
4. Only then remove it.

Reversing steps 1 and 2 is the way to take an installation down with a configuration change.

## 3. Local keys where there should be none

```bash
grep -i "USING LOCALLY CONFIGURED SIGNING KEYS" devstack/var/policy-registry.log
```

That line in a deployed environment **is** the finding. A production key is generated in, and never
leaves, the KMS or HSM; the two settings exist so the registry runs on a laptop. The same applies to
`USING THE DEVELOPMENT KMS` in the decision service's log — key-encryption keys in process memory
protect nothing and are lost on restart.

## 4. Import refusals

See `docs/EXTERNAL-CALLS.md` for the trust model. In short: an importing installation accepts a
bundle only from a `kid` it has named as a trusted exporter, and only with role ORIGIN — an IMPORT
countersignature speaks for whoever imported it, not for whoever approved the policy, so importing
from one would mean trusting a party you never named.
