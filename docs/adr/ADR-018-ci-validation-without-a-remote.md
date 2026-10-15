# ADR-018: Validating CI without a remote

Status: accepted · Date: 2026-10-08

## Context

BD2 chose local git only: there is no remote, so no GitHub Actions run has ever
executed. M0-06's artifacts (two workflows, a PR template, CODEOWNERS) were
described as "written and lint-clean".

They were not lint-clean. Both workflows failed to parse as YAML:

```yaml
- run: echo "Engine benchmarks land in M1-16: p99 <= 0.5 ms (100 rules)."
```

A plain scalar containing `": "` is a mapping indicator, so GitHub would have
rejected the whole file. Neither workflow could ever have run, and the claim
that they were lint-clean had never been tested by anything.

`actionlint` cannot be installed here — GitHub release downloads are refused in
this environment (only Maven Central and npm resolve), which is the same
containment D11 relies on.

## Decision

Validate as much of the pipeline as can be validated without executing it, and
say precisely what remains unverified.

1. **Schema validation** through `@action-validator/cli` from npm, which checks
   the GitHub workflow schema and expression syntax, not merely YAML syntax.
2. **The validator is itself verified** against a deliberately broken workflow
   and a schema violation, so its silence means something. A tool trusted
   without a negative control is the same mistake as a green build that ran no
   tests — which happened twice in this repository and is recorded in
   `docs/html/status.html`.
3. **Every `run:` step is executed locally**: the two Gradle invocations, and
   the shell of each summary step.
4. **CODEOWNERS paths are resolved against the tree**, because a pattern that
   matches nothing silently owns nothing and still looks correct.
5. **Actions are pinned to commit SHAs**, not tags. A tag is a mutable pointer;
   whoever controls the action's repository can move `v4` to new code that then
   runs with this workflow's token against a credit platform's source.

## Consequences

- The four placeholder jobs that echoed a sentence and exited 0 are gone. They
  showed a reviewer green checks named "Contracts", "SBOM" and "JMH smoke" for
  work that does not exist. A check reporting success for nothing built is worse
  than no check, because it is read as assurance. The gaps are now written to
  the run summary by one job that does not look like a passing gate.
- The PR gate asserts on the test-results XML — no failures, and nothing
  skipped — rather than trusting the build's exit status. Both failure modes it
  guards against have actually occurred here: a Jupiter `@Tag` on a jqwik
  property made the platform filter skip it while the build stayed green, and a
  `grep` in a pipeline swallowed a failing exit code.
- The nightly fuzz job runs Jazzer for real now that M1-02's targets exist. It
  is not a formality: the first local run found a parser stack overflow in 54
  seconds.

## Amendment, 2026-10-15: the validation is now a command

Everything above was run by hand, which means once. The workflows were edited
afterwards and nothing re-checked them. A validation that is not a command is
not a check, so it is `devstack/check-ci.sh` now: the negative control, both
workflows, the CODEOWNERS resolution and the SHA-pinning grep, in one run.

The same pass found that the studio job ran only `npx playwright test` — not
types, not lint, not unit tests — so three of the Definition of Done's steps
were absent from the gate that is supposed to enforce it. Worse, `npm run lint`
had no `eslint.config.js` behind it and had therefore failed every time it ran
since the first commit, and `npm test` globbed the Playwright specs into
vitest, where they cannot execute. Both are fixed; all four steps are in the
job now.

## What is still unverified, and what would change it

**Orchestration has never executed.** Everything above tests the workflows as
documents and their steps as commands. It does not test that the runner
provisions two JDKs, that the Gradle cache action behaves, that the artifact
upload works, or that the schedule fires. Only a real run does that, and a real
run needs a remote.

Creating one is a decision for the owner, not a build step: pushing this
repository to GitHub publishes it, and BD2 deliberately chose otherwise. Until
that decision is taken, M0-06 stays partial and says why.
