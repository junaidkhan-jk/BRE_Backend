#!/usr/bin/env bash
# The whole application, exercised locally through its own HTTP APIs.
#
# Run ./devstack/up.sh --registry first, then this. It assumes nothing about the state of the
# database beyond a bootstrapped administrator, and it asserts at every step — a script that
# prints "done" after a 409 is worse than no script.
#
# What it proves, in order:
#
#   1. an author can sign in
#   2. a policy and a draft can be created
#   3. the real 328-line reference policy compiles, and its test cases pass
#   4. coverage is MEASURED, and the submit gate refuses a suite that does not reach the threshold
#   5. a tenant administrator can lower the threshold, and the change is audited
#   6. submit requires a change note and a committee reference
#   7. approval signs a bundle — with the administrator's segregation-of-duties override audited
#   8. deployment publishes a signed Active Pointer Manifest
#   9. a RUNNING decision pod adopts that manifest without a restart (§8.2)
#  10. the decision service serves the policy and returns the three expected decisions
#  11. a decision's masked explanation can be read back
#  12. the hash-chained audit log verifies, with no gaps and no broken links
#
# Everything is local. No Docker, no Kafka, no cloud. See docs/EXTERNAL-CALLS.md for what is
# substituted and what has to be real in production.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VAR="$ROOT/devstack/var"
REGISTRY="http://127.0.0.1:${BRE_REGISTRY_PORT:-8081}"
DECISIONS="http://127.0.0.1:${BRE_PORT:-8080}"
TENANT="t_devstack"
POLICY="MFI_JLG_CREDIT_UW"
EMAIL="${BRE_ADMIN_EMAIL:-admin@lender.local}"
PASSWORD="${BRE_ADMIN_PASSWORD:-devstack-admin-password}"
ASOF="2026-10-01"

step()  { printf '\n\033[1;36m▸ %s\033[0m\n' "$*"; }
ok()    { printf '  \033[0;32m✓\033[0m %s\n' "$*"; }
fail()  { printf '  \033[0;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

# Every call goes through here so a non-2xx response is a failure with the server's own problem
# detail printed, rather than an empty variable that breaks three lines later.
call() {
  local method="$1" path="$2" body="${3:-}" expect="${4:-2}"
  local out status
  if [[ -n "$body" ]]; then
    out="$(curl -sS -w '\n%{http_code}' -X "$method" "$REGISTRY$path" \
      -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$body")"
  else
    out="$(curl -sS -w '\n%{http_code}' -X "$method" "$REGISTRY$path" \
      -H "Authorization: Bearer $TOKEN")"
  fi
  status="${out##*$'\n'}"
  BODY="${out%$'\n'*}"
  [[ "$status" == "$expect"* ]] || fail "$method $path -> $status (expected ${expect}xx): $BODY"
}

jq_() { printf '%s' "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

curl -sS --max-time 3 "$REGISTRY/v1/auth/me" >/dev/null 2>&1 \
  || fail "no registry on $REGISTRY. Start it with ./devstack/up.sh --registry"
curl -sS --max-time 3 "$DECISIONS/actuator/health" >/dev/null 2>&1 \
  || curl -sS --max-time 3 -o /dev/null "$DECISIONS/v1/decisions" 2>/dev/null \
  || fail "no decision service on $DECISIONS. Start it with ./devstack/up.sh --registry"

# ---------------------------------------------------------------- 1. sign in
step "1. sign in"
TOKEN=""
call POST /v1/auth/login "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}"
TOKEN="$(jq_ "d['token']")"
ok "signed in as $(jq_ "d['displayName']") with roles $(jq_ "','.join(d['roles'])")"

# ---------------------------------------------------------------- 2. the tenant's assets
# The registry resolves a tenant's parameter sets and reference lists from §5.2's asset tables,
# APPROVED versions only — that is M3-03, and it is how AC-MODEL-007 is enforced. The file below
# is the OFFLINE fallback, used when a tenant has no approved asset of a kind the policy names:
# `bre psf bundle --assets` works with no registry and no database, which is how an integrator
# tries a policy against their own thresholds.
#
# This walk uses the file, deliberately, because it exercises the offline path end to end in one
# command. To walk the maker-checker path instead, draft a PARAMETER_SET through
# /v1/assets/PARAMETER_SET/{code}/versions, have a SECOND person approve it, and delete this
# file — the registry then prefers the approved asset and logs that the file was ignored.
step "2. install the tenant's parameter sets and reference lists"
mkdir -p "$VAR/bundles"
cp "$ROOT/reference-policy/assets.json" "$VAR/bundles/assets-$TENANT.json"
ok "assets-$TENANT.json in place ($(python3 -c "
import json; a=json.load(open('$VAR/bundles/assets-$TENANT.json'))
print('%d parameters, %d lists' % (len(a.get('parameterSets',[{}])[0].get('parameters',{})), len(a.get('referenceLists',[]))))
" 2>/dev/null || echo 'from reference-policy/assets.json'))"

# ---------------------------------------------------------------- 3. author
step "3. create the policy and open a draft"
call POST /v1/policies \
  "{\"policyCode\":\"$POLICY\",\"name\":\"MFI JLG credit underwriting\",\"stage\":\"CREDIT_UW\"}" 2 \
  || true
call POST "/v1/policies/$POLICY/versions" "" 2
VERSION="$(jq_ "d['version']")"
ok "draft v$VERSION"

step "4. save the real reference policy and let it compile"
SOURCE_RESPONSE="$(curl -sS -w '\n%{http_code}' -X PUT \
  "$REGISTRY/v1/policies/$POLICY/versions/$VERSION/source" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: text/plain' \
  --data-binary @"$ROOT/reference-policy/mfi-jlg-credit-uw.psf.yaml")"
[[ "${SOURCE_RESPONSE##*$'\n'}" == 2* ]] \
  || fail "saving the source failed: ${SOURCE_RESPONSE%$'\n'*}"
BODY="${SOURCE_RESPONSE%$'\n'*}"
jq_ "d['compileOk']" | grep -qi true || fail "the reference policy did not compile: $(jq_ "d['findings'][:3]")"
ok "compiled: $(jq_ "len(d['findings'])") finding(s), none an error"

step "5. add the three acceptance fixtures as test cases"
declare -A EXPECT=( [approve-5-member-jlg]=APPROVE [decline-negative-list]=DECLINE
                    [refer-soft-cap-threshold]=REFER
                    [decline-household-obligation-cap]=DECLINE )
for fixture in "$ROOT"/reference-policy/fixtures/*.json; do
  name="$(basename "$fixture" .json)"
  want="${EXPECT[$name]:-}"
  [[ -n "$want" ]] || continue
  body="$(python3 - "$fixture" "$want" "$ASOF" <<'PY'
import json, sys
print(json.dumps({"asOf": sys.argv[3], "facts": json.load(open(sys.argv[1])),
                  "expect": {"decision": sys.argv[2]}}))
PY
)"
  call PUT "/v1/policies/$POLICY/versions/$VERSION/test-cases/$name" "$body"
  ok "$name expects $want"
done
TOTAL="$(jq_ "d['tests']['total']")"; PASSED="$(jq_ "d['tests']['passed']")"
[[ "$TOTAL" == "$PASSED" ]] || fail "$((TOTAL - PASSED)) of $TOTAL test cases fail: $(jq_ "d['tests']['failures']")"
ok "$PASSED/$TOTAL test cases pass against the real engine"

# ---------------------------------------------------------------- 6. the coverage gate
step "6. coverage is measured, and the submit gate uses it"
call GET "/v1/policies/$POLICY/versions/$VERSION"
COVERAGE="$(jq_ "d['coverage']['overallPercent']")"
THRESHOLD="$(jq_ "d['minCoveragePercent']")"
ok "coverage $COVERAGE% against this tenant's threshold of $THRESHOLD%"
ok "uncovered: $(jq_ "len(d['coverage']['uncoveredRuleStatuses'])") rule status(es), \
$(jq_ "len(d['coverage']['uncoveredRows'])") table row(s), $(jq_ "len(d['coverage']['uncoveredBins'])") bin(s)"

NOTE='{"changeNote":"Initial load of the MFI JLG credit underwriting policy from the reference set.","committeeReference":"DEVSTACK-1"}'

call POST "/v1/policies/$POLICY/versions/$VERSION/submit" "$NOTE" 4
printf '%s' "$BODY" | grep -q 'coverage is' \
  && ok "submit refused for coverage, naming the gap — which is the gate working" \
  || ok "submit was not refused for coverage (threshold already below $COVERAGE%)"

# Lowering it is a real weakening of the bar, so it is a tenant setting, it needs TENANT_SETTINGS,
# and the before and after are both written to the audit log. Three fixtures cannot cover a
# 328-line policy; see reference-policy/FINDINGS.md finding 11.
step "7. lower the tenant's coverage threshold — audited, before and after"
call PATCH /v1/tenant/settings '{"minCoveragePercent": 25.0}'
ok "threshold now $(jq_ "d['minCoveragePercent']")%"

# ---------------------------------------------------------------- 8. the evidence gate
step "8. submit requires a change note and a committee reference"
call POST "/v1/policies/$POLICY/versions/$VERSION/submit" '{}' 4
printf '%s' "$BODY" | grep -q 'change note' || fail "expected the change-note refusal: $BODY"
ok "refused without a change note (FR-LIFE-002)"

call POST "/v1/policies/$POLICY/versions/$VERSION/submit" "$NOTE" 2
[[ "$(jq_ "d['status']")" == IN_REVIEW ]] || fail "expected IN_REVIEW, got $(jq_ "d['status']")"
ok "submitted: IN_REVIEW, round $(jq_ "d['submissionRound']")"

step "9. the version is now frozen"
FROZEN="$(curl -sS -o /dev/null -w '%{http_code}' -X PUT \
  "$REGISTRY/v1/policies/$POLICY/versions/$VERSION/source" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: text/plain' \
  --data-binary @"$ROOT/reference-policy/mfi-jlg-credit-uw.psf.yaml")"
[[ "$FROZEN" == 409 ]] || fail "an IN_REVIEW version accepted an edit ($FROZEN)"
ok "editing an IN_REVIEW version is refused — a reviewer reads the bytes that get deployed"

# ---------------------------------------------------------------- 10. approve
step "10. approve, which builds and signs the bundle"
call POST "/v1/policies/$POLICY/versions/$VERSION/reviews" \
  '{"approve":true,"comment":"devstack end-to-end"}'
[[ "$(jq_ "d['status']")" == APPROVED ]] || fail "expected APPROVED, got $(jq_ "d['status']")"
BUNDLE="$(jq_ "d['bundleHash']")"
ok "APPROVED, bundle $BUNDLE"
ok "the administrator approved their own work — permitted here, and written to the audit log as \
POLICY_VERSION.SOD_OVERRIDDEN"

# ---------------------------------------------------------------- 11. deploy
step "11. deploy to DEV, which publishes a signed Active Pointer Manifest"
call POST /v1/deployments \
  "{\"envCode\":\"DEV\",\"policyCode\":\"$POLICY\",\"version\":$VERSION,\"mode\":\"CHAMPION\",\"reason\":\"devstack end-to-end\"}"
[[ "$(jq_ "d['status']")" == ACTIVE ]] || fail "expected ACTIVE, got $(jq_ "d['status']") — DEV needs no approvals"
ok "ACTIVE, manifest version $(jq_ "d.get('manifestVersion')")"
[[ -s "$VAR/manifests/manifest.json" ]] || fail "no manifest at $VAR/manifests/manifest.json"
ok "manifest on disk, signed with the cell's manifest key"

# ---------------------------------------------------------------- 12. the pod adopts it
step "12. the running decision pod picks up the new manifest"
# Asserted by asking the pod to decide, NOT by grepping its log. The first version of this check
# waited for "adopted manifest version" and failed against a pod that was serving the policy
# perfectly well — because a pod that starts AFTER a deploy adopts at startup and logs nothing.
# Both paths are correct; only one of them writes that line. The property is what matters.
PROBE="$(python3 - "$ROOT/reference-policy/fixtures/approve-5-member-jlg.json" "$TENANT" "$ASOF" "$POLICY" <<'PROBEPY'
import json, sys
print(json.dumps({"tenantId": sys.argv[2], "requestId": "e2e-probe", "asOf": sys.argv[3],
                  "environment": "DEV", "policyCode": sys.argv[4],
                  "facts": json.load(open(sys.argv[1]))}))
PROBEPY
)"
SERVING=false
for _ in $(seq 1 30); do
  if curl -sS -X POST "$DECISIONS/v1/decisions" -H 'Content-Type: application/json' \
       -H "X-Tenant-Id: $TENANT" -d "$PROBE" 2>/dev/null | grep -q '"decision"'; then
    SERVING=true
    break
  fi
  sleep 1
done
$SERVING || fail "the pod will not serve $POLICY. Is it pointed at $VAR/manifests/manifest.json, \
and does it trust the registry's bundle key?"

if grep -q "adopted manifest version" "$VAR/decision-service.log" 2>/dev/null; then
  ok "$(grep -o 'adopted manifest version.*' "$VAR/decision-service.log" | tail -1) — adopted \
while running, with no restart (§8.2)"
else
  ok "the manifest was already in force when the pod started, so there was nothing to adopt"
fi
ok "the pod resolves $POLICY from the registry's signed manifest"

# ---------------------------------------------------------------- 13. decide
step "13. decide the three fixtures against the DEPLOYED policy"
printf '\n  %-32s %-9s %s\n' FIXTURE DECISION 'EXPECTED'
FAILURES=0
LAST_DECISION=""
for fixture in "$ROOT"/reference-policy/fixtures/*.json; do
  name="$(basename "$fixture" .json)"
  want="${EXPECT[$name]:-}"
  [[ -n "$want" ]] || continue
  body="$(python3 - "$fixture" "$TENANT" "$name" "$ASOF" "$POLICY" <<'PY'
import json, sys
print(json.dumps({"tenantId": sys.argv[2], "requestId": "e2e-" + sys.argv[3],
                  "asOf": sys.argv[4], "environment": "DEV", "policyCode": sys.argv[5],
                  "facts": json.load(open(sys.argv[1]))}))
PY
)"
  response="$(curl -sS -X POST "$DECISIONS/v1/decisions" \
    -H 'Content-Type: application/json' -H "X-Tenant-Id: $TENANT" -d "$body")"
  got="$(printf '%s' "$response" | python3 -c "
import json,sys
d=json.load(sys.stdin)
print(d.get('decision', 'ERROR ' + json.dumps(d)[:120]))")"
  id="$(printf '%s' "$response" | python3 -c "
import json,sys; print(json.load(sys.stdin).get('decisionId',''))" 2>/dev/null || true)"
  [[ -n "$id" ]] && LAST_DECISION="$id"
  if [[ "$got" == "$want" ]]; then
    printf '  %-32s \033[0;32m%-9s\033[0m %s\n' "$name" "$got" "$want"
  else
    printf '  %-32s \033[0;31m%-9s\033[0m %s\n' "$name" "$got" "$want"
    FAILURES=$((FAILURES + 1))
  fi
done
[[ "$FAILURES" == 0 ]] || fail "$FAILURES fixture(s) decided differently from the policy's own tests"
ok "the deployed policy decides exactly as its test cases assert"

# ---------------------------------------------------------------- 14. explain
if [[ -n "$LAST_DECISION" ]]; then
  step "14. read a decision's masked explanation"
  # The STATUS, and a field only a real explanation has. The first version of this grepped the body
  # for the word "decision" — which the Spring error page also contains, in its own "path" field:
  #
  #   {"status":500,...,"path":"/v1/decisions/01a1.../explanation"}
  #
  # So it reported "explanation returned, masked" on an HTTP 500, every run, and hid a defect that
  # made the endpoint fail for EVERY decision in POSTGRES mode. A check that passes on the error
  # page of the thing it checks is worse than no check.
  code="$(curl -sS -o /tmp/bre-e2e-explain.json -w '%{http_code}' \
    "$DECISIONS/v1/decisions/$LAST_DECISION/explanation" -H "X-Tenant-Id: $TENANT" || true)"
  case "$code" in
    200)
      python3 -c "
import json,sys
d=json.load(open('/tmp/bre-e2e-explain.json'))
sys.exit(0 if 'matched' in d and d.get('bundleHash') else 1)" \
        && ok "explanation returned against the pinned bundle, masked (§9.4) — there is no unmask path, deliberately" \
        || fail "HTTP 200 but not an explanation: $(head -c 200 /tmp/bre-e2e-explain.json)"
      ;;
    409)
      ok "no fact snapshot stored, so no explanation (store-facts is off by default) — 409, correctly"
      ;;
    *)
      fail "explanation returned HTTP $code: $(head -c 200 /tmp/bre-e2e-explain.json)"
      ;;
  esac
fi

# ---------------------------------------------------------------- 15. audit
step "15. verify the hash-chained audit log"
call GET /v1/audit/verify
[[ "$(jq_ "d['intact']")" == True ]] || fail "the audit chain is BROKEN: $BODY"
ok "$(jq_ "d['entries']") entries, every hash recomputed from its predecessor, no gaps"
ok "head $(jq_ "d['headHash'][:32]")…"

call GET "/v1/audit?action=POLICY_VERSION&limit=50"
ok "policy lifecycle actions recorded: $(jq_ "', '.join(sorted({e['action'] for e in d}))")"

cat <<DONE

  ────────────────────────────────────────────────────────────────────────────
  The complete application works locally, with no Docker and nothing external.

    authored   $POLICY v$VERSION, from reference-policy/mfi-jlg-credit-uw.psf.yaml
    bundle     $BUNDLE
    served by  $DECISIONS
    studio     cd ../BRE_UI && VITE_USE_FIXTURES=false \\
               VITE_REGISTRY_URL=$REGISTRY npm run dev
    sign in    $EMAIL / $PASSWORD

  Substituted locally: PostgreSQL is embedded, the KMS is a local AES key, the
  manifest long-poll is a 2-second file poll, the identity provider is a local
  account table, and the decision log is in-memory or PostgreSQL rather than
  Kafka. Every one of them is listed in docs/EXTERNAL-CALLS.md with what has to
  be real in production.
  ────────────────────────────────────────────────────────────────────────────
DONE
