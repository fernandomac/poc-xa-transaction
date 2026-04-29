# Tasks: XA Load Testing

**Input**: Design documents from `specs/002-xa-load-testing/`
**Prerequisites**: plan.md ✅ | spec.md ✅ | research.md ✅ | contracts/ ✅ | quickstart.md ✅

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Add Gatling submodule and Docker build for the fat JAR.

**Note**: `load-tests` is a standalone Maven project (no parent inheritance) to avoid Maven's packaging constraint. Run Gatling with `cd load-tests && mvn gatling:test` or `mvn gatling:test -f load-tests/pom.xml`.

- [X] T001 [P] Add `spring-boot-starter-web` to root `pom.xml` (required for `@RestController`)
- [X] T002 [P] Create `load-tests/pom.xml` — standalone Gatling module with `gatling-charts-highcharts:3.9.5` and `gatling-maven-plugin:4.9.0`; source dir `src/gatling/java`; Java 17 compiler target
- [X] T003 [P] Create `Dockerfile` — single-stage `eclipse-temurin:25-jdk`; installs Maven 3.9.11 via curl; runs `mvn package -pl . -DskipTests`; entrypoint runs fat JAR

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: docker-compose stack and Prometheus config that ALL user stories depend on.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T004 Create `docker-compose.yml` — five-service stack: `app` (built from Dockerfile), `mysql:8.0`, `apache/activemq-artemis:latest-alpine`, `prom/prometheus:latest`, `grafana/grafana:latest`; `app` depends on `mysql` and `artemis` with `condition: service_healthy`; mount prometheus and grafana config from `docker/`
- [X] T005 [P] Create `docker/prometheus.yml` — scrape `http://app:8080/actuator/prometheus` every 5 seconds via static_configs
- [X] T006 [P] Create `docker/grafana/provisioning/datasources/prometheus.yml` — register Prometheus at `http://prometheus:9090` as the default Grafana datasource with uid `prometheus`

**Checkpoint**: `docker-compose up -d` starts all five services; `docker-compose ps` shows all healthy.

---

## Phase 3: User Story 1 — Sustained Load Validation (Priority: P1) 🎯 MVP

**Goal**: Expose `POST /api/events` on the Spring Boot app and drive it with a Gatling simulation at up to 5,000 RPS for 5 minutes.

**Independent Test**: `docker-compose up -d` → smoke-test curl returns `201` → `cd load-tests && mvn gatling:test` completes with an HTML report at `load-tests/target/gatling/*/index.html`.

### Implementation for User Story 1

- [X] T007 [P] [US1] Create `src/main/java/com/example/xapoc/controller/EventController.java` — `@RestController` with `POST /api/events`; accepts `{"payload": "<string>"}` body; delegates to `EventProducerService.produceEvent()`; returns `201 Created` with `{"id": "<uuid>", "payload": "<string>"}` on success; returns `500` if the XA transaction throws
- [X] T008 [P] [US1] Create `load-tests/src/gatling/java/com/example/xapoc/loadtest/XaLoadSimulation.java` — Java DSL `Simulation`; reads system properties `gatling.baseUrl` (default `http://localhost:8080`), `gatling.peakRps` (default `5000`), `gatling.rampSeconds` (default `60`), `gatling.sustainSeconds` (default `300`); open-loop injection: `rampUsersPerSec(0).to(peakRps).during(rampSeconds)` + `constantUsersPerSec(peakRps).during(sustainSeconds)`; asserts `status().is(201)`
- [X] T009 [US1] Verify end-to-end: build JAR, start stack, smoke-test curl (expect `201`), run Gatling test, confirm HTML report generated

**Checkpoint**: Gatling HTML report at `load-tests/target/gatling/*/index.html` with p50/p95/p99 and error rate visible. User Story 1 is independently testable.

---

## Phase 4: User Story 2 — Real-Time Metrics Visibility (Priority: P2)

**Goal**: Pre-built Grafana dashboard loads automatically with no manual import. All panels update within 5 seconds of load test start.

**Independent Test**: `docker-compose up -d` → open `http://localhost:3000` (admin/admin) → **XA Load Test** dashboard is immediately visible with six panels; panels update live during a running Gatling test.

### Implementation for User Story 2

- [X] T010 [P] [US2] Create `docker/grafana/provisioning/dashboards/dashboards.yml` — file provider pointing to `/etc/grafana/provisioning/dashboards`; `updateIntervalSeconds: 10`
- [X] T011 [P] [US2] Create `docker/grafana/provisioning/dashboards/xa-load-test.json` — Grafana dashboard JSON with six time-series panels for XA rate, XA latency, HTTP rate, HTTP latency, error rate, JVM heap; datasource uid `prometheus`; refresh `5s`

**Checkpoint**: After `docker-compose up -d`, navigating to `http://localhost:3000` shows the **XA Load Test** dashboard immediately with no import step. User Story 2 is independently testable.

---

## Phase 5: User Story 3 — Benchmark Result Persistence and Comparison (Priority: P3)

**Goal**: Each Gatling run persists a structured JSON result file; a comparison script flags regressions >10% between any two stored runs.

**Independent Test**: Run two consecutive Gatling tests → two JSON files appear in `load-tests/results/` → run the compare script → output flags any metric differing by >10%.

### Implementation for User Story 3

- [X] T012 [US3] Create `load-tests/scripts/save-result.sh` — reads latest Gatling stats.json, extracts metrics (achievedRps, p50/p95/p99Ms, errorRate, totalRequests), writes `load-tests/results/run-<timestamp>.json`
- [X] T013 [US3] Create `load-tests/scripts/compare-results.sh <file1.json> <file2.json>` — prints metric comparison table, exits 1 and prints `REGRESSION DETECTED` if any metric differs by >10%

**Checkpoint**: Two JSON result files → compare script outputs table and flags regressions. User Story 3 is independently testable.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final verification and cleanup.

- [X] T014 [P] Verify `mvn test` at repo root doesn't invoke Gatling — confirmed via dry-run and compilation check
- [X] T015 [P] Run quickstart scenarios from `specs/002-xa-load-testing/quickstart.md` (requires Docker stack running)
- [X] T016 Create `.dockerignore` and update `.gitignore` with Gatling output and results paths

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — T001, T002, T003 all parallelizable
- **Foundational (Phase 2)**: Depends on Phase 1 completion — T005, T006 can run in parallel after T001-T003
- **US1 (Phase 3)**: Depends on Phase 2 — T007 and T008 parallelizable; T009 must follow T007+T008
- **US2 (Phase 4)**: Depends on Phase 2 — T010 and T011 can run in parallel; US2 can run in parallel with US1
- **US3 (Phase 5)**: Depends on US1 completion (Gatling run must exist before scripts can be tested)
- **Polish (Phase 6)**: Depends on all user stories complete

### Run Commands

```bash
# Build fat JAR
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home \
  mvn package -pl . -DskipTests

# Start full stack
docker-compose up -d

# Smoke test
curl -X POST http://localhost:8080/api/events \
  -H "Content-Type: application/json" \
  -d '{"payload": "smoke-test"}' -w "\nHTTP %{http_code}\n"

# Run Gatling load test
cd load-tests && mvn gatling:test

# Save result and compare
./scripts/save-result.sh
./scripts/compare-results.sh results/run-<baseline>.json results/run-<candidate>.json

# Tear down
docker-compose down -v
```

---

## Notes

- [P] tasks touch different files with no shared dependencies — safe to execute concurrently
- `load-tests` is a standalone Maven module (not a child of the root pom) — Maven 3.9.11 requires `pom` packaging for aggregator projects
- The existing `mvn test` Surefire tests remain unaffected — Gatling classes don't match Surefire include patterns
- US3 scripts require python3 (for JSON parsing)
