# Database and migrations

**Symptom:** the registry will not start, Flyway failed, or queries return nothing when rows plainly
exist.

## 1. "relation does not exist" on every query, and the scripts look fine

Flyway's auto-configuration is a **separate artifact** in Spring Boot 4:
`org.springframework.boot:spring-boot-flyway`. With only `flyway-core` on the classpath, Flyway sits
there and never runs — so the service starts, connects, and every query fails against a schema
nobody created.

```bash
grep -iE "flyway|Migrating schema|Successfully applied" devstack/var/policy-registry.log | head
```

No Flyway lines at all → the artifact is missing. Lines present and a failure → read the failing
script's error; it is usually the next item.

## 2. "schema bre_ctl already exists" during V1

Do not set `spring.flyway.schemas`. Flyway creates the schemas it is told about, and V1 does
`CREATE SCHEMA bre_ctl AUTHORIZATION bre_ctl_owner` itself. Naming it in configuration makes Flyway
create it first, owned by the connecting user — and the obvious fix of adding `IF NOT EXISTS` is
worse, because it leaves the schema owned by the wrong role and quietly breaks `FORCE ROW LEVEL
SECURITY` and the §5.2 grants.

## 3. Queries return nothing, and the rows are there in psql

This is row-level security working, and you are looking at it from the wrong connection.

- Every policy compares `tenant_id` against `current_setting('bre.tenant_id', true)`, which **fails
  closed**: no scope set means no rows.
- A **superuser bypasses RLS entirely.** So `psql` as the migrating role sees everything and the
  service sees nothing — which looks like missing data and is the opposite.

Connect as the application role to reproduce what the service sees:

```bash
psql -w "$PG_URI" -c "SET ROLE bre_ctl_app; SELECT set_config('bre.tenant_id','t_devstack',false);
                      SELECT count(*) FROM bre_ctl.policy_versions;"
```

## 4. "permission denied for table audit_chain_heads" from a connection that can write audit_log

`SECURITY DEFINER` **does not bypass RLS when the definer owns the table and FORCE is on.** That is
why `bre_ctl_authn` exists: a `NOLOGIN BYPASSRLS` role that owns the pre-tenant functions — login
lookup, session lookup, the due-deployment list. If you add a function that must read across
tenants, it goes to that role, and it needs an explicit `GRANT SELECT` on each table it reads:
**`BYPASSRLS` is not a table privilege.**

## 5. "new row violates row-level security policy" on a write that should work

The connection was bound to the transaction before the tenant scope existed. Two paths invert the
usual order — login (the tenant is not known until the account is found) and the audit log (written
from outside any request). Both use `TenantSession.bind()`, which issues
`set_config('bre.tenant_id', ?, true)` — transaction-local, so the database reverts it and it cannot
leak onto a pooled connection.

## 6. Never

```
flyway clean
```

`clean-disabled: true` is set in configuration for the control plane, and it is not a preference. It
destroys every policy version, every approval and the hash-chained audit log, and there is no undo
for an audit log.
