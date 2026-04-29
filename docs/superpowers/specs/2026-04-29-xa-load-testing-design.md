# Design: XA Load Testing — Phase 2

**Date**: 2026-04-29
**Status**: Approved
**Spec**: [specs/002-xa-load-testing/spec.md](../../../specs/002-xa-load-testing/spec.md)

## Goal

Validate the throughput and latency characteristics of the XA two-phase-commit path (MySQL + Apache Artemis) under sustained HTTP load using Gatling, with real-time visibility into XA internals via Prometheus and Grafana.

**Performance targets**: 5,000 RPS sustained, p95 < 100ms, p99 < 150ms.

> **Note**: Given that each XA transaction involves at minimum 4 network round-trips (prepare + commit on both MySQL and Artemis), these targets represent a ceiling-discovery exercise on a single developer machine rather than a guaranteed pass/fail gate. The result — whatever the ceiling is — is the valuable output.

---

## Architecture Overview

```
┌─────────────┐    POST /api/events     ┌──────────────────────────┐
│   Gatling   │ ──────────────────────► │   Spring Boot app :8080  │
│ load-tests/ │                         │   (XA producer + REST)   │
└─────────────┘                         └────────────┬─────────────┘
                                                     │ XA 2PC
                                          ┌──────────┴──────────┐
                                          ▼                     ▼
                                     MySQL :3306        Artemis :61616
                                                     (JMS broker)

Prometheus :9090  ◄── scrape /actuator/prometheus ── Spring Boot
Grafana :3000     ◄── query ──────────────────────── Prometheus
```

All five services (app, mysql, artemis, prometheus, grafana) run via `docker-compose up`.

---

## Components

### 1. REST Endpoint (change to main module)

A thin `POST /api/events` controller added to the existing Spring Boot application.

- **Request**: `POST /api/events` with JSON body `{"payload": "<string>"}`
- **Response**: `201 Created` with body `{"id": "<uuid>", "payload": "<string>"}`
- **Error**: `500 Internal Server Error` if the XA transaction fails
- **Logic**: delegates directly to the existing `EventProducerService.produceEvent()` — no XA logic in the controller
- **No authentication** (POC scope)

### 2. docker-compose stack

File: `docker-compose.yml` in the project root.

| Service | Image | Port | Notes |
|---------|-------|------|-------|
| `app` | built via `Dockerfile` | 8080 | waits on mysql + artemis health checks |
| `mysql` | `mysql:8.0` | 3306 | same config as Testcontainers tests |
| `artemis` | `apache/activemq-artemis:latest-alpine` | 61616 | same config as Testcontainers tests |
| `prometheus` | `prom/prometheus:latest` | 9090 | scrapes app every 5s |
| `grafana` | `grafana/grafana:latest` | 3000 | datasource + dashboard auto-provisioned |

Supporting config files under `docker/`:

```
docker/
  prometheus.yml                          # scrape config
  grafana/
    provisioning/
      datasources/prometheus.yml          # Prometheus datasource (auto-wired)
      dashboards/dashboards.yml           # dashboard folder config
      dashboards/xa-load-test.json        # pre-built dashboard (auto-loaded)
```

A `Dockerfile` at the project root builds the fat JAR with `mvn package -DskipTests` and runs it.

### 3. Gatling `load-tests/` Maven submodule

A new Maven module at `load-tests/` with `gatling-maven-plugin`. Simulation written in **Java** (Gatling 3.9+ Java DSL).

**Simulation design:**

| Phase | Duration | RPS |
|-------|----------|-----|
| Ramp-up | 60s | 0 → target |
| Sustained | 5 min | target (default 5000) |
| Cooldown | graceful stop | — |

Each virtual user sends `POST http://localhost:8080/api/events` with a unique UUID payload and asserts HTTP 201. Gatling records p50/p95/p99 natively.

**Configurable via system properties:**

| Property | Default | Purpose |
|----------|---------|---------|
| `gatling.baseUrl` | `http://localhost:8080` | Target URL |
| `gatling.peakRps` | `5000` | Sustained RPS target |
| `gatling.rampSeconds` | `60` | Ramp duration |
| `gatling.sustainSeconds` | `300` | Sustained duration |

**Output**: HTML report at `load-tests/target/gatling/*/index.html`.

The root `pom.xml` gains a `<modules>` block listing both modules. Running `mvn test` on the main module is unaffected — Gatling only runs via `mvn gatling:test -pl load-tests`.

### 4. Prometheus + Grafana monitoring

**Prometheus** scrapes `http://app:8080/actuator/prometheus` every 5 seconds. The existing `xa.transaction.duration` Micrometer histogram (instrumented in Phase 1) provides XA-internal latency, independent of the HTTP layer.

**Grafana dashboard** (`xa-load-test.json`) provisioned automatically with these panels:

| Panel | Query basis | Type |
|-------|-------------|------|
| XA transaction rate (RPS) | `xa_transaction_duration_seconds_count` | Time series |
| XA p50 / p95 / p99 latency | `xa_transaction_duration_seconds` histogram | Time series |
| HTTP request rate | `http_server_requests_seconds_count` | Time series |
| HTTP p95 / p99 latency | `http_server_requests_seconds` histogram | Time series |
| XA error rate (5xx) | `http_server_requests_seconds` status=5xx | Time series |
| JVM heap used | `jvm_memory_used_bytes` | Time series |

All panels share a live time window so Gatling ramp-up correlates visually with XA latency changes. Access at `http://localhost:3000` (admin / admin) — dashboard loads with no imports or manual steps.

---

## Data Flow

1. Gatling sends `POST /api/events` at configured RPS
2. Spring MVC controller receives request, calls `EventProducerService.produceEvent(payload)`
3. Atomikos starts XA transaction; MySQL XA resource and Artemis XA resource both enlisted
4. JPA saves `SampleEvent` to MySQL; JmsTemplate sends message to `sample.events` queue
5. Atomikos runs 2PC: prepare on both resources, then commit on both
6. Controller returns `201 Created` with event ID
7. Micrometer records `xa.transaction.duration`; `/actuator/prometheus` exposes it
8. Prometheus scrapes the endpoint; Grafana queries Prometheus
9. Gatling records HTTP latency; HTML report aggregates p50/p95/p99

---

## Running the Load Test

```bash
# Build the JAR
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home \
  mvn package -DskipTests

# Start the full stack
docker-compose up -d

# Wait ~30s for the app to be healthy, then run Gatling
mvn gatling:test -pl load-tests

# Open results
open load-tests/target/gatling/*/index.html

# Monitor live at http://localhost:3000 (Grafana, admin/admin)

# Tear down
docker-compose down -v
```

---

## File Changes Summary

| File | Change |
|------|--------|
| `src/main/java/.../controller/EventController.java` | New — REST endpoint |
| `Dockerfile` | New — builds fat JAR |
| `docker-compose.yml` | New — five-service stack |
| `docker/prometheus.yml` | New — scrape config |
| `docker/grafana/provisioning/datasources/prometheus.yml` | New — Grafana datasource |
| `docker/grafana/provisioning/dashboards/dashboards.yml` | New — dashboard folder config |
| `docker/grafana/provisioning/dashboards/xa-load-test.json` | New — pre-built dashboard |
| `load-tests/pom.xml` | New — Gatling submodule |
| `load-tests/src/gatling/java/.../XaLoadSimulation.java` | New — Gatling simulation |
| `pom.xml` | Modified — add `<modules>` block |

---

## Out of Scope

- Distributed load generation (multi-machine Gatling cluster)
- CI/CD integration
- Consumer throughput validation
- Cloud deployment
- Authentication on the REST endpoint
