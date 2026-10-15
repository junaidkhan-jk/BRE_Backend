#!/usr/bin/env bash
# Decision delivery, end to end: push and fetch, and what happens when the downstream breaks.
#
# Run ./devstack/up.sh --deliver first. That starts a durable decision log (POSTGRES mode — the
# only mode that creates outbox rows), a stand-in downstream system, and the relay pointed at it.
#
# The second half is the part worth watching. A relay that delivers when everything works and
# loses a decision when it does not is worse than no relay, because a lender would believe their
# downstream was in step with their decisions.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VAR="$ROOT/devstack/var"
DECISIONS="http://127.0.0.1:${BRE_PORT:-8080}"
TENANT="t_devstack"
POLICY="MFI_JLG_CREDIT_UW"
ASOF="2026-10-01"
LOG="$VAR/receiver.log"
STATUS="$VAR/receiver.status"

step() { printf '\n\033[1;36m▸ %s\033[0m\n' "$*"; }
ok()   { printf '  \033[0;32m✓\033[0m %s\n' "$*"; }
fail() { printf '  \033[0;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

[[ -f "$STATUS" ]] || fail "no stand-in downstream. Start it with ./devstack/up.sh --deliver"
curl -sS --max-time 3 -o /dev/null "$DECISIONS/v1/decision-events" -H "X-Tenant-Id: $TENANT" \
  || fail "no decision service on $DECISIONS, or it is not in POSTGRES mode"

# Decides one application and returns its decisionId.
decide() {
  local ref="$1" fixture="$2"
  python3 - "$ROOT/reference-policy/fixtures/$fixture" "$TENANT" "$ref" "$ASOF" "$POLICY" <<'PY' > /tmp/bre-decide-body.json
import json, sys
print(json.dumps({"tenantId": sys.argv[2], "requestId": sys.argv[3], "asOf": sys.argv[4],
                  "environment": "DEV", "policyCode": sys.argv[5],
                  "applicationRef": sys.argv[3],
                  "facts": json.load(open(sys.argv[1]))}))
PY
  curl -sS -X POST "$DECISIONS/v1/decisions" -H 'Content-Type: application/json' \
    -H "X-Tenant-Id: $TENANT" -d @/tmp/bre-decide-body.json
}

health() {
  curl -sS "$DECISIONS/v1/decision-events/health" -H "X-Tenant-Id: $TENANT"
}

feed() {
  curl -sS "$DECISIONS/v1/decision-events?since=${1:-0}&limit=50" -H "X-Tenant-Id: $TENANT"
}

jq_() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

# ---------------------------------------------------------------- 1. push
step "1. decide three applications — the relay pushes each one downstream"
: > "$LOG"
echo 200 > "$STATUS"

for pair in "APP-1001:approve-5-member-jlg.json" \
            "APP-1002:decline-negative-list.json" \
            "APP-1003:refer-soft-cap-threshold.json"; do
  ref="${pair%%:*}"; fixture="${pair#*:}"
  response="$(decide "$ref" "$fixture")"
  decision="$(printf '%s' "$response" | jq_ "d.get('decision','ERROR '+json.dumps(d)[:100])")"
  printf '  %-10s %s\n' "$ref" "$decision"
done

# The relay polls every second; give it two.
sleep 3
printf '\n  the downstream system received:\n'
sed 's/^/    /' "$LOG"
[[ "$(wc -l < "$LOG")" -ge 3 ]] || fail "the downstream did not receive three events"
ok "three decisions delivered as CloudEvents, each with its ce-id and an Authorization header"

step "2. nothing is pending, because everything was accepted"
health | python3 -c "
import json,sys
h=json.load(sys.stdin)
print(f\"    pending {h['pending']}  delivered {h['delivered']}  dead-lettered {h['deadLettered']}  push {'on' if h['pushEnabled'] else 'off'}\")
assert h['pending'] == 0, 'expected nothing pending'
assert h['deadLettered'] == 0, 'expected nothing dead-lettered'
"
ok "the relay keeps its own record of what was accepted"

# ---------------------------------------------------------------- 3. fetch
step "3. the SAME decisions over the fetch API — reading does not consume them"
feed 0 | python3 -c "
import json,sys
events=json.load(sys.stdin)
for e in events:
    print(f\"    #{e['outboxId']:<4} {e['recordKey']:<22} {e['decision']['decision']:<8} delivered={'yes' if e.get('deliveredAt') else 'no'}\")
assert len(events) >= 3, f'expected at least three events, got {len(events)}'
assert all(e.get('deliveredAt') for e in events), 'all three were pushed, so all three are delivered'
"
CURSOR="$(feed 0 | jq_ "d[0]['outboxId']")"
ok "a warehouse can pull what the origination system was already pushed"

step "4. the cursor is an outbox id, so resuming cannot skip a decision"
feed "$CURSOR" | python3 -c "
import json,sys
rest=json.load(sys.stdin)
print(f'    after #$CURSOR there are {len(rest)} more')
assert all(e['outboxId'] > $CURSOR for e in rest), 'the feed must return only rows after the cursor'
"
ok "two decisions in the same millisecond would make a timestamp cursor skip one; an id cannot"

# ---------------------------------------------------------------- 5. the downstream breaks
step "5. the downstream goes down — a decision is retried, not lost"
echo 503 > "$STATUS"
: > "$LOG"
decide "APP-1004" "approve-5-member-jlg.json" > /dev/null
sleep 3

health | python3 -c "
import json,sys
h=json.load(sys.stdin)
print(f\"    pending {h['pending']}  dead-lettered {h['deadLettered']}  oldest pending {h['oldestPendingSeconds']}s\")
assert h['pending'] >= 1, 'the decision must still be pending, not dropped'
assert h['deadLettered'] == 0, 'a 503 is the downstream\\'s problem, not a reason to give up yet'
"
printf '  the downstream refused it:\n'
sed 's/^/    /' "$LOG" | head -3
ok "held, with backoff — 2s, 4s, 8s … capped at five minutes"

step "6. and it is visible as pending in the feed, with the error"
feed 0 | python3 -c "
import json,sys
pending=[e for e in json.load(sys.stdin) if not e.get('deliveredAt')]
for e in pending:
    print(f\"    #{e['outboxId']} {e['recordKey']} attempts={e['attempts']} error={e.get('lastError','')[:40]}\")
assert pending, 'a pending decision must be visible'
"
ok "an operator can see exactly which decision has not landed, and why"

step "7. the downstream comes back — the backlog drains on its own"
echo 200 > "$STATUS"
# Pulled forward rather than waiting out the backoff, which is what restarting the receiver does.
python3 - "$VAR" <<'PY'
import glob, os, subprocess, sys
# Nudge the next attempt to now, through psql, the way an operator would.
uri = open(os.path.join(sys.argv[1], "postgres.properties")).read()
line = [l for l in uri.splitlines() if l.startswith("psql.uri=")][0].split("=", 1)[1]
subprocess.run(["psql", "-w", line, "-q", "-c",
                "UPDATE bre_dec.outbox SET next_attempt_at = now() WHERE published_at IS NULL"],
               check=False)
PY
sleep 3

health | python3 -c "
import json,sys
h=json.load(sys.stdin)
print(f\"    pending {h['pending']}  delivered {h['delivered']}  dead-lettered {h['deadLettered']}\")
assert h['pending'] == 0, 'the backlog should have drained'
"
printf '  the downstream received it on the retry:\n'
sed 's/^/    /' "$LOG" | tail -2
ok "the same decision, redelivered — which is why a receiver must deduplicate on ce-id"

# ---------------------------------------------------------------- 8. rejected content
step "8. a downstream that REJECTS the content is dead-lettered at once"
echo 400 > "$STATUS"
: > "$LOG"
decide "APP-1005" "decline-negative-list.json" > /dev/null
sleep 3

health | python3 -c "
import json,sys
h=json.load(sys.stdin)
print(f\"    pending {h['pending']}  dead-lettered {h['deadLettered']}\")
assert h['deadLettered'] >= 1, 'a 400 means the content was refused; retrying cannot help'
"
ok "one attempt, not four: retrying rejected content buries the integration defect"

step "9. an operator fixes the cause and replays it, deliberately"
echo 200 > "$STATUS"
DEAD="$(feed 0 | jq_ "[e['outboxId'] for e in d if e['deadLettered']][0]")"
printf '    replaying #%s\n' "$DEAD"
curl -sS -X POST "$DECISIONS/v1/decision-events/$DEAD/replay" -H "X-Tenant-Id: $TENANT" \
  | sed 's/^/    /'
echo
sleep 3

health | python3 -c "
import json,sys
h=json.load(sys.stdin)
print(f\"    pending {h['pending']}  delivered {h['delivered']}  dead-lettered {h['deadLettered']}\")
assert h['deadLettered'] == 0, 'the replayed event should have been delivered'
assert h['pending'] == 0
"
ok "replay is an operator's act — a dead letter is never retried on its own"

cat <<DONE

  ────────────────────────────────────────────────────────────────────────────
  Both ways out of the outbox, working, from the same rows.

    PUSH    the relay POSTs each decision to the downstream as a CloudEvent
    FETCH   GET /v1/decision-events?since=<outboxId>, which does not consume

  What the second half showed, which is the half that matters:

    a 503 is held and retried with backoff, never dropped
    a 400 is dead-lettered at once, because retrying rejected content hides a bug
    a dead letter is VISIBLE in /health and in the feed, with the error
    a replay is deliberate, and a redelivery is why you deduplicate on ce-id

    receiver log      $LOG
    make it fail      echo 503 > $STATUS
    make it reject    echo 400 > $STATUS
    make it work      echo 200 > $STATUS
  ────────────────────────────────────────────────────────────────────────────
DONE
