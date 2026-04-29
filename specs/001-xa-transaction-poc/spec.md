# Feature Specification: XA Transaction POC — Reliable Message Production & Consumption

**Feature Branch**: `001-xa-transaction-poc`
**Created**: 2026-04-28
**Status**: Draft
**Input**: User description: "POC for XA transaction: Java 25 + Spring Boot 3.5, validate produce/consuming messages in ACID transactions. Producer persists sample events in MySQL DB and sends MQ messages to Apache Artemis. Consumer consumes these events. Failure handling: tests randomly produce exceptions before committing on producer DB but after sending the message — expected result: consumer should NOT receive this message. Also measure performance throughput and latency."

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Happy-Path: Committed Event Reaches Consumer (Priority: P1)

A developer triggers the producer to publish a sample event. The event is persisted to the
database and a corresponding message is sent to the broker. The consumer picks up the message
and processes it. Both operations succeed atomically.

**Why this priority**: This is the foundational correctness scenario — XA must work when
everything goes right before we can trust failure handling.

**Independent Test**: Start producer, consume one message, assert the message matches the
persisted DB record. Can be run as a standalone integration test with no other stories present.

**Acceptance Scenarios**:

1. **Given** the producer is running and connected to both MySQL and Artemis,
   **When** a sample event is published without any injected fault,
   **Then** the event record appears in the database AND the consumer receives exactly one
   matching message.

2. **Given** the consumer receives a message,
   **When** it processes the message successfully,
   **Then** no message is left in the queue (or dead-letter) and the database record is
   present and consistent.

---

### User Story 2 — Failure Scenario: Rolled-Back Producer Does Not Deliver (Priority: P1)

A developer triggers the producer with fault injection enabled. An exception is raised after
the MQ message has been sent but before the database commit. The XA transaction rolls back.
The consumer must receive no message.

**Why this priority**: This is the core correctness invariant being validated by the POC.
It is equally critical to the happy path.

**Independent Test**: Run producer with fault injection; poll consumer for 5 seconds; assert
zero messages received and zero DB records created.

**Acceptance Scenarios**:

1. **Given** the producer is configured with fault injection (exception after MQ send, before
   DB commit),
   **When** the producer runs,
   **Then** the XA transaction rolls back, the database contains no new record, and the
   consumer receives zero messages within 5 seconds.

2. **Given** a rollback has occurred,
   **When** the broker is inspected,
   **Then** no message remains in the queue or in any dead-letter queue attributable to this
   transaction.

---

### User Story 3 — Performance Baseline: Throughput and Latency Under Load (Priority: P2)

A developer runs a benchmark that drives the producer at increasing message rates. Results are
captured as structured output showing throughput (messages committed per second) and latency
distribution (p50, p95, p99) end-to-end from producer trigger to consumer receipt.

**Why this priority**: The POC must quantify whether XA overhead is acceptable for the
target use case. This is secondary to correctness but still mandatory for the POC sign-off.

**Independent Test**: Run the benchmark harness in isolation; assert results file is produced
and contains all required metrics; optionally assert values are within configured targets.

**Acceptance Scenarios**:

1. **Given** the producer and consumer are running against the real MySQL and Artemis stack,
   **When** the benchmark runs a sustained load for at least 60 seconds,
   **Then** structured output (JSON or CSV) is produced containing: throughput msg/s,
   p50 latency ms, p95 latency ms, p99 latency ms.

2. **Given** the benchmark output is produced,
   **When** results are compared against the constitution performance baseline,
   **Then** throughput is ≥ 500 committed msgs/sec and p95 commit latency is ≤ 200 ms.

---

### Edge Cases

- What happens when the Artemis broker is unavailable at producer start-up?
- What happens when MySQL is unavailable mid-transaction?
- What happens if the consumer crashes while processing a message — is the message redelivered?
- What is the observed behaviour when the XA transaction coordinator (JTA) times out?
- What happens when fault injection fires on every message vs. a random subset?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST enlist both the MySQL data source and the Artemis connection
  factory in a single XA/JTA distributed transaction for every produced event.
- **FR-002**: The producer MUST persist a sample event record to MySQL within the same XA
  transaction that sends the corresponding message to Artemis.
- **FR-003**: The consumer MUST subscribe to the Artemis queue and process received messages;
  it MUST log each received message with its content.
- **FR-004**: The system MUST provide a fault-injection mechanism that throws a configurable
  exception after the MQ send but before the DB commit, triggering an XA rollback.
- **FR-005**: When an XA rollback occurs (producer fault injected), the consumer MUST NOT
  receive the message that was part of the rolled-back transaction.
- **FR-006**: The system MUST produce benchmark results as structured output (JSON or CSV)
  containing: throughput (msgs/sec), p50 latency (ms), p95 latency (ms), p99 latency (ms).
- **FR-007**: Fault injection MUST be controllable via a configuration flag or Spring profile
  so it can be disabled for benchmark runs and enabled for failure-scenario tests.
- **FR-008**: The producer MUST support sending a configurable number of events in a single
  benchmark run.

### Key Entities

- **SampleEvent**: Represents a produced event. Key attributes: unique ID, payload (string),
  timestamp, status (COMMITTED / ROLLED_BACK). Persisted in MySQL.
- **EventMessage**: The MQ representation of a SampleEvent. Contains event ID and payload.
  Sent to Apache Artemis queue.
- **BenchmarkResult**: Captures performance metrics for a benchmark run. Contains: run ID,
  event count, duration, throughput, p50/p95/p99 latency values. Written to structured output.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In the happy-path scenario, 100% of committed events have a corresponding
  consumer receipt with matching payload — verified by integration test assertion.
- **SC-002**: In the failure scenario, 0 messages are received by the consumer within 5
  seconds of a producer rollback — verified by integration test assertion.
- **SC-003**: The benchmark harness consistently produces ≥ 500 committed messages per second
  under sustained load on reference hardware.
- **SC-004**: The p95 end-to-end commit latency (producer trigger → consumer receipt) is
  ≤ 200 ms under the benchmark load.
- **SC-005**: All benchmark results are captured in machine-readable format (JSON or CSV)
  enabling comparison across runs.

## Assumptions

- The POC runs on a single developer machine (no distributed/clustered environment).
- MySQL and Apache Artemis are available as local Docker containers or installed locally.
- A JTA-compliant transaction manager (Atomikos or Narayana) is embedded in the Spring Boot
  application; no external transaction coordinator is required.
- The "sample event" payload is a simple string or small JSON object — no binary payloads.
- Fault injection probability is 100% (always fires) in test mode, controllable via config.
- Performance targets (≥500 msgs/sec, ≤200ms p95) are for committed transactions only;
  rolled-back transactions are excluded from throughput/latency calculations.
- The consumer is a simple synchronous listener — no complex processing chain.
- No authentication or authorization is required for broker or database access in the POC.
