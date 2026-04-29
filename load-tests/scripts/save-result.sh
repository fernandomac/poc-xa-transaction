#!/usr/bin/env bash
# Reads the latest Gatling run's stats.json and writes a structured result file.
# Usage: ./save-result.sh
# Output: load-tests/results/run-<ISO-timestamp>.json

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(dirname "$SCRIPT_DIR")"
RESULTS_DIR="$MODULE_DIR/results"
GATLING_DIR="$MODULE_DIR/target/gatling"

if [ ! -d "$GATLING_DIR" ]; then
    echo "ERROR: No Gatling output found at $GATLING_DIR — run 'mvn gatling:test -pl load-tests' first." >&2
    exit 1
fi

STATS_FILE="$(find "$GATLING_DIR" -name "stats.json" | sort | tail -1)"
if [ -z "$STATS_FILE" ]; then
    echo "ERROR: No stats.json found under $GATLING_DIR" >&2
    exit 1
fi

if ! command -v python3 &>/dev/null; then
    echo "ERROR: python3 is required to parse stats.json" >&2
    exit 1
fi

TIMESTAMP="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
RUN_ID="run-$(date -u +%Y%m%d-%H%M%S)"
TARGET_RPS="${GATLING_PEAK_RPS:-5000}"
mkdir -p "$RESULTS_DIR"
OUTPUT_FILE="$RESULTS_DIR/${RUN_ID}.json"

python3 - "$STATS_FILE" "$RUN_ID" "$TIMESTAMP" "$TARGET_RPS" "$OUTPUT_FILE" <<'PYEOF'
import json, sys

stats_file, run_id, timestamp, target_rps, output_file = sys.argv[1:]

with open(stats_file) as f:
    stats = json.load(f)

req = stats.get("stats", {})
ok = req.get("ok", {})
all_req = req.get("numberOfRequests", {})

total = all_req.get("total", 0)
errors = all_req.get("ko", 0)
error_rate = round((errors / total * 100) if total > 0 else 0, 4)

p50 = ok.get("percentiles1", 0)
p95 = ok.get("percentiles2", 0)
p99 = ok.get("percentiles3", 0)
mean_rps = req.get("meanNumberOfRequestsPerSecond", {}).get("total", 0)

result = {
    "runId": run_id,
    "timestamp": timestamp,
    "targetRps": int(target_rps),
    "achievedRps": round(float(mean_rps), 2),
    "p50Ms": float(p50),
    "p95Ms": float(p95),
    "p99Ms": float(p99),
    "errorRate": error_rate,
    "totalRequests": int(total)
}

with open(output_file, "w") as f:
    json.dump(result, f, indent=2)

print(json.dumps(result, indent=2))
PYEOF

echo ""
echo "Result saved to: $OUTPUT_FILE"
