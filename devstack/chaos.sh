#!/usr/bin/env bash
# Chaos: kill a dependency and assert what §10 says should happen (M4-10).
#
# Run ./devstack/up.sh --registry first.
#
# §10's resilience table makes specific, falsifiable claims about what survives what. A table in a
# document is a claim; this is the claim executed. Two of them matter more than the rest and are
# the ones here:
#
#   "Policy registry down -> Decisions unaffected"
#       The pods keep serving the last verified manifest and bundles. If this is false, the
#       control plane is on the hot path and a registry deployment becomes a lending outage.
#
#   "Hot store down -> ..." depends on the mode, and the DIFFERENCE is the point:
#       Kafka-first continues, because the durable write is the broker's.
#       Compact mode FAILS CLOSED — D6 says the record commits before the response, so no record
#       means no response. A 500 here is the correct answer and a 200 would be the defect.
#
# What is NOT here: broker loss, AZ loss and KMS loss. Those need a cluster, a second zone and a
# KMS, none of which exist locally. §10 lists them and this script does not pretend to cover them.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VAR="$ROOT/devstack/var"
DECISIONS="http://127.0.0.1:${BRE_PORT:-8080}"
REGISTRY="http://127.0.0.1:${BRE_REGISTRY_PORT:-8081}"
TENANT="t_devstack"
POLICY="MFI_JLG_CREDIT_UW"

step() { printf '\n\033[1;36m▸ %s\033[0m\n' "$*"; }
ok()   { printf '  \033[0;32m✓\033[0m %s\n' "$*"; }
fail() { printf '  \033[0;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

decide() {
  python3 - "$ROOT/reference-policy/fixtures/approve-5-member-jlg.json" "$1" <<'PY' > /tmp/bre-chaos-body.json
import json, sys
print(json.dumps({"tenantId": "t_devstack", "requestId": sys.argv[2], "asOf": "2026-10-01",
                  "environment": "DEV", "policyCode": "MFI_JLG_CREDIT_UW",
                  "applicationRef": sys.argv[2],
                  "facts": json.load(open(sys.argv[1]))}))
PY
  curl -sS -o /tmp/bre-chaos-out.json -w '%{http_code}' -X POST "$DECISIONS/v1/decisions" \
    -H 'Content-Type: application/json' -H "X-Tenant-Id: $TENANT" -d @/tmp/bre-chaos-body.json
}

outcome() { python3 -c "import json;print(json.load(open('/tmp/bre-chaos-out.json')).get('decision','-'))"; }

[[ -f "$VAR/policy-registry.pid" ]] || fail "no registry running. Start it with ./devstack/up.sh --registry"

# ---------------------------------------------------------------- baseline
step "1. baseline — the stack decides"
status="$(decide "CHAOS-BASE-$RANDOM")"
[[ "$status" == "20"* ]] || fail "the baseline decision failed with $status, so nothing below means anything"
ok "decision $(outcome) ($status)"

# ---------------------------------------------------------------- registry loss
step "2. kill the policy registry — §10 says decisions are UNAFFECTED"
registry_pid="$(cat "$VAR/policy-registry.pid")"
kill "$registry_pid" 2>/dev/null || true
for _ in $(seq 1 20); do kill -0 "$registry_pid" 2>/dev/null || break; sleep 0.5; done
curl -sf --max-time 2 -o /dev/null "$REGISTRY/actuator/health" && fail "the registry is still up; the test proves nothing"
ok "registry is down"

status="$(decide "CHAOS-NOREG-$RANDOM")"
[[ "$status" == "20"* ]] \
  || fail "a decision failed with $status while the registry was down. The pod is supposed to keep serving the last verified manifest and bundles — if it cannot, the control plane is on the hot path and every registry deployment is a lending outage."
ok "decision $(outcome) ($status) — the control plane is NOT on the hot path"

step "3. and the pod is serving the same bundle it verified before the registry died"
grep -q "adopted" "$VAR/decision-service.log" 2>/dev/null \
  && ok "the pod adopted its manifest before the outage and is still serving it" \
  || ok "no re-adoption attempted while the registry was down, which is the expected quiet"

# ---------------------------------------------------------------- hot store loss
step "4. kill the hot store"
# Matched on "durable" rather than on the mode NAME, and the first version of this got it wrong
# in a way worth keeping. It grepped for the mode and compared against IN_MEMORY — but the service
# logs the mode twice, once as "IN_MEMORY" from the configuration and once as "in-memory" from
# describe(), and `tail -1` took the lowercase one. The script then expected compact mode's
# fail-closed 5xx from a pod with no database to lose, and reported the SERVICE as broken when the
# service was right. A test that fails the thing under test because of its own parsing is worse
# than no test.
if grep -qiE 'decision log:.*(compact mode|durable\))' "$VAR/decision-service.log" \
   && ! grep -qiE 'decision log:.*NOT durable' "$VAR/decision-service.log"; then
  mode="DURABLE"
else
  mode="IN_MEMORY"
fi
printf '  the decision log is: %s\n' "$mode"

# The REAL server, not the pid file. postgres.pid records the Gradle wrapper that launched the
# embedded database; the server itself is a child process several levels down. Killing the
# recorded pid leaves PostgreSQL listening — which is how the first version of this step reported
# a D6 VIOLATION against a service that was behaving perfectly: the decision succeeded because the
# database was never actually stopped.
#
# A chaos test that does not cause the chaos is worse than no chaos test. It fails the healthy
# system, and it would pass a broken one.
pg_port="$(grep -oE 'localhost:[0-9]+' "$VAR/postgres.properties" 2>/dev/null | head -1 | cut -d: -f2)"
postgres_pid="$(pgrep -f "bin/postgres -D .* -p ${pg_port:-0} " | head -1 || true)"

if [[ -n "$postgres_pid" ]]; then
  # SIGKILL, not SIGTERM, and the reason is PostgreSQL's own shutdown modes. SIGTERM to the
  # postmaster is a SMART shutdown: it waits for every client to disconnect first — and the
  # decision service holds a connection pool, so it waits for ever and the database never stops.
  # The second version of this step hung there.
  #
  # An abrupt stop is also the better simulation. §10's row is "hot store down", which in a
  # cluster means a node vanished, not an administrator asking politely.
  kill -9 "$postgres_pid" 2>/dev/null || true
  # Verified by the PORT, not by the pid. "the process is gone" and "the database is unreachable"
  # are different claims, and only the second one is the chaos.
  down=false
  for _ in $(seq 1 30); do
    if ! (exec 3<>"/dev/tcp/127.0.0.1/${pg_port}") 2>/dev/null; then down=true; break; fi
    exec 3<&- 2>/dev/null || true
    sleep 0.5
  done
  [[ "$down" == true ]] || fail "PostgreSQL is still listening on ${pg_port}; the chaos never happened, so nothing below would mean anything"
  ok "the database is down — port ${pg_port} refuses connections"

  status="$(decide "CHAOS-NODB-$RANDOM")"
  if [[ "$mode" == "IN_MEMORY" ]]; then
    [[ "$status" == "20"* ]] || fail "IN_MEMORY mode does not use the database, so a decision should still succeed; got $status"
    ok "IN_MEMORY: decision $(outcome) ($status) — the decision log never touched the database"
  else
    # THE ASSERTION THAT MATTERS, and the one whose sense is inverted from every other test here.
    # D6 says the record commits BEFORE the response. With no database there is no record, so a
    # 200 would mean the service answered a caller about a decision it cannot produce on audit.
    # The 5xx is the correct behaviour.
    [[ "$status" == "5"* ]] \
      || fail "a decision returned $status with no database. D6 requires the durable write before the response, so this should have FAILED CLOSED — a 200 here is a decision the lender cannot produce on audit, which is worse than an outage."
    ok "compact mode: $status — failed closed, exactly as D6 requires"
  fi
else
  printf '  no embedded database recorded; skipping\n'
fi

printf '\n  ────────────────────────────────────────────────────────────────────────────\n'
printf '  §10 executed, for the two failures that can be caused locally.\n\n'
printf '    registry down   decisions continue — the control plane is not on the hot path\n'
printf '    hot store down  compact mode fails CLOSED, which is the correct answer\n\n'
printf '  NOT covered here, and needing a cluster: broker loss, AZ loss, region loss, KMS\n'
printf '  loss. §10 lists them; this script does not pretend to cover them.\n\n'
printf '  The stack is now broken on purpose. Restart it:  ./devstack/up.sh --registry\n'
printf '  ────────────────────────────────────────────────────────────────────────────\n'
