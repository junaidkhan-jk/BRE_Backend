#!/usr/bin/env bash
# The 24-hour fuzzing campaign (M6-02).
#
# The nightly workflow runs one hour against the BEL parser — M1-02's exit criterion. This is the
# longer, broader run a security review wants before a penetration test, across every target, with
# the corpus kept between runs so each campaign starts where the last one stopped rather than from
# nothing.
#
# WHAT A CLEAN RUN DOES AND DOES NOT MEAN. Twenty-four hours without a crash says these parsers
# survive what a coverage-guided fuzzer found in a day. It says nothing about authorisation,
# business logic, or anything needing a sequence of valid requests — a fuzzer sends one input to
# one function. The penetration test in M6-02 is for the rest, and this does not substitute for it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOURS="${BRE_FUZZ_HOURS:-24}"
OUT="${BRE_FUZZ_OUT:-$ROOT/devstack/var/fuzz}"

# Every target, with the module that owns it. Each gets an equal share of the campaign.
TARGETS=(
  "libs:bre-engine-core|*BelParserFuzzTest*"
  "libs:bre-bundle|*BundleParserFuzz*"
)

step() { printf '\n\033[1;36m▸ %s\033[0m\n' "$*"; }
ok()   { printf '  \033[0;32m✓\033[0m %s\n' "$*"; }
fail() { printf '  \033[0;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

mkdir -p "$OUT"
# Overridable, so the script itself is testable. A campaign script nobody has run end to end is
# a campaign script with an unknown number of bugs in it — and the first version of this had one.
per_target_minutes="${BRE_FUZZ_MINUTES_PER_TARGET:-$(( (HOURS * 60) / ${#TARGETS[@]} ))}"

printf '\n  %s-hour campaign over %d targets, %d minutes each.\n' \
  "$HOURS" "${#TARGETS[@]}" "$per_target_minutes"
printf '  Corpus is kept in each module under .cifuzz-corpus and grows between runs.\n'

crashes=0
for entry in "${TARGETS[@]}"; do
  module="${entry%%|*}"
  pattern="${entry##*|}"
  log="$OUT/$(echo "$module" | tr ':' '-').log"

  step "$module — $per_target_minutes minutes"

  # --rerun-tasks, and this matters: JAZZER_FUZZ is an environment variable, so Gradle sees no
  # input change and reports the test task UP-TO-DATE. The first version of this script ran for
  # four seconds and declared the campaign clean. A campaign that silently skips is worse than
  # none — it produces the evidence of a run that never happened.
  if JAZZER_FUZZ=1 timeout "$(( per_target_minutes * 60 + 120 ))" \
      "$ROOT/backend/gradlew" -p "$ROOT/backend" ":$module:test" \
      --tests "$pattern" --rerun-tasks --console=plain \
      -Djazzer.maxDuration="${per_target_minutes}m" > "$log" 2>&1; then
    : # Finished within the budget.
  elif [[ $? -eq 124 ]]; then
    : # Killed by the outer timeout, which is the expected way a long fuzz run ends.
    :
  fi

  # WHAT COUNTS AS A CRASH, and this took three wrong answers to get right.
  #
  # The first version matched every crash-* file newer than a day. That counted (a) the COMMITTED
  # regression corpus, because the test task copies resources into build/ fresh on every run, and
  # (b) files libFuzzer writes when it is KILLED — it emits a crash artefact on OOM and on signal,
  # and the outer timeout here sends one every time. A one-minute smoke test reported five crashes
  # and every one was false.
  #
  # A security campaign that cries wolf is worse than no campaign: the first real finding arrives
  # in a report nobody believes. So a crash counts only when it is BOTH new to git AND under src/,
  # and it is then REPLAYED to prove it still fails.
  new_crashes=()
  while IFS= read -r candidate; do
    [[ -n "$candidate" ]] && new_crashes+=("$candidate")
  done < <(cd "$ROOT" && git ls-files --others --exclude-standard \
             | grep -E "^backend/${module//://}/src/.*/crash-" || true)

  executions="$(grep -aoE '^#[0-9]+' "$log" | tail -1 || echo '#0')"

  if [[ ${#new_crashes[@]} -gt 0 ]]; then
    step "replaying ${#new_crashes[@]} candidate crash input(s) to confirm"
    if "$ROOT/backend/gradlew" -p "$ROOT/backend" ":$module:test" --tests "$pattern" \
         --rerun-tasks --console=plain > "$log.replay" 2>&1; then
      # The corpus replays clean, so the artefacts were the fuzzer being killed rather than the
      # parser failing. Removed, because leaving them would fail every future ordinary build.
      printf '  \033[0;33m!\033[0m %d artefact(s) written, but the corpus replays CLEAN — these\n' \
        "${#new_crashes[@]}"
      printf '      were libFuzzer being killed (OOM or signal), not a parser defect. Removed.\n'
      for artefact in "${new_crashes[@]}"; do rm -f "$ROOT/$artefact"; done
      ok "no crash. $executions executions, log: ${log#"$ROOT"/}"
    else
      crashes=$(( crashes + ${#new_crashes[@]} ))
      printf '  \033[0;31m✗ CONFIRMED: %d crash input(s) that still fail on replay\033[0m\n' \
        "${#new_crashes[@]}"
      printf '      %s\n' "${new_crashes[@]}"
    fi
  else
    ok "no crash. $executions executions, log: ${log#"$ROOT"/}"
  fi
done

printf '\n  ────────────────────────────────────────────────────────────────────────────\n'
if [[ "$crashes" -gt 0 ]]; then
  fail "$crashes crash input(s). Each is committed as a regression case and must be FIXED, never deleted."
fi
# The ACTUAL budget, not the requested one. With BRE_FUZZ_MINUTES_PER_TARGET set the summary
# said "24 hours" after a one-minute smoke test — a line that would have gone into a security
# report as evidence of a campaign that never ran that long.
printf '  %d minutes per target across %d targets, no crash.\n\n' \
  "$per_target_minutes" "${#TARGETS[@]}"
printf '  What this does NOT cover, and the threat model says so too: authorisation, business\n'
printf '  logic, and anything needing a SEQUENCE of valid requests. A fuzzer sends one input to\n'
printf '  one function. The external penetration test is for the rest.\n'
printf '  ────────────────────────────────────────────────────────────────────────────\n'
