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
#   ./devstack/up.sh --stop     stop everything
#   ./devstack/up.sh --seed     seed only: keys, bundle, manifest. No service.
#
# What it does:
#   1. builds the CLI and the service
#   2. generates LOCAL signing keys — never production keys (§8.6)
#   3. bundles and signs the reference policy WITH its resolved assets
#   4. writes and signs an Active Pointer Manifest pointing at that bundle
#   5. starts decision-service with only the PUBLIC halves configured
#   6. posts the three reference fixtures, so "it started" and "it decides correctly" are
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
  stop_pid "$REGISTRY_PID_FILE" "policy-registry"
  # PostgreSQL last: the registry holds connections to it, and killing the database first makes
  # the service log a wall of connection errors on its way out.
  stop_pid "$POSTGRES_PID_FILE" "embedded PostgreSQL"
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
# JDK if that is older. Gradle provisions its own toolchain; find one rather than telling the
# developer to go and install something.
find_jdk25() {
  if [[ -n "${JAVA_HOME:-}" ]] && [[ "$("$JAVA_HOME/bin/java" -version 2>&1)" == *'"25'* ]]; then
    printf '%s' "$JAVA_HOME"; return 0
  fi
  if [[ "$(java -version 2>&1)" == *'"25'* ]]; then
    printf '%s' ""; return 0   # already on PATH
  fi
  for candidate in "$HOME"/.jdks/jdk-25* "$HOME"/.gradle/jdks/*25* /usr/lib/jvm/*25*; do
    [[ -x "$candidate/bin/java" ]] && { printf '%s' "$candidate"; return 0; }
  done
  return 1
}

JDK="$(find_jdk25)" || fail "no Java 25 found. The build targets 25 (ADR-012); run
  ./gradlew build  in backend/ once — Gradle provisions a toolchain under ~/.jdks — or set
  JAVA_HOME to a JDK 25."
[[ -n "$JDK" ]] && export JAVA_HOME="$JDK"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

# Stop whatever is already running before starting another. Without this, a second run leaves the
# first process holding the port, the new one fails to bind, and the script reports "the service
# did not start" — which sent me looking for a startup bug that did not exist. The pid file only
# knows about the most recent run, so match on the jar as well.
WITH_REGISTRY=false
[[ "${1:-}" == "--registry" ]] && WITH_REGISTRY=true

if [[ -f "$PID_FILE" ]] || pgrep -f "decision-service.jar" >/dev/null 2>&1 \
   || [[ -f "$REGISTRY_PID_FILE" ]] || pgrep -f "policy-registry.jar" >/dev/null 2>&1; then
  log "an instance is already running; stopping it first"
  stop
  pkill -f "decision-service.jar" 2>/dev/null || true
  pkill -f "policy-registry.jar" 2>/dev/null || true
  pkill -f "EmbeddedPostgresMain" 2>/dev/null || true
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
  log "starting embedded PostgreSQL (downloads a real server binary on first run)"
  rm -f "$PG_PROPERTIES"
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

  # "It started" and "you can sign in" are different claims, so prove the second one.
  LOGIN="$(curl -sS -X POST "http://127.0.0.1:$REGISTRY_PORT/v1/auth/login" \
      -H 'Content-Type: application/json' \
      -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}")" \
    || fail "the login request failed"
  printf '%s' "$LOGIN" | grep -q '"token"' \
    || { printf '%s\n' "$LOGIN"; fail "the bootstrapped administrator could not sign in"; }
  log "administrator can sign in: $ADMIN_EMAIL"
fi

log "starting decision-service on port $PORT"
JAR="$(ls "$ROOT"/backend/services/decision-service/build/libs/*.jar | grep -v plain | head -1)"

nohup "$JAVA" -jar "$JAR" \
  --server.port="$PORT" \
  --bre.env-code=LOCAL \
  --bre.policy.bundles="$VAR/bundles" \
  --bre.policy.manifest="$VAR/manifest.json" \
  --bre.policy.manifest-key="$(cat "$VAR/manifest-signing.x509")" \
  --bre.policy.trusted-keys.$KID="$(cat "$VAR/bundle-signing.x509")" \
  --bre.decision-log.mode=IN_MEMORY \
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

# "It started" and "it decides correctly" are different claims. Post all three reference
# fixtures and print what came back: a devstack that comes up serving a policy that refers
# everything is worse than one that does not come up, because it looks fine.
log "posting the three reference fixtures"
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
    stop       ./devstack/up.sh --stop

  Expected above: APPROVE for the clean group, DECLINE for the negative-list knockout,
  REFER for the soft-cap threshold. Anything else is a regression.

  The decision log is IN_MEMORY — not durable, every decision is lost on restart. For the
  durable path (D6: the decision, its idempotency key and its outbox event in ONE transaction)
  run the bre_dec migrations against your own PostgreSQL and set
  --bre.decision-log.mode=POSTGRES --spring.flyway.enabled=true. The service refuses to start
  in POSTGRES mode without a DataSource rather than silently falling back.

  X-Tenant-Id IS NOT AUTHENTICATED. There is no authentication anywhere yet (M3-09), so this
  must not be reachable from a network you do not control.

INFO
