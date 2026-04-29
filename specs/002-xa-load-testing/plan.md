# Implementation Plan: XA Load Testing

**Branch**: `002-xa-load-testing` | **Date**: 2026-04-29 | **Spec**: [spec.md](spec.md)
**Design**: [docs/superpowers/specs/2026-04-29-xa-load-testing-design.md](../../docs/superpowers/specs/2026-04-29-xa-load-testing-design.md)

## Summary

Add an HTTP REST endpoint to the existing Spring Boot XA producer, wrap the full infrastructure (MySQL + Artemis + Prometheus + Grafana) in `docker-compose`, and add a separate `load-tests/` Maven submodule containing a Gatling Java DSL simulation that drives `POST /api/events` at up to 5,000 RPS with configurable ramp/sustain phases. Real-time XA-internal metrics are exposed via the existing `/actuator/prometheus` endpoint, collected by Prometheus, and displayed on a pre-provisioned Grafana dashboard.

## Technical Context

**Language/Version**: Java 25 (main module unchanged); Java 17 compatible DSL for Gatling simulation
**Primary Dependencies**:
- Main module: Spring Boot 3.5, Atomikos 6.0.0 (unchanged)
- Load-tests module: `io.gatling.highcharts:gatling-charts-highcharts:3.9.5`, `io.gatling:gatling-maven-plugin:4.9.0`
- Infrastructure: `mysql:8.0`, `apache/activemq-artemis:latest-alpine`, `prom/prometheus:latest`, `grafana/grafana:latest`

**Storage**: MySQL 8 (XA, unchanged). Gatling HTML report in `load-tests/target/gatling/`. Prometheus TSDB in docker volume.
**Testing**: Gatling simulation (open-loop injection). Existing Surefire tests unaffected.
**Target Platform**: Local Docker daemon (single machine), `docker-compose up`
**Project Type**: Multi-module Maven — existing app module + new `load-tests` submodule
**Performance Goals**: 5,000 RPS sustained for 5 minutes; p95 < 100ms, p99 < 150ms (ceiling-discovery target)
**Constraints**: Single load-generating machine; no auth; no CI/CD; ramp-up ≤60s before steady state
**Scale/Scope**: POC — one simulation, one dashboard, one docker-compose file

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I — XA Transaction Integrity | ✅ PASS | REST controller delegates to existing `EventProducerService.produceEvent()` — no change to XA path |
| II — Failure-Scenario Coverage | ✅ PASS | No new failure paths introduced; Phase 1 coverage unchanged |
| III — Performance Observability | ✅ PASS | Gatling HTML report + Micrometer `xa.transaction.duration` histogram + Grafana dashboard; runs against real stack |
| IV — Technology Stack | ✅ PASS | Java 25, Spring Boot 3.5, MySQL, Artemis maintained; Gatling is additive to test layer |
| V — POC Scope | ✅ PASS | REST endpoint is explicitly required for Gatling test driving; Grafana/Prometheus are observability-only |

**Constitutional note**: Principle V lists "REST API layers beyond what is needed for test driving" as out of scope. The `POST /api/events` endpoint is precisely what is needed for test driving — it is in scope.

Post-design re-check: no violations found.

## Project Structure

### Documentation (this feature)

```text
specs/002-xa-load-testing/
├── plan.md           ← this file
├── research.md       ← Phase 0 output
├── data-model.md     ← Phase 1 output (N/A — no new entities)
├── contracts/        ← Phase 1 output
│   └── event-producer-api.md
├── quickstart.md     ← Phase 1 output
└── tasks.md          ← Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
src/
  main/java/com/example/xapoc/
    controller/
      EventController.java          ← NEW: POST /api/events
    (all existing classes unchanged)

load-tests/                         ← NEW: Gatling Maven submodule
  pom.xml
  src/
    gatling/
      java/com/example/xapoc/loadtest/
        XaLoadSimulation.java       ← NEW: Gatling Java DSL simulation

docker-compose.yml                  ← NEW: five-service stack
Dockerfile                          ← NEW: builds fat JAR

docker/                             ← NEW: infra config files
  prometheus.yml
  grafana/
    provisioning/
      datasources/
        prometheus.yml
      dashboards/
        dashboards.yml
        xa-load-test.json

pom.xml                             ← MODIFIED: add <modules> block
```

**Structure Decision**: Multi-module Maven. The `load-tests/` module is a peer of the root module. Running `mvn test` at the root still only runs the existing Surefire integration tests (integration-tests and benchmark-tests executions). Gatling runs only via `mvn gatling:test -pl load-tests`.

## Complexity Tracking

No constitution violations requiring justification.
