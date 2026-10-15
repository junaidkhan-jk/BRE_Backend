#!/usr/bin/env bash
# Validates the GitHub workflows without running them, and proves the validator can fail.
#
# ADR-018 took this approach and ran it by hand. By hand means once: the workflows have been edited
# since, and nothing re-checked them. A validation that is not a command is not a check.
#
# What this does NOT do is test orchestration — that the runner provisions two JDKs, that the cache
# action behaves, that the schedule fires. Only a real run does that, and a real run needs a remote,
# which BD2 deliberately does not have. See ADR-018.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VALIDATOR="@action-validator/cli@0.6.0"

step() { printf '\n\033[1;36m▸ %s\033[0m\n' "$*"; }
ok()   { printf '  \033[0;32m✓\033[0m %s\n' "$*"; }
fail() { printf '  \033[0;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

step "the validator itself can fail"
# A negative control, because a tool trusted without one is the same mistake as a green build that
# ran no tests — which has happened twice in this repository.
BROKEN="$(mktemp -d)/broken.yml"
cat > "$BROKEN" <<'YML'
name: Broken
on: push
jobs:
  oops:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with_typo: yes
YML
if npx --yes "$VALIDATOR" "$BROKEN" >/dev/null 2>&1; then
  fail "the validator accepted a workflow with an unknown step key — its silence means nothing"
fi
ok "a schema violation is rejected, so a pass below is evidence"
rm -rf "$(dirname "$BROKEN")"

step "every workflow validates"
shopt -s nullglob
for workflow in "$ROOT"/.github/workflows/*.yml; do
  npx --yes "$VALIDATOR" "$workflow" || fail "$(basename "$workflow") is not a valid workflow"
  ok "$(basename "$workflow")"
done

step "every CODEOWNERS pattern matches something"
# A pattern that matches nothing silently owns nothing and still looks correct, so a file nobody
# reviews reads as a file somebody reviews.
missing=0
while read -r pattern _owners; do
  [[ -z "$pattern" || "$pattern" == \#* ]] && continue
  path="${pattern#/}"
  path="${path%/}"
  if [[ "$pattern" == "*" ]]; then
    continue
  fi
  if ! compgen -G "$ROOT/$path" > /dev/null && [[ ! -e "$ROOT/$path" ]]; then
    printf '  \033[0;31m✗ %s matches nothing\033[0m\n' "$pattern" >&2
    missing=1
  fi
done < "$ROOT/CODEOWNERS"
[[ $missing -eq 0 ]] || fail "a CODEOWNERS pattern owns nothing"
ok "every pattern resolves against the tree"

step "every action is pinned to a commit SHA, not a tag"
# A tag is a mutable pointer: whoever controls the action's repository can move v4 to new code that
# then runs with this workflow's token against a credit platform's source.
if grep -nE 'uses: [^@]+@v?[0-9]+(\.[0-9]+)*\s*$' "$ROOT"/.github/workflows/*.yml; then
  fail "the lines above pin a tag rather than a SHA"
fi
ok "no tag-pinned action"

printf '\n  \033[0;32mThe workflows are valid as documents. Orchestration is still unrun — ADR-018.\033[0m\n\n'
