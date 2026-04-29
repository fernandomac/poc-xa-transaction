# Feature Specification: XA Load Testing

**Feature Branch**: `002-xa-load-testing`
**Created**: 2026-04-29
**Status**: Ready
**Design**: [docs/superpowers/specs/2026-04-29-xa-load-testing-design.md](../../docs/superpowers/specs/2026-04-29-xa-load-testing-design.md)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Sustained Load Validation (Priority: P1)

As a performance engineer, I want to drive the XA transaction system at 5,000 requests per second via an HTTP endpoint under sustained load so that I can determine whether the two-phase commit overhead fits within the agreed latency budget.

**Why this priority**: Without evidence that XA can sustain the required throughput and latency, the architecture cannot be adopted in production. This is the core go/no-go gate for Phase 2.

**Independent Test**: Start the five-service stack, run the load simulation for 5 minutes at the target rate, and read the generated HTML report. Pass/fail is deterministic and standalone.

**Acceptance Scenarios**:

1. **Given** the full stack is running, **When** HTTP load is applied at 5,000 requests/second for 5 minutes, **Then** p95 latency is below 100ms and p99 latency is below 150ms throughout the run.
2. **Given** the load test completes, **When** results are reviewed, **Then** the transaction error rate is at or below 0.1%.
3. **Given** a test run produces results, **When** the same test is repeated under identical conditions, **Then** latency measurements are within 10% of the previous run.

---

### User Story 2 - Real-Time Metrics Visibility (Priority: P2)

As a performance engineer, I want to observe XA throughput, latency, and error rate in real time during a load test via a pre-built dashboard so that I can identify the exact moment a bottleneck appears rather than discovering it only in post-run reports.

**Why this priority**: Real-time visibility reduces diagnosis time from hours to minutes and enables the engineer to abort a run early when results are clearly outside bounds.

**Independent Test**: Start the stack, open the monitoring dashboard, and verify that all metric panels update within 5 seconds. Can be validated independently of the sustained-load story.

**Acceptance Scenarios**:

1. **Given** a load test is in progress, **When** an observer opens the monitoring dashboard, **Then** throughput and latency figures are updated within the last 5 seconds.
2. **Given** the stack is started, **When** the monitoring URL is opened, **Then** a pre-built dashboard with XA transaction and HTTP metrics is immediately visible with no manual import or configuration.
3. **Given** latency exceeds any configured threshold, **When** the threshold is breached, **Then** the breach is visible in the real-time view within 10 seconds.

---

### User Story 3 - Benchmark Result Persistence and Comparison (Priority: P3)

As a team member, I want each load test run to persist its results so that I can compare performance across code changes and detect regressions.

**Why this priority**: Without historical comparison, performance improvements or regressions caused by code changes go undetected. This closes the feedback loop for ongoing XA optimisation.

**Independent Test**: Run two consecutive load tests and verify both result sets are stored and a comparison report highlights any metric that changed by more than 10%.

**Acceptance Scenarios**:

1. **Given** a load test completes, **When** results are stored, **Then** a report file contains run identifier, timestamp, event count, throughput, p50, p95, and p99.
2. **Given** two stored result sets exist, **When** a comparison is requested, **Then** the output flags any metric that differs by more than 10% between runs.
3. **Given** a regression is detected, **When** the comparison report is produced, **Then** the specific metric and magnitude of change are clearly stated.

---

### Edge Cases

- What happens when the system sustains load beyond 5,000 RPS — does latency degrade gracefully or cliff?
- How does the system behave when the target is unreachable or responds with errors during ramp-up?
- What is reported when a run is interrupted before completion?
- How are in-flight XA transactions counted if the load driver stops abruptly?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST expose an HTTP endpoint that accepts a produce-event request and processes it within a single XA transaction (DB write + JMS send, two-phase commit).
- **FR-002**: The endpoint MUST return a success response only after the XA transaction has fully committed; errors MUST return a failure response.
- **FR-003**: The load testing suite MUST be able to drive the endpoint at a configurable rate up to at least 5,000 requests per second.
- **FR-004**: The suite MUST measure and report p50, p95, and p99 end-to-end request latency.
- **FR-005**: The suite MUST report the request error rate for each run.
- **FR-006**: A run MUST be configurable for duration (minimum 5 minutes for a sustained baseline) and target RPS without code changes.
- **FR-007**: Results for each run MUST be persisted as a structured report containing: run ID, date/time, target RPS, achieved RPS, p50/p95/p99 latency, error rate, and total transaction count.
- **FR-008**: Real-time metrics (throughput, latency, error rate) covering both the HTTP boundary and XA internals MUST be observable during a run with a refresh interval no greater than 5 seconds.
- **FR-009**: The suite MUST produce an HTML summary report upon run completion.
- **FR-010**: The suite MUST support comparison between two stored result sets and flag regressions exceeding 10% on any metric.
- **FR-011**: The monitoring dashboard MUST load automatically when the stack is started, with no manual import or configuration required.

### Key Entities

- **Load Test Run**: A single execution of the load test; identified by a unique run ID; records configuration (target RPS, duration) and outcome metrics.
- **Metric Sample**: A point-in-time observation (timestamp, RPS, p50, p95, p99, error count) collected during a run for real-time display.
- **Benchmark Result**: The aggregated summary of a completed run, stored for later comparison.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The system processes XA transactions at 5,000 requests per second for a continuous 5-minute window with an error rate at or below 0.1%. This target represents a ceiling-discovery exercise; the measured ceiling — whatever it is — is the valuable outcome.
- **SC-002**: 99% of all transactions during a sustained run complete in under 150ms (p99 < 150ms).
- **SC-003**: 95% of all transactions during a sustained run complete in under 100ms (p95 < 100ms).
- **SC-004**: Full results including the HTML report are available within 2 minutes of run completion.
- **SC-005**: Two runs executed under identical infrastructure conditions produce p95 and p99 values within 10% of each other, confirming result reproducibility.
- **SC-006**: All monitoring panels are live and updating within 5 seconds of load test start, with no manual setup after stack startup.

## Assumptions

- The load testing tool is Gatling (explicitly specified by the team); technology choice is fixed.
- Gatling runs as a separate Maven submodule (`load-tests/`) and does not affect the existing `mvn test` execution.
- The XA producer is exposed via a thin `POST /api/events` HTTP REST endpoint added to the existing Spring Boot application; the endpoint delegates entirely to the existing XA producer service with no additional transaction logic.
- The full stack (Spring Boot app + MySQL + Apache Artemis + Prometheus + Grafana) runs via `docker-compose up`; Testcontainers is not used for load testing.
- Prometheus scrapes the app's existing `/actuator/prometheus` endpoint every 5 seconds; the `xa.transaction.duration` Micrometer histogram from Phase 1 is the primary XA-internal latency signal.
- The Grafana monitoring dashboard is provisioned automatically via configuration files; no manual imports or UI steps are required after `docker-compose up`.
- The target RPS (5,000) is the steady-state goal, not a spike target; ramp-up time of up to 60 seconds before steady state is acceptable.
- Results are stored locally (file system); no remote storage or CI/CD integration is required for Phase 2.
- A single load-generating machine is used; distributed load generation is out of scope.
- The XA consumer is assumed to keep pace with the producer; consumer throughput is not independently tested in this phase.
- Latency is measured end-to-end from the moment the load driver sends the HTTP request to the moment it receives the HTTP response confirming the XA transaction committed.
- Infrastructure sizing (CPU, memory, network) is sufficient; capacity planning is out of scope.
- No authentication is required on the REST endpoint (POC scope).
