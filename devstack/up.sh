#!/usr/bin/env bash
#
# devstack — bring the BRE up locally with one command (M2-07).
#
# No Docker, deliberately. This host has none, and neither do plenty of developer laptops under
# corporate policy; requiring it would make "one command" mean "one command, after an afternoon
# with IT".
#
#   ./devstack/up.sh            build, seed, start, and prove it decides
#   ./devstack/up.sh --registry  also start PostgreSQL and the policy registry, with an admin
#                                account you can sign in to the studio with
#   ./devstack/up.sh --deliver   as --registry, plus a DURABLE decision log and delivery: a
#                                stand-in downstream system is started and the relay pushes every
#                                decision to it. Then ./devstack/deliver-demo.sh shows push and
#                                fetch working, including what happens when the downstream breaks.
#   ./devstack/up.sh --sandbox   as --registry, plus a `sandbox` tenant with one account per role
#                                (author, reviewer, approver, viewer, auditor) so maker-checker can
#                                be walked without creating accounts by hand. Password is printed
#                                to the registry log. Never enable this in production.
#   ./devstack/up.sh --stop     stop everything
#   ./devstack/up.sh --seed     seed only: keys, bundle, manifest. No service.
#
# What it does:
#   1. builds the CLI and the service
#   2. generates LOCAL signing keys — never production keys (§8.6)
#   3. bundles and signs the reference policy WITH its resolved assets
#   4. writes and signs an Active Pointer Manifest pointing at that bundle
#   5. starts decision-service with only the PUBLIC halves configured
#   6. posts every reference fixture, so "it started" and "it decides correctly" are
#      not confused with each other
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VAR="$ROOT/devstack/var"
PORT="${BRE_PORT:-8080}"
TENANT="t_devstack"
KID="devstack-2026"
PID_FILE="$VAR/decision-service.pid"
REGISTRY_PID_FILE="$VAR/policy-registry.pid"
POSTGRES_PID_FILE="$VAR/postgres.pid"
# Declared here with the others rather than beside its flag: stop() reads it, and stop() runs
# before the flag parsing does when a previous instance is still up.
RECEIVER_PID_FILE="$VAR/receiver.pid"
PG_PROPERTIES="$VAR/postgres.properties"
REGISTRY_PORT="${BRE_REGISTRY_PORT:-8081}"
ADMIN_EMAIL="${BRE_ADMIN_EMAIL:-admin@lender.local}"
ADMIN_PASSWORD="${BRE_ADMIN_PASSWORD:-devstack-admin-password}"

log()  { printf '\033[36m==>\033[0m %s\n' "$*"; }
fail() { printf '\033[31m!!!\033[0m %s\n' "$*" >&2; exit 1; }

stop_pid() {
  local file="$1" what="$2"
  if [[ -f "$file" ]]; then
    local pid; pid="$(cat "$file")"
    if kill -0 "$pid" 2>/dev/null; then
      log "stopping $what (pid $pid)"
      kill "$pid" 2>/dev/null || true
      for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
      kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$file"
  fi
}

stop() {
  stop_pid "$RECEIVER_PID_FILE" "the stand-in downstream"
  stop_pid "$REGISTRY_PID_FILE" "policy-registry"
  # PostgreSQL last: the registry holds connections to it, and killing the database first makes
  # the service log a wall of connection errors on its way out.
  stop_pid "$POSTGRES_PID_FILE" "embedded PostgreSQL"
  # The database runs as a Gradle task, so the recorded pid is the server and the WRAPPER that
  # launched it survives — holding the port, and named after neither PostgreSQL nor this script.
  # The startup path already pkills this; stop() did not, so `--stop` left a process behind that
  # made the NEXT start fail with "finished with non-zero exit value 143", which reads like a
  # crash rather than a port clash. Half a fix is how the same defect arrives twice.
  pkill -f "devPostgres" 2>/dev/null || true
  rm -f "$PG_PROPERTIES"
  if [[ -f "$PID_FILE" ]]; then
    pid="$(cat "$PID_FILE")"
    if kill -0 "$pid" 2>/dev/null; then
      log "stopping decision-service (pid $pid)"
      kill "$pid" 2>/dev/null || true
      for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
      kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
  fi
  log "stopped"
}

[[ "${1:-}" == "--stop" ]] && { stop; exit 0; }

# The build targets Java 25 (ADR-012), so the CLI and the service JAR cannot run on the system
# JDK if that is older. Shared with the other devstack scripts that launch a JAR.
source "$ROOT/devstack/jdk25.sh"
require_jdk25

# Stop whatever is already running before starting another. Without this, a second run leaves the
# first process holding the port, the new one fails to bind, and the script reports "the service
# did not start" — which sent me looking for a startup bug that did not exist. The pid file only
# knows about the most recent run, so match on the jar as well.
WITH_REGISTRY=false
WITH_SANDBOX=false
WITH_DELIVERY=false
RECEIVER_PORT="${BRE_RECEIVER_PORT:-9099}"
for arg in "$@"; do
  case "$arg" in
    --registry) WITH_REGISTRY=true ;;
    # Durable decision log plus delivery: the decision service runs in POSTGRES mode (which is
    # what creates outbox rows at all), a stand-in downstream system is started, and the relay is
    # pointed at it. Then both ways out of the outbox can be shown working.
    --deliver)  WITH_REGISTRY=true; WITH_DELIVERY=true ;;
    # Seeds the `sandbox` tenant with one account per role, so maker-checker can be walked without
    # creating accounts by hand. Separate from --registry because it is a different decision: the
    # registry is infrastructure, five accounts sharing a password is a test fixture.
    --sandbox)  WITH_REGISTRY=true; WITH_SANDBOX=true ;;
  esac
done

if [[ -f "$PID_FILE" ]] || pgrep -f "decision-service.jar" >/dev/null 2>&1 \
   || [[ -f "$REGISTRY_PID_FILE" ]] || pgrep -f "policy-registry.jar" >/dev/null 2>&1; then
  log "an instance is already running; stopping it first"
  stop
  pkill -f "decision-service.jar" 2>/dev/null || true
  pkill -f "policy-registry.jar" 2>/dev/null || true
  pkill -f "EmbeddedPostgresMain" 2>/dev/null || true
  # The database runs as a Gradle task, so a stale run survives as a wrapper process rather than
  # anything named after PostgreSQL. Missing it cost an hour: stop() killed the pid it recorded,
  # the three-hour-old wrapper from an earlier run kept the port, and the new devPostgres died
  # with "finished with non-zero exit value 143" — which reads like a crash, not a port clash.
  pkill -f "devPostgres" 2>/dev/null || true
  pkill -f "devstack/receiver.py" 2>/dev/null || true
  sleep 1
fi

mkdir -p "$VAR/bundles"

log "building"
BUILD_TARGETS=(:tools:bre-cli:installDist :services:decision-service:bootJar)
$WITH_REGISTRY && BUILD_TARGETS+=(:services:policy-registry:bootJar :services:policy-registry:compileDevstackJava)
(cd "$ROOT/backend" && ./gradlew -q "${BUILD_TARGETS[@]}") \
  || fail "the build failed; devstack will not start a service that does not compile"

BRE="$ROOT/backend/tools/bre-cli/build/install/bre/bin/bre"

if [[ ! -f "$VAR/bundle-signing.pkcs8" ]]; then
  log "generating LOCAL signing keys (a production key never leaves the KMS — §8.6)"
  # Two keys, not one. A compromise of the thing that says WHAT TO SERVE must not also be a
  # compromise of the thing that says WHAT A POLICY CONTAINS (§8.6).
  "$BRE" keygen "$VAR/bundle-signing.pkcs8"   "$VAR/bundle-signing.x509"   >/dev/null
  "$BRE" keygen "$VAR/manifest-signing.pkcs8" "$VAR/manifest-signing.x509" >/dev/null
fi

log "bundling the reference policy with its resolved assets"
# psf bundle refuses to sign a policy that fails its own static checks, so a devstack that comes
# up is a devstack serving a policy that passed them.
BUNDLE_OUT="$("$BRE" psf bundle "$ROOT/reference-policy/mfi-jlg-credit-uw.psf.yaml" \
  --assets "$ROOT/reference-policy/assets.json" \
  --key "$VAR/bundle-signing.pkcs8" --kid "$KID" -o "$VAR/bundle.json")" \
  || fail "the reference policy did not bundle:
$BUNDLE_OUT"
HASH="$(printf '%s\n' "$BUNDLE_OUT" | awk '/^ok/ {print $2; exit}')"
[[ -n "$HASH" ]] || fail "could not read the bundle hash from: $BUNDLE_OUT"
cp "$VAR/bundle.json" "$VAR/bundles/$(printf '%s' "$HASH" | tr ':' '-' | tr 'A-Z' 'a-z').json"
log "bundle $HASH"

log "writing and signing the Active Pointer Manifest"
# Version 1 every time, which is correct here and would NOT be in production: a pod refuses a
# manifest whose version is not greater than the one it holds (§8.5), and devstack starts with a
# pod that holds none. A registry increments per cell.
cat > "$VAR/manifest-unsigned.json" <<JSON
{
  "format": "bre-manifest/1",
  "cellId": 1,
  "manifestVersion": 1,
  "issuedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "entries": [
    {
      "tenantId": "$TENANT",
      "environment": "PROD",
      "policyCode": "MFI_JLG_CREDIT_UW",
      "timeZone": "Asia/Kolkata",
      "pinningAllowed": true,
      "champion": { "deploymentId": "devstack-1", "bundleHash": "$HASH" },
      "challengers": [],
      "servable": [
        { "bundleHash": "$HASH", "deploymentId": "devstack-1", "championUntil": null }
      ]
    }
  ]
}
JSON
"$BRE" manifest sign "$VAR/manifest-unsigned.json" \
  --key "$VAR/manifest-signing.pkcs8" --kid "manifest-cell-1" -o "$VAR/manifest.json" \
  || fail "the manifest could not be signed"

if [[ "${1:-}" == "--seed" ]]; then
  log "seeded. Decide offline without a service:"
  echo
  echo "  $BRE decide $VAR/bundle.json \\"
  echo "      --facts $ROOT/reference-policy/fixtures/approve-5-member-jlg.json \\"
  echo "      --pub $VAR/bundle-signing.x509 --kid $KID --as-of 2026-10-01"
  echo
  exit 0
fi

if $WITH_REGISTRY; then
  # The registry's manifests and bundles go with the database, and they have to go TOGETHER.
  #
  # The embedded PostgreSQL is a fresh temporary cluster on every run, so bre_ctl starts empty and
  # the registry re-issues manifest versions from 1. The FILESYSTEM is not fresh — and a pod that
  # starts before the deploy reads whatever manifest the last run left, which is also version 1.
  # §8.1 versions are monotonic, so the pod correctly refuses the new version 1 as not newer, and
  # then serves the PREVIOUS RUN'S POLICY while the registry reports the new one ACTIVE.
  #
  # That is not theoretical. It happened, and it approved an application the current policy
  # declines: a rule added to the reference policy was simply absent from the bundle being served,
  # and the only reason anybody noticed is that e2e.sh step 13 decides the fixtures and compares.
  # Deleting the database's state without deleting the state that references it is the bug.
  log "starting embedded PostgreSQL (downloads a real server binary on first run)"
  rm -f "$PG_PROPERTIES"
  # Only the manifests. Bundles are content-addressed, so a stale one cannot be served by
  # accident — the manifest is the only thing that says which hash is the champion, and the
  # manifest is therefore the only thing that can be stale. Deleting $VAR/bundles here would also
  # delete the bundle the seeding step signed thirty seconds earlier, which is a different bug.
  rm -rf "$VAR/manifests"
  (cd "$ROOT/backend" && nohup ./gradlew -q :services:policy-registry:devPostgres \
      --args="$PG_PROPERTIES" > "$VAR/postgres.log" 2>&1 & echo $! > "$POSTGRES_PID_FILE")
  for _ in $(seq 1 180); do
    [[ -s "$PG_PROPERTIES" ]] && break
    sleep 1
  done
  [[ -s "$PG_PROPERTIES" ]] || { tail -20 "$VAR/postgres.log"; fail "PostgreSQL did not start"; }

  PG_ADMIN_URL="$(grep '^admin.url=' "$PG_PROPERTIES" | cut -d= -f2-)"
  PG_PSQL_URI="$(grep '^psql.uri=' "$PG_PROPERTIES" | cut -d= -f2-)"
  log "PostgreSQL ready"

  # Migrations run as the SUPERUSER and the service runs as bre_ctl_app, which is the production
  # shape and the only one in which row-level security is actually exercised: a superuser bypasses
  # RLS entirely, so running the service as one would hide every tenant-isolation mistake.
  log "migrating bre_ctl and granting the application role a login"
  REGISTRY_JAR="$(ls "$ROOT"/backend/services/policy-registry/build/libs/*.jar | grep -v plain | head -1)"
  "$JAVA" -jar "$REGISTRY_JAR" \
    --spring.main.web-application-type=none \
    --spring.datasource.url="$PG_ADMIN_URL" \
    --spring.datasource.username=postgres \
    --spring.datasource.password= \
    --spring.flyway.enabled=true \
    --bre.registry.bootstrap-tenant= \
    > "$VAR/migrate.log" 2>&1 \
    || { tail -30 "$VAR/migrate.log"; fail "the bre_ctl migrations failed"; }

  # -w so psql NEVER prompts. Without it a connection problem turns into a password prompt on a
  # script's stdin and the whole thing hangs with no error anywhere.
  psql -w "$PG_PSQL_URI" -q -v ON_ERROR_STOP=1 \
    -c "ALTER ROLE bre_ctl_app LOGIN PASSWORD 'devstack-only'" \
    || fail "could not grant bre_ctl_app a login. Is psql on PATH?"

  if [[ ! -f "$VAR/registry-bundle.pkcs8" ]]; then
    log "generating the registry's LOCAL signing keys (two of them — §8.6 keeps them separate)"
    "$BRE" keygen "$VAR/registry-bundle.pkcs8"   "$VAR/registry-bundle.x509"   >/dev/null
    "$BRE" keygen "$VAR/registry-manifest.pkcs8" "$VAR/registry-manifest.x509" >/dev/null
  fi

  log "starting policy-registry on port $REGISTRY_PORT"
  # Truncated HERE, in the parent, before the child is forked. `nohup ... > file &` truncates in
  # the CHILD, and the wait loop below can reach its first grep before that happens — so it
  # matched the PREVIOUS run's "FIRST ADMINISTRATOR CREATED", decided the registry was ready at
  # t=0, and curled a port nothing was listening on yet. The symptom was "the login request
  # failed / connection refused" against a registry that was in fact starting normally.
  : > "$VAR/policy-registry.log"
  nohup "$JAVA" -jar "$REGISTRY_JAR" \
    --server.port="$REGISTRY_PORT" \
    --spring.datasource.url="$PG_ADMIN_URL" \
    --spring.datasource.username=bre_ctl_app \
    --spring.datasource.password=devstack-only \
    --spring.flyway.enabled=false \
    --bre.registry.cell-id=1 \
    --bre.registry.bundle-dir="$VAR/bundles" \
    --bre.registry.manifest-dir="$VAR/manifests" \
    --bre.registry.bundle-signing-key="$(cat "$VAR/registry-bundle.pkcs8")" \
    --bre.registry.manifest-signing-key="$(cat "$VAR/registry-manifest.pkcs8")" \
    --bre.registry.bootstrap-tenant=t_devstack \
    --bre.registry.bootstrap-tenant-name="Devstack Lender" \
    --bre.registry.bootstrap-admin-email="$ADMIN_EMAIL" \
    --bre.registry.bootstrap-admin-password="$ADMIN_PASSWORD" \
    --bre.registry.sandbox-enabled="$WITH_SANDBOX" \
    --bre.registry.sandbox-password=sandbox-password-1 \
    `# Decision lookup and the explanation viewer (M5-08). The studio reads decisions THROUGH the` \
    `# registry rather than from the decision service, because the Decision API trusts X-Tenant-Id` \
    `# and has no authentication — a browser calling it directly could read any tenant by editing` \
    `# one header. The registry supplies that header from the session instead.` \
    --bre.registry.decision-service-url="http://127.0.0.1:$PORT" \
    `# The OpenAPI document and the Swagger UI, reachable without a session. On here and off by` \
    `# default in application.yaml: locally it is the integration portal M6-05 asks for, and in a` \
    `# deployment it is a complete inventory of the control plane handed to anybody who can reach` \
    `# the port. Serve it from the gateway to named integrators there, or send them the committed` \
    `# file in docs/api/ instead.` \
    --bre.openapi.public=true \
    > "$VAR/policy-registry.log" 2>&1 &
  echo $! > "$REGISTRY_PID_FILE"

  # Waits for the BOOTSTRAP line, not for "Started". The administrator is created on
  # ApplicationReadyEvent, which fires after startup completes — so a script that logs in as soon
  # as the port is listening races the account into existence and gets a 401 that looks exactly
  # like a wrong password. Found that way.
  for _ in $(seq 1 90); do
    grep -qE "FIRST ADMINISTRATOR CREATED|bootstrap skipped" "$VAR/policy-registry.log" 2>/dev/null \
      && break
    grep -qi "APPLICATION FAILED TO START" "$VAR/policy-registry.log" 2>/dev/null \
      && { tail -40 "$VAR/policy-registry.log"; fail "the registry failed to start"; }
    sleep 1
  done
  grep -q "Started PolicyRegistryApplication" "$VAR/policy-registry.log" \
    || { tail -40 "$VAR/policy-registry.log"; fail "the registry did not start in 90s"; }
  grep -qE "FIRST ADMINISTRATOR CREATED|bootstrap skipped" "$VAR/policy-registry.log" \
    || { tail -40 "$VAR/policy-registry.log"; fail "the registry started but never bootstrapped"; }

  # "It started" and "you can sign in" are different claims, so prove the second one. Retried,
  # because the log line and the listening socket are not the same instant: a single-shot curl
  # here reports a wrong password for a service that is two seconds from ready.
  LOGIN=""
  for _ in $(seq 1 30); do
    LOGIN="$(curl -sS -X POST "http://127.0.0.1:$REGISTRY_PORT/v1/auth/login" \
        -H 'Content-Type: application/json' \
        -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" 2>/dev/null)" || true
    printf '%s' "$LOGIN" | grep -q '"token"' && break
    sleep 1
  done
  printf '%s' "$LOGIN" | grep -q '"token"' \
    || { printf '%s\n' "$LOGIN"; tail -20 "$VAR/policy-registry.log"; \
         fail "the bootstrapped administrator could not sign in"; }
  log "administrator can sign in: $ADMIN_EMAIL"
  log "API portal: http://127.0.0.1:$REGISTRY_PORT/swagger-ui.html (43 endpoints, no session needed)"
fi

log "starting decision-service on port $PORT"
JAR="$(ls "$ROOT"/backend/services/decision-service/build/libs/*.jar | grep -v plain | head -1)"

# WHICH manifest the pod watches, and WHOSE signature it trusts, depend on who is publishing.
#
# Without --registry the CLI builds and signs the bundle and writes $VAR/manifest.json, so the pod
# trusts the CLI's keys. With --registry the REGISTRY signs both, into $VAR/manifests/manifest.json
# — a different file and a different pair of keys. Pointing the pod at the CLI's manifest in
# registry mode is why a policy deployed through the API never reached it: everything worked and
# the two halves were watching different files.
if $WITH_REGISTRY; then
  POD_MANIFEST="$VAR/manifests/manifest.json"
  POD_MANIFEST_KEY="$(cat "$VAR/registry-manifest.x509")"
  POD_BUNDLE_KID="reg-local-2026-01"                 # bre.registry.bundle-signing-kid's default
  POD_BUNDLE_KEY="$(cat "$VAR/registry-bundle.x509")"
  # The registry has published nothing yet, so the pod starts with no manifest and refuses every
  # decision (§8.5) until e2e.sh deploys one. That is the correct behaviour, not a failure.
  mkdir -p "$VAR/manifests"
else
  POD_MANIFEST="$VAR/manifest.json"
  POD_MANIFEST_KEY="$(cat "$VAR/manifest-signing.x509")"
  POD_BUNDLE_KID="$KID"
  POD_BUNDLE_KEY="$(cat "$VAR/bundle-signing.x509")"
fi

if $WITH_DELIVERY; then
  log "starting the stand-in downstream system on port $RECEIVER_PORT"
  nohup python3 "$ROOT/devstack/receiver.py" "$RECEIVER_PORT" > "$VAR/receiver.out" 2>&1 &
  echo $! > "$RECEIVER_PID_FILE"
  for _ in $(seq 1 30); do
    grep -q "receiver listening" "$VAR/receiver.out" 2>/dev/null && break
    sleep 1
  done
  grep -q "receiver listening" "$VAR/receiver.out" \
    || { cat "$VAR/receiver.out"; fail "the stand-in downstream did not start"; }

  # POSTGRES mode is what creates outbox rows: IN_MEMORY has no outbox, so there is nothing to
  # deliver. Migrated as the superuser for the same reason bre_ctl is.
  DECISION_LOG_ARGS=(
    --bre.decision-log.mode=POSTGRES
    --spring.datasource.url="$PG_ADMIN_URL"
    --spring.datasource.username=postgres
    --spring.datasource.password=
    --spring.flyway.enabled=true
    --bre.outbox.endpoint="http://127.0.0.1:$RECEIVER_PORT/decisions"
    --bre.outbox.authorization="Bearer devstack-downstream-token"
    --bre.outbox.poll-ms=1000
    --bre.outbox.max-attempts=4
  )
else
  DECISION_LOG_ARGS=(--bre.decision-log.mode=IN_MEMORY)
fi

# Same truncation, for the same reason: the loop below waits for a line in this file.
: > "$VAR/decision-service.log"
nohup "$JAVA" -jar "$JAR" \
  --server.port="$PORT" \
  --bre.env-code=LOCAL \
  --bre.policy.bundles="$VAR/bundles" \
  --bre.policy.manifest="$POD_MANIFEST" \
  --bre.policy.manifest-key="$POD_MANIFEST_KEY" \
  --bre.policy.trusted-keys.$POD_BUNDLE_KID="$POD_BUNDLE_KEY" \
  "${DECISION_LOG_ARGS[@]}" \
  > "$VAR/decision-service.log" 2>&1 &
echo $! > "$PID_FILE"

for _ in $(seq 1 90); do
  grep -q "Started DecisionServiceApplication" "$VAR/decision-service.log" 2>/dev/null && break
  grep -qi "APPLICATION FAILED TO START\|Error starting" "$VAR/decision-service.log" 2>/dev/null \
    && { tail -40 "$VAR/decision-service.log"; fail "the service failed to start"; }
  sleep 1
done
grep -q "Started DecisionServiceApplication" "$VAR/decision-service.log" \
  || { tail -40 "$VAR/decision-service.log"; fail "the service did not start in 90s"; }

if $WITH_REGISTRY; then
  # Nothing is deployed yet — the registry has published no manifest, so the pod is correctly
  # refusing every decision. e2e.sh authors the policy through the API, approves it, deploys it,
  # waits for the pod to adopt the manifest, and only then decides. That walk IS the test.
  log "handing over to devstack/e2e.sh for the end-to-end walk"
  echo
  exec "$ROOT/devstack/e2e.sh"
fi

# "It started" and "it decides correctly" are different claims. Post every reference fixture and
# print what came back: a devstack that comes up serving a policy that refers everything is worse
# than one that does not come up, because it looks fine.
#
# COUNTED, not asserted. This said "the three reference fixtures" and then listed four, because a
# fixture was added for the regulatory obligation cap and the sentence was not. A hard-coded count
# beside a loop over a directory is wrong the first time somebody adds a file, and a startup banner
# that miscounts its own output is the kind of small lie that makes a reader stop trusting the rest.
FIXTURE_COUNT=$(ls -1 "$ROOT"/reference-policy/fixtures/*.json 2>/dev/null | wc -l)
log "posting the $FIXTURE_COUNT reference fixtures"
echo
printf '  %-34s %-10s %s\n' FIXTURE DECISION 'REASONS'
n=0
for fixture in "$ROOT"/reference-policy/fixtures/*.json; do
  n=$((n + 1))
  body="$(python3 - "$fixture" "$TENANT" "$n" <<'PY'
import json, sys
facts = json.load(open(sys.argv[1]))
print(json.dumps({
    "tenantId": sys.argv[2],
    "requestId": "devstack-%s" % sys.argv[3],
    "asOf": "2026-10-01",
    "environment": "PROD",
    "policyCode": "MFI_JLG_CREDIT_UW",
    "facts": facts,
}))
PY
)"
  response="$(curl -sS -X POST "http://127.0.0.1:$PORT/v1/decisions" \
      -H 'Content-Type: application/json' -d "$body")" || fail "the request failed"
  printf '  %-34s %s\n' "$(basename "$fixture" .json)" \
    "$(printf '%s' "$response" | python3 -c "
import json, sys
d = json.load(sys.stdin)
if 'decision' not in d:
    print('ERROR  ' + json.dumps(d)[:160]); raise SystemExit
reasons = sorted({r.get('reasonCode') for r in d.get('results', []) if r.get('reasonCode')})
print('%-10s %s' % (d['decision'], ', '.join(reasons)))
")"
done

cat <<INFO

  devstack is up.

    decisions  http://127.0.0.1:$PORT
    tenant     $TENANT
    policy     MFI_JLG_CREDIT_UW
    bundle     $HASH
    log        $VAR/decision-service.log
    stop       $0 --stop

  Expected above: APPROVE for the clean group, DECLINE for the negative-list knockout, DECLINE
  for the household over the regulatory obligation cap, and REFER for the soft-cap threshold.
  Anything else is a regression.

  THERE IS NO POLICY REGISTRY AND NO STUDIO IN THIS MODE, and if you came here to open the
  Rule Studio that is the thing to know. This started the decision service alone: it serves
  decisions against the bundle above and nothing else. The studio signs in against the registry,
  so with no registry running it can only report that it cannot reach the server.

    $0 --registry
        PostgreSQL, the policy registry on 8081, an admin account, and the full walk — author,
        approve, deploy, decide, audit. Then, for the studio:
            cd ../BRE_UI && VITE_USE_FIXTURES=false npm run dev
        and sign in as admin@lender.local / devstack-admin-password

    $0 --deliver
        As --registry, plus a DURABLE decision log. Needed for the explanation viewer, replay
        and the back-test, which all read the hot store.

    $0 --sandbox
        As --registry, plus one account per role, so maker-checker can be walked without
        creating accounts by hand.

  The decision log is IN_MEMORY — not durable, every decision is lost on restart. For the
  durable path (D6: the decision, its idempotency key and its outbox event in ONE transaction)
  run the bre_dec migrations against your own PostgreSQL and set
  --bre.decision-log.mode=POSTGRES --spring.flyway.enabled=true. The service refuses to start
  in POSTGRES mode without a DataSource rather than silently falling back.

  X-Tenant-Id IS NOT AUTHENTICATED. There is no authentication anywhere yet (M3-09), so this
  must not be reachable from a network you do not control.

INFO
