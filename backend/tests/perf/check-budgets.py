#!/usr/bin/env python3
"""Fails the build when a JMH result exceeds its latency budget (LLD M1-16).

Run after JMH with -rf json -rff <results>. Reads budgets.json beside this script.

Why a script and not a JUnit test: a latency assertion inside the normal test suite would run on
every build, on whatever machine, with no warmup — and would then be either meaningless or flaky.
JMH has already done the warmup and the statistics; this only compares its output with a ceiling.
"""
import json
import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: check-budgets.py <jmh-results.json>", file=sys.stderr)
        return 2

    results_path = Path(sys.argv[1])
    budgets_path = Path(__file__).parent / "budgets.json"

    if not results_path.is_file():
        print(f"no JMH results at {results_path}: the benchmark did not run", file=sys.stderr)
        return 2

    budgets = json.loads(budgets_path.read_text())["budgets"]
    results = json.loads(results_path.read_text())

    measured = {}
    for entry in results:
        name = entry["benchmark"].rsplit(".", 1)[-1]
        rules = entry.get("params", {}).get("ruleCount", "-")
        measured[(name, rules)] = entry["primaryMetric"]["scorePercentiles"]["99.0"]

    failures, checked = [], 0
    print(f"{'benchmark':16s} {'rules':>6s} {'p99 us':>10s} {'budget':>10s}  verdict")
    for budget in budgets:
        key = (budget["benchmark"], budget["ruleCount"])
        if key not in measured:
            # Not a pass. A budget whose benchmark did not run is a budget nobody checked.
            failures.append(f"{key[0]}/{key[1]}: no result — the benchmark did not run")
            print(f"{key[0]:16s} {key[1]:>6s} {'-':>10s} {budget['p99Micros']:>10d}  NOT RUN")
            continue
        p99 = measured[key]
        ceiling = budget["p99Micros"]
        ok = p99 <= ceiling
        checked += 1
        print(f"{key[0]:16s} {key[1]:>6s} {p99:10.1f} {ceiling:>10d}  {'ok' if ok else 'OVER BUDGET'}")
        if not ok:
            failures.append(
                f"{key[0]}/{key[1]}: p99 {p99:.1f} us exceeds the {ceiling} us budget "
                f"({budget['note']})")

    print()
    if failures:
        print(f"{len(failures)} budget failure(s):", file=sys.stderr)
        for failure in failures:
            print(f"  {failure}", file=sys.stderr)
        return 1
    print(f"all {checked} latency budgets met")
    return 0


if __name__ == "__main__":
    sys.exit(main())
