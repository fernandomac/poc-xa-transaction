# Contract: BenchmarkResult JSON Output

**File**: `benchmark-result.json` (written to working directory)
**Producer**: `BenchmarkRunner.run()`
**Consumer**: CI scripts, manual comparison, `PerformanceBenchmarkIT` assertions

## File Format

UTF-8 JSON, single object, no trailing newline required.

```json
{
  "runId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "eventCount": 1000,
  "durationMs": 1842,
  "throughputPerSec": 542.8,
  "p50Ms": 1.2,
  "p95Ms": 3.7,
  "p99Ms": 8.1
}
```

## Field Contracts

| Field | Type | Required | Constraint |
|---|---|---|---|
| `runId` | string (UUID) | Yes | Unique per run; generated at benchmark start |
| `eventCount` | integer | Yes | Must equal `xa-poc.benchmark.event-count` config value |
| `durationMs` | integer | Yes | Wall-clock milliseconds for full benchmark loop; > 0 |
| `throughputPerSec` | number | Yes | `eventCount / (durationMs / 1000.0)`; MUST be ≥ 500 to pass SC-003 |
| `p50Ms` | number | Yes | Micrometer 50th percentile commit latency in milliseconds; > 0 |
| `p95Ms` | number | Yes | Micrometer 95th percentile commit latency; MUST be ≤ 200 to pass SC-004 |
| `p99Ms` | number | Yes | Micrometer 99th percentile commit latency; > 0 |

## Latency Scope

All percentile values measure the duration of `EventProducerService.produceEvent()` from
entry to return — encompassing both the MySQL XA write and the Artemis XA send, plus the
two-phase commit overhead. Rolled-back transactions (fault injection) MUST NOT be included
in the latency histogram.

## Compliance Check (by `PerformanceBenchmarkIT`)

```java
assertThat(result.throughputPerSec()).isGreaterThanOrEqualTo(500.0);
assertThat(result.p95Ms()).isLessThanOrEqualTo(200.0);
```
