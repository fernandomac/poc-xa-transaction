# Data Model: XA Transaction POC

**Branch**: `001-xa-transaction-poc` | **Date**: 2026-04-28

---

## Entity: SampleEvent

**Storage**: MySQL table `sample_event`
**Purpose**: Persistent record of every event produced within an XA transaction.

### Fields

| Field | Type | Constraints | Notes |
|---|---|---|---|
| `id` | UUID | PRIMARY KEY, NOT NULL | Auto-generated (`@GeneratedValue`) |
| `payload` | VARCHAR(500) | NOT NULL | Arbitrary string payload from producer caller |
| `created_at` | DATETIME(6) | NOT NULL | Set in `@PrePersist`; microsecond precision |

### JPA Mapping

```java
@Entity
@Table(name = "sample_event")
public class SampleEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 500)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() { this.createdAt = Instant.now(); }
}
```

### Validation Rules

- `payload` MUST NOT be null or empty at the service layer before `save()` is called.
- `id` MUST be assigned by the JPA provider — never set by caller.

### State Transitions

`SampleEvent` has no explicit status field. Its presence in the table implies a committed
XA transaction. Its absence (after a fault injection rollback) implies the XA transaction
was rolled back. The test assertions rely on `repository.count()` directly.

---

## Message Contract: EventMessage (JMS TextMessage)

**Transport**: Apache Artemis queue `sample.events`
**Format**: JMS `TextMessage` with a JSON body
**Purpose**: Carries the event from producer to consumer within the same XA transaction.

### JSON Body Schema

```json
{
  "eventId": "<UUID string>",
  "payload": "<String>"
}
```

### Fields

| Field | Type | Required | Notes |
|---|---|---|---|
| `eventId` | String (UUID) | Yes | Must match the `SampleEvent.id` persisted in the same transaction |
| `payload` | String | Yes | Must match the `SampleEvent.payload` persisted in the same transaction |

### Invariant

`eventId` in the JMS message MUST equal the `id` of the `SampleEvent` saved to MySQL in the
same XA transaction. Consumer tests verify this by asserting the payload matches.

---

## Value Object: BenchmarkResult

**Storage**: JSON file `benchmark-result.json` in the working directory
**Purpose**: Structured output from a benchmark run; machine-readable for comparison across
runs.

### Schema

```json
{
  "runId": "<UUID string>",
  "eventCount": 1000,
  "durationMs": 1842,
  "throughputPerSec": 542.8,
  "p50Ms": 1.2,
  "p95Ms": 3.7,
  "p99Ms": 8.1
}
```

### Fields

| Field | Type | Unit | Notes |
|---|---|---|---|
| `runId` | String (UUID) | — | Generated at benchmark start; unique per run |
| `eventCount` | int | messages | Configured via `xa-poc.benchmark.event-count` |
| `durationMs` | long | ms | Wall-clock duration of the full benchmark loop |
| `throughputPerSec` | double | msgs/sec | `eventCount / (durationMs / 1000.0)` |
| `p50Ms` | double | ms | 50th percentile commit latency from Micrometer Timer |
| `p95Ms` | double | ms | 95th percentile commit latency from Micrometer Timer |
| `p99Ms` | double | ms | 99th percentile commit latency from Micrometer Timer |

### Latency Definition

All latency values measure the wall-clock time from `EventProducerService.produceEvent()`
entry to its return (i.e., XA two-phase commit included). End-to-end consumer receipt latency
is tracked separately in `US3` acceptance tests using `Awaitility` timing.

---

## Relationships

```
EventProducerService
  │
  ├──[XA TX]──> SampleEvent (persisted to MySQL)
  └──[XA TX]──> EventMessage (sent to Artemis queue "sample.events")
                    │
                    └──> EventConsumerService (receives and logs)

BenchmarkRunner
  └──> EventProducerService (N times)
  └──> BenchmarkResult (written to benchmark-result.json)
```
