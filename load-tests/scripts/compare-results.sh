#!/usr/bin/env bash
# Compares two Gatling result JSON files and flags regressions > 10%.
# Usage: ./compare-results.sh <file1.json> <file2.json>
# Exit 1 if any metric regresses by more than 10%.

set -euo pipefail

if [ $# -ne 2 ]; then
    echo "Usage: $0 <baseline.json> <candidate.json>" >&2
    exit 2
fi

BASELINE="$1"
CANDIDATE="$2"

if [ ! -f "$BASELINE" ]; then
    echo "ERROR: Baseline file not found: $BASELINE" >&2; exit 2
fi
if [ ! -f "$CANDIDATE" ]; then
    echo "ERROR: Candidate file not found: $CANDIDATE" >&2; exit 2
fi

if ! command -v python3 &>/dev/null; then
    echo "ERROR: python3 is required" >&2; exit 2
fi

python3 - "$BASELINE" "$CANDIDATE" <<'PYEOF'
import json, sys

baseline_file, candidate_file = sys.argv[1:]

with open(baseline_file) as f:
    b = json.load(f)
with open(candidate_file) as f:
    c = json.load(f)

METRICS = ["achievedRps", "p50Ms", "p95Ms", "p99Ms", "errorRate"]
THRESHOLD = 10.0

print(f"Baseline : {b['runId']} ({b['timestamp']})")
print(f"Candidate: {c['runId']} ({c['timestamp']})")
print()
print(f"{'Metric':<15} {'Baseline':>12} {'Candidate':>12} {'Change %':>10}  Status")
print("-" * 60)

regressions = []
for metric in METRICS:
    bval = float(b.get(metric, 0))
    cval = float(c.get(metric, 0))
    if bval == 0:
        pct = 0.0
    else:
        pct = ((cval - bval) / abs(bval)) * 100

    # Higher is better for achievedRps; lower is better for latency/errors
    if metric == "achievedRps":
        regressed = pct < -THRESHOLD
    else:
        regressed = pct > THRESHOLD

    status = "REGRESSION" if regressed else "ok"
    if regressed:
        regressions.append(metric)

    print(f"{metric:<15} {bval:>12.2f} {cval:>12.2f} {pct:>+9.1f}%  {status}")

print()
if regressions:
    print(f"REGRESSION DETECTED in: {', '.join(regressions)}")
    sys.exit(1)
else:
    print("No regressions detected (all metrics within 10% threshold).")
    sys.exit(0)
PYEOF
