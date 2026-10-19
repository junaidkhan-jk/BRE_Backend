#!/usr/bin/env bash
#
# The restore half of the disaster-recovery drill (M6-03), executed rather than described.
#
#   ./devstack/up.sh --deliver      first: registry, PostgreSQL, a deployed policy
#   ./devstack/dr-drill.sh          then: back up, destroy, restore, prove it is readable
#
# M6-03 asks for two things. Only one of them can run on one machine:
#
#   REGION FAILOVER of the data plane — needs a second region. NOT DONE HERE and not faked. The
#     runbook (docs/runbooks/dr-drill.md) carries the procedure and the evidence template for the
#     team that has two regions; §10.2 is the design.
#
#   BACKUP RESTORE INCLUDING DECRYPTION — runs here, in full, and is the half where the
#     interesting failure lives. Restoring a database is a solved problem that PostgreSQL does for
#     you. Restoring a database whose every personal field is ciphertext under keys held somewhere
#     else is not, and it fails in a way that passes every obvious check: pg_restore reports
#     success, the service starts, decisions are listed, and the facts are gone forever. Nothing
#     errors, because nothing is wrong with the database. The backup was simply not the whole of
#     what had to be backed up.
#
# So the drill asserts the thing an operator actually needs to know — after the restore, can a
# sealed decision still be READ — and then deliberately performs the incomplete restore to show
# what it looks like. Both runs are the drill. A drill that only ever does it right measures
# nothing: the procedure's purpose is to be followed under pressure by somebody who has not read
# it, and the failure mode has to be recognisable.
#
# WHAT THIS DRILL CANNOT TELL YOU. It restores a local embedded PostgreSQL from a pg_dump taken
# minutes earlier. It does not exercise PITR, WAL archiving, a 35-day retention (NFR-DR-02), object
# storage versioning, WORM locks, or a restore under load. Those need the infrastructure BD4 has
# not chosen. What it does establish is the property every one of those depends on and none of them
# tests: that the set of artefacts in the backup is sufficient to produce readable data.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VAR="$ROOT/devstack/var"
DRILL="$VAR/dr"
PORT="${BRE_PORT:-8080}"
DECISIONS="http://127.0.0.1:$PORT"
TENANT="t_devstack"
POLICY="MFI_JLG_CREDIT_UW"
ASOF="2026-10-01"
PID_FILE="$VAR/decision-service.pid"
KEYSTORE="$DRILL/kek.jceks"
KEYSTORE_PASSWORD="drill-only-not-a-secret"
# Fixed, not generated. The integrity HMAC (§9.2) is keyed off this, and a random per-process
# secret would make every restored fact report INTEGRITY — a mismatch caused by the drill's own
# setup, indistinguishable in the output from a tampered backup.
INTEGRITY_SECRET="ZHJpbGwtaW50ZWdyaXR5LXJvb3Qtc2VjcmV0LW5vdC1yZWFsLS0="

step()  { printf '\n\033[1;36m▸ %s\033[0m\n' "$*"; }
ok()    { printf '  \033[0;32m✓\033[0m %s\n' "$*"; }
note()  { printf '  \033[0;33m•\033[0m %s\n' "$*"; }
fail()  { printf '  \033[0;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

[[ -s "$VAR/postgres.properties" ]] \
  || fail "no PostgreSQL. Run ./devstack/up.sh --deliver first (this drill needs a real database)."
PG_ADMIN_URL="$(grep '^admin.url=' "$VAR/postgres.properties" | cut -d= -f2-)"
PG_PSQL_URI="$(grep '^psql.uri=' "$VAR/postgres.properties" | cut -d= -f2-)"

source "$ROOT/devstack/jdk25.sh"
require_jdk25
JAR="$(ls "$ROOT"/backend/services/decision-service/build/libs/*.jar | grep -v plain | head -1)"
[[ -f "$VAR/manifests/manifest.json" ]] \
  || fail "no registry manifest at $VAR/manifests/manifest.json. Run up.sh --deliver."

mkdir -p "$DRILL"

psql_q() { psql -w "$PG_PSQL_URI" -qtAX -v ON_ERROR_STOP=1 -c "$1"; }

# Both schemas, before anything else. This drill drops them and puts them back, so an INTERRUPTED
# run leaves a devstack with one of them missing — and the next run would then back up half a
# database, restore it, and report a successful recovery of a system that was already broken when it
# started. A drill that proceeds from an unknown state measures the state, not the backup.
for schema in bre_dec bre_ctl; do
  [[ "$(psql_q "SELECT count(*) FROM information_schema.schemata WHERE schema_name='$schema'")" == "1" ]] \
    || fail "$schema is missing. A previous drill was interrupted, or the devstack never came up
     fully. Rebuild it:  ./devstack/up.sh --stop && ./devstack/up.sh --deliver"
done

stop_service() {
  [[ -f "$PID_FILE" ]] || return 0
  local pid; pid="$(cat "$PID_FILE")"
  kill -0 "$pid" 2>/dev/null || return 0
  kill "$pid" 2>/dev/null || true
  for _ in $(seq 1 40); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
  kill -9 "$pid" 2>/dev/null || true
}

# The service as the drill needs it: facts stored (so there is something encrypted to lose), the
# dev KMS on a file (so the keys outlive the restart a restore requires), and a fixed integrity
# secret. store-facts is OFF by default everywhere else and must stay that way — fact snapshots are
# personal data under §9.2, and this turns them on against a devstack's synthetic applicants.
start_service() {
  local keystore_arg=("--bre.crypto.dev-kms-keystore=$KEYSTORE")
  [[ "${1:-}" == "--without-keystore" ]] && keystore_arg=("--bre.crypto.dev-kms-keystore=$DRILL/absent.jceks")
  : > "$VAR/decision-service.log"
  nohup "$JAVA" -jar "$JAR" \
    --server.port="$PORT" \
    --bre.env-code=LOCAL \
    --bre.policy.bundles="$VAR/bundles" \
    --bre.policy.manifest="$VAR/manifests/manifest.json" \
    --bre.policy.manifest-key="$(cat "$VAR/registry-manifest.x509")" \
    --bre.policy.trusted-keys.reg-local-2026-01="$(cat "$VAR/registry-bundle.x509")" \
    --bre.decision-log.mode=POSTGRES \
    --bre.decision-log.store-facts=true \
    --spring.datasource.url="$PG_ADMIN_URL" \
    --spring.datasource.username=postgres \
    --spring.datasource.password= \
    --spring.flyway.enabled=true \
    --bre.crypto.dev-kms-keystore-password="$KEYSTORE_PASSWORD" \
    --bre.crypto.integrity-root-secret="$INTEGRITY_SECRET" \
    "${keystore_arg[@]}" \
    > "$VAR/decision-service.log" 2>&1 &
  echo $! > "$PID_FILE"
  for _ in $(seq 1 90); do
    grep -q "Started DecisionServiceApplication" "$VAR/decision-service.log" 2>/dev/null && return 0
    grep -qi "APPLICATION FAILED TO START\|Error starting" "$VAR/decision-service.log" 2>/dev/null \
      && { tail -30 "$VAR/decision-service.log"; fail "the service failed to start"; }
    sleep 1
  done
  tail -30 "$VAR/decision-service.log"; fail "the service did not start in 90s"
}

decide() {
  local body
  body="$(python3 - "$ROOT/reference-policy/fixtures/approve-5-member-jlg.json" \
                    "$TENANT" "$1" "$ASOF" "$POLICY" <<'PY'
import json, sys
print(json.dumps({"tenantId": sys.argv[2], "requestId": sys.argv[3],
                  "asOf": sys.argv[4], "environment": "DEV", "policyCode": sys.argv[5],
                  "facts": json.load(open(sys.argv[1]))}))
PY
)"
  curl -sS -m 30 -X POST "$DECISIONS/v1/decisions" \
    -H 'Content-Type: application/json' -H "X-Tenant-Id: $TENANT" -d "$body"
}

explain() {
  curl -sS -m 30 -o "$DRILL/explanation.json" -w '%{http_code}' \
    "$DECISIONS/v1/decisions/$1/explanation" -H "X-Tenant-Id: $TENANT"
}

# Read one field out of the last explanation. Defined once because every assertion below reads it,
# and a `grep -q` on the raw body would pass on a field appearing anywhere in it, including inside
# an error message that happens to quote the word.
field() {
  python3 -c "
import json,sys
try: d=json.load(open('$DRILL/explanation.json'))
except Exception: print('-'); sys.exit()
print(d.get('$1','-'))"
}

facts_readable() {
  python3 -c "
import json
try: d=json.load(open('$DRILL/explanation.json'))
except Exception: print('no'); raise SystemExit
facts=d.get('facts') or {}
# A readable snapshot has the group structure in it. 'present but empty' and 'masked' are both
# readable; absent is not. Masking replaces values, it does not remove the fields.
print('yes' if isinstance(facts,dict) and facts else 'no')"
}

printf '\033[1m\nBRE disaster-recovery drill — backup, destroy, restore, decrypt\033[0m\n'
printf 'NFR-DR-01 (RPO/RTO) and NFR-DR-02 (backups) — control plane and hot store only.\n'

# ---------------------------------------------------------------- 1. a decision worth losing
step "1. seal a decision with its facts, under keys on disk"
rm -f "$DRILL/absent.jceks"

# The keystore and the wrapped keys are reset TOGETHER, and the first version of this script reset
# only the keystore. That is worth the paragraph, because the failure is one a real installation can
# reach.
#
# bre_dec.tenant_data_keys holds TDKs wrapped under a KEK, recorded by KEK IDENTIFIER. The dev KMS
# numbers its identifiers per tenant from 1, so a KMS that has lost its keys hands out
# kek/t_devstack/1 again — for a COMPLETELY DIFFERENT key. Every TDK already in the table still
# names that identifier, so the next decision fails with:
#
#   NOT_AUTHENTIC: a key presented as wrapped under kek/t_devstack/1 does not verify under it
#
# Refusing is the right answer, loudly — returning the wrong plaintext would be catastrophic — but
# it arrives as a 500 on the DECISION path, and the cause is a key identifier reused for new key
# material. A real KMS never reuses one; a dev stub that numbers from 1 does. So the two artefacts
# are reset as a pair, because either one alone is an inconsistent state.
#
# On a real installation this DELETE is unrecoverable data loss rather than a reset. It is safe here
# only because these are a devstack's synthetic applicants.
rm -f "$KEYSTORE"
# BOTH key tables. The tenant data keys are the obvious ones; the data principal keys (§9.2) are
# wrapped under the same KEK and keyed by principalRef, so re-running the drill with the same
# fixture finds last run's DPK rows and fails on those instead — which is how this took three
# attempts to get right.
psql_q "DELETE FROM bre_dec.tenant_data_keys" > /dev/null
psql_q "DELETE FROM bre_dec.principal_data_keys" > /dev/null

stop_service
start_service
grep -q "keys in .*kek.jceks" "$VAR/decision-service.log" \
  || fail "the service did not take the dev keystore. Is bre.crypto.dev-kms-keystore wired?"
RESPONSE="$(decide "dr-drill-$(date +%s)")"
DECISION_ID="$(printf '%s' "$RESPONSE" | python3 -c "
import json,sys; print(json.load(sys.stdin).get('decisionId',''))")"
[[ -n "$DECISION_ID" ]] || fail "no decision: $(printf '%s' "$RESPONSE" | head -c 300)"
ok "decision $DECISION_ID"
[[ -s "$KEYSTORE" ]] || fail "no keystore was written — the KEK is still only in memory"
ok "KEK persisted to $(basename "$KEYSTORE") ($(stat -c%s "$KEYSTORE") bytes)"

SEALED="$(psql_q "SELECT count(*) FROM bre_dec.decision_facts WHERE facts_envelope IS NOT NULL")"
[[ "$SEALED" -ge 1 ]] || fail "no sealed fact snapshot was stored (is store-facts on?)"
TDKS="$(psql_q "SELECT count(*) FROM bre_dec.tenant_data_keys")"
[[ "$TDKS" -ge 1 ]] || fail "no wrapped TDK in bre_dec.tenant_data_keys"
ok "$SEALED sealed snapshot(s), $TDKS wrapped tenant data key(s)"

step "2. baseline: the decision reads back and its facts decrypt"
CODE="$(explain "$DECISION_ID")"
[[ "$CODE" == "200" ]] || fail "explanation returned HTTP $CODE before anything was broken"
[[ "$(field matched)" == "True" ]] \
  || fail "the replay did not match BEFORE the drill: $(field mismatches). Fix this first."
[[ "$(facts_readable)" == "yes" ]] || fail "no facts in the baseline explanation"
ok "matched=true, facts present — this is what the restore has to reproduce"

# ---------------------------------------------------------------- 3. back up
#
# COPY rather than pg_dump, and not by preference. The embedded PostgreSQL this devstack downloads
# ships three binaries — initdb, pg_ctl, postgres — and no client tools at all, and the system
# pg_dump is version 16 against an 18.6 server, which it refuses outright ("aborting because of
# server version mismatch"). So the backup is taken with what the server itself provides.
#
# The table list comes from the CATALOGUE, never from a list in this script. That is the one part of
# the mechanism worth keeping on a real installation: a hand-written list of tables to back up is
# correct on the day it is written and silently wrong the first time somebody adds a table — and the
# table most likely to be added and forgotten is a key table, whose absence is undetectable until a
# restore. Enumerating means a new table is in the backup before anybody remembers it exists.
step "3. back up"
rm -rf "$DRILL/data"; mkdir -p "$DRILL/data"
# relispartition rather than a name pattern. The hot store is declaratively partitioned by date
# (§5.3), so pg_tables lists every leaf alongside its parent; backing up both would duplicate every
# row. Asking the catalogue which relations ARE partitions is exact, where "tablename NOT LIKE
# '%_p20%'" is a guess about a naming convention that a future migration is free to change.
mapfile -t TABLES < <(psql_q "
  SELECT n.nspname || '.' || c.relname
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname IN ('bre_dec','bre_ctl')
     AND c.relkind IN ('r','p')
     AND NOT c.relispartition
   ORDER BY 1")
[[ "${#TABLES[@]}" -gt 0 ]] || fail "no tables found to back up"
for table in "${TABLES[@]}"; do
  psql -w "$PG_PSQL_URI" -qX -v ON_ERROR_STOP=1 \
    -c "\\copy (SELECT * FROM $table) TO '$DRILL/data/$table.csv' WITH (FORMAT csv, HEADER true)"
done
printf '%s\n' "${TABLES[@]}" > "$DRILL/manifest.txt"
cp "$KEYSTORE" "$DRILL/kek.jceks.backup"
ok "${#TABLES[@]} tables ($(du -sh "$DRILL/data" | cut -f1)), plus the key material as a SEPARATE artefact"

# The check that would have caught the original design defect — key stores as HashMaps — from the
# backup side, and the one most likely to catch a future regression.
step "4. does the backup actually contain the keys?"
for required in bre_dec.tenant_data_keys bre_dec.principal_data_keys bre_dec.decision_facts bre_dec.decisions; do
  grep -qx "$required" "$DRILL/manifest.txt" \
    || fail "$required is NOT in the backup. Restoring it would produce unreadable data."
  [[ -s "$DRILL/data/$required.csv" ]] \
    || fail "$required is in the manifest but its file is empty"
done
ok "the key stores are in the backup (ciphertext only — unwrapping still needs the KMS)"
note "the KEK is NOT in it and must not be. It is the separate artefact copied above."

# ---------------------------------------------------------------- 5. destroy
step "5. destroy the database"
stop_service
# client_min_messages, or the cascade prints sixty NOTICE lines and buries every ✓ above it.
#
# public.flyway_schema_history goes too, and forgetting it cost a run. The registry keeps its
# migration history in `public` — not in the schema it manages — so dropping bre_ctl leaves a
# history table claiming version 16 is applied. Flyway then reports "Schema public is up to date.
# No migration necessary", creates nothing, and every restore step afterwards fails with "schema
# bre_ctl does not exist". On a real restore onto fresh infrastructure this does not arise; onto
# REUSED infrastructure, which is what a drill and a partial recovery both are, it does, and the
# error arrives several steps away from its cause. (bre_dec keeps its history inside bre_dec, so it
# re-migrates on its own.)
#
# The ROLES go too, and this one is a finding rather than a drill detail. bre_ctl V1 does a plain
# `CREATE ROLE bre_ctl_owner NOLOGIN` — and a role is a CLUSTER object, not a schema object, so
# dropping the schema leaves it behind and the migration fails on the next run with:
#
#   ERROR: role "bre_ctl_owner" already exists
#
# Which means: a restore onto a cluster that still has these roles — a surviving cluster, a second
# attempt after a failed restore, a standby being rebuilt — fails at V1. A restore onto genuinely
# fresh infrastructure does not, which is why nobody has hit it. It is written up in
# docs/runbooks/dr-drill.md: either restore onto a fresh cluster, or drop the roles first. Fixing V1
# is not the answer — it is applied everywhere and editing it would break Flyway's checksums.
psql -w "$PG_PSQL_URI" -q -v ON_ERROR_STOP=1 \
  -c "SET client_min_messages = warning" \
  -c "DROP SCHEMA IF EXISTS bre_dec CASCADE" \
  -c "DROP SCHEMA IF EXISTS bre_ctl CASCADE" \
  -c "DROP TABLE IF EXISTS public.flyway_schema_history" \
  -c "DROP OWNED BY bre_ctl_owner, bre_ctl_app, bre_ctl_platform, bre_ctl_auditor, bre_ctl_authn CASCADE" \
  -c "DROP ROLE IF EXISTS bre_ctl_owner, bre_ctl_app, bre_ctl_platform, bre_ctl_auditor, bre_ctl_authn"
[[ "$(psql_q "SELECT count(*) FROM information_schema.schemata WHERE schema_name='bre_dec'")" == "0" ]] \
  || fail "bre_dec survived the drop — the rest of this drill would prove nothing"
ok "bre_dec and bre_ctl are gone"

# ---------------------------------------------------------------- 6. restore, completely
step "6. restore everything, and read the decision"
RESTORE_START="$(date +%s)"

# Schema from the MIGRATIONS, data from the backup. Deliberately, and it is how a restore onto fresh
# infrastructure actually goes: the DDL is in version control and the backup carries rows. It also
# means a restore cannot resurrect a schema the migrations no longer describe.
REGISTRY_JAR="$(ls "$ROOT"/backend/services/policy-registry/build/libs/*.jar | grep -v plain | head -1)"
"$JAVA" -jar "$REGISTRY_JAR" \
  --spring.main.web-application-type=none \
  --spring.datasource.url="$PG_ADMIN_URL" \
  --spring.datasource.username=postgres \
  --spring.datasource.password= \
  --spring.flyway.enabled=true \
  --bre.registry.bootstrap-tenant= \
  > "$DRILL/migrate.log" 2>&1 \
  || { tail -20 "$DRILL/migrate.log"; fail "the bre_ctl migrations failed during the restore"; }
psql -w "$PG_PSQL_URI" -q -v ON_ERROR_STOP=1 \
  -c "ALTER ROLE bre_ctl_app LOGIN PASSWORD 'devstack-only'" > /dev/null
start_service   # brings bre_dec back, Flyway on startup
stop_service    # ... and stops again, because rows go in before anything reads them

# session_replication_role = replica, so rows go back in whatever order the catalogue listed them
# rather than in a dependency order this script would have to work out and keep right. Flyway's own
# history table is excluded: it was just written by the migration above, and restoring the backup's
# copy over it would replace live checksums with the backup's.
for table in "${TABLES[@]}"; do
  case "$table" in *flyway_schema_history*) continue ;; esac
  psql -w "$PG_PSQL_URI" -qX -v ON_ERROR_STOP=1 \
    -c "SET session_replication_role = replica" \
    -c "\\copy $table FROM '$DRILL/data/$table.csv' WITH (FORMAT csv, HEADER true)" \
    > /dev/null || fail "restoring $table failed"
done
cp "$DRILL/kek.jceks.backup" "$KEYSTORE"
start_service
RESTORE_SECONDS=$(( $(date +%s) - RESTORE_START ))

CODE="$(explain "$DECISION_ID")"
[[ "$CODE" == "200" ]] || { head -c 400 "$DRILL/explanation.json"; fail "explanation returned HTTP $CODE after the restore"; }
[[ "$(field matched)" == "True" ]] \
  || fail "the replay does not match after the restore: $(field mismatches)"
[[ "$(facts_readable)" == "yes" ]] \
  || fail "the decision survived but its facts did not — the backup is not sufficient"
ok "matched=true and the facts decrypted. RPO 0 for this acknowledged decision."
ok "restore to serving: ${RESTORE_SECONDS}s (control-plane RTO budget is 30 min, NFR-DR-01)"

# ---------------------------------------------------------------- 7. the incomplete restore
step "7. now restore the database WITHOUT the key material"
stop_service
mv "$KEYSTORE" "$DRILL/kek.jceks.withheld"
start_service --without-keystore

# The decision record must still be there and still be listable. Losing the KMS must not look like
# losing the decision: a lender is required to retain the decision, and §9.5 is built on exactly
# this separation — destroying keys destroys the personal data and leaves the decision auditable.
STILL_THERE="$(psql_q "SELECT count(*) FROM bre_dec.decisions WHERE decision_id = '$DECISION_ID'")"
[[ "$STILL_THERE" == "1" ]] || fail "the decision row vanished, which is a different bug"
ok "the decision record is intact — the database was never the problem"

CODE="$(explain "$DECISION_ID")"
if [[ "$(facts_readable)" == "yes" ]]; then
  fail "THE FACTS DECRYPTED WITHOUT THE KEK. Either the drill did not really withhold it, or"\
       "something is storing key material where it must not be. Stop and investigate."
fi
# 409 and a reason, not a 500. The first run of this drill got a bare 500 — indistinguishable from
# a null pointer to whoever is reading it during a recovery — and that is why the service now
# answers CryptoException properly. 409 because no retry will ever help; 503 would be a lie that
# sends a caller round a loop.
[[ "$CODE" == "409" ]] \
  || fail "expected 409 for an unopenable snapshot, got HTTP $CODE. An operator in the middle of a
     restore has to be able to tell 'the keys did not come back' from 'the service is broken'."
REASON="$(python3 -c "
import json
try: print(json.load(open('$DRILL/explanation.json')).get('reason','-'))
except Exception: print('-')")"
ok "the facts are unreadable: HTTP 409, reason $REASON — permanently, and no restore recovers them"
note "that is the finding this drill exists to produce. An operator who restores the database and"
note "not the key material gets exactly this, and nothing in the restore itself reports a problem."

step "8. put it back"
mv "$DRILL/kek.jceks.withheld" "$KEYSTORE"
stop_service
start_service
CODE="$(explain "$DECISION_ID")"
[[ "$CODE" == "200" && "$(facts_readable)" == "yes" ]] \
  || fail "the facts did not come back with the keystore — then the keystore was not the variable"
ok "readable again. The data was never damaged; only the key was absent."

cat <<REPORT

$(printf '\033[1mEvidence\033[0m')
  decision                $DECISION_ID
  sealed snapshots         $SEALED, under $TDKS wrapped tenant data key(s)
  backup artefacts         ${#TABLES[@]} table files (both schemas) + kek.jceks (key material, separate)
  restore to serving       ${RESTORE_SECONDS}s
  complete restore         decision readable, facts decrypted, replay matched
  database-only restore    decision readable, facts PERMANENTLY unreadable
  taken at                 $(date -Iseconds)

$(printf '\033[1mProven\033[0m')
  NFR-DR-02  a backup of bre_dec + bre_ctl plus the key material restores to readable data, and
             the key stores are verifiably inside it — enumerated from the catalogue, not listed
             by hand, so a table added later cannot be missed.
  NFR-DR-01  RPO 0 for an acknowledged decision across a total loss of both schemas.
  §9.5       key destruction is irreversible and leaves the decision record auditable — the same
             mechanism as erasure, seen from the backup side.

$(printf '\033[1mNOT proven, and not claimed\033[0m')
  Region failover of the data plane (§10.2) — needs a second region. docs/runbooks/dr-drill.md
  has the procedure and the evidence template; this machine has one region.
  PITR and 35-day retention (NFR-DR-02) — needs WAL archiving and object storage (BD4).
  Archive restore with Object Lock / WORM — needs versioned object storage.
  Restore under production load, and the control plane's warm standby promotion.

M6-03 is NOT complete. Its backup-restore half is, with the evidence above.
REPORT
