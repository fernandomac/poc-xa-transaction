---
description: "Task list for XA Transaction POC — Reliable Message Production & Consumption"
---

# Tasks: XA Transaction POC — Reliable Message Production & Consumption

**Input**: Design documents from `specs/001-xa-transaction-poc/`
**Prerequisites**: spec.md (required for user stories)
**Branch**: `001-xa-transaction-poc`

**Organization**: Tasks are grouped by user story to enable independent implementation and
testing of each story.

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- Exact file paths are included in every task description

---

## Phase 1: Setup (Project Initialization)

**Purpose**: Bootstrap the Maven project and shared infrastructure configuration.

- [x] T001 Initialize Maven project: create `pom.xml` with Spring Boot 3.5 parent, Java 25 toolchain, dependencies (spring-boot-starter-jms, spring-boot-starter-data-jpa, mysql-connector-j, atomikos transactions-spring-boot3-starter:6.0.0, artemis-jakarta-client, micrometer-registry-prometheus, testcontainers + testcontainers-mysql + testcontainers-artemis, awaitility) and Maven Wrapper (`mvnw`, `.mvn/wrapper/`)
- [x] T002 Create Spring Boot main class `src/main/java/com/example/xapoc/XaTransactionPocApplication.java` annotated with `@SpringBootApplication` and `@EnableTransactionManagement`
- [x] T003 [P] Create source directory tree: `src/main/java/com/example/xapoc/{config,domain,repository,producer,consumer,benchmark}/` and `src/test/java/com/example/xapoc/{integration,benchmark}/`
- [x] T004 [P] Create `docker-compose.yml` at project root with MySQL 8 service (port 3306, database `xapoc`, user `xapoc`, password `xapoc`) and Apache Artemis 2.x service (port 61616 for AMQP/JMS, port 8161 for web console, default admin credentials)

**Checkpoint**: `./mvnw validate` compiles; `docker-compose up -d` starts both containers.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: XA infrastructure that ALL user stories depend on. No story can be tested
until this phase is complete.

**⚠️ CRITICAL**: Complete and verify each task before starting Phase 3.

- [x] T005 ~~Configure Atomikos JTA transaction manager in `AtomikosJtaConfig.java`~~ **SUPERSEDED**: Atomikos Spring Boot 3 starter (`transactions-spring-boot3-starter`) auto-configures `UserTransactionManager`, `UserTransaction`, and `JtaTransactionManager` — no manual config class needed.
- [x] T006 [P] Configure XA DataSource in `src/main/java/com/example/xapoc/config/DataSourceConfig.java`: create `MysqlXADataSource` from `application.yml` properties, wrap in `AtomikosDataSourceBean`, expose as `DataSource` bean
- [x] T007 [P] Configure XA JMS ConnectionFactory in `src/main/java/com/example/xapoc/config/JmsConfig.java`: create `ActiveMQXAConnectionFactory` from Artemis broker URL, wrap in `AtomikosConnectionFactoryBean`, define `JmsTemplate` and `DefaultJmsListenerContainerFactory`
- [x] T008 [P] Create `SampleEvent` JPA entity in `src/main/java/com/example/xapoc/domain/SampleEvent.java`: fields `id` (UUID, @GeneratedValue), `payload` (String, @Column(nullable=false)), `createdAt` (Instant, set in `@PrePersist`); mapped to table `sample_event`
- [x] T009 [P] Create `SampleEventRepository` in `src/main/java/com/example/xapoc/repository/SampleEventRepository.java` extending `JpaRepository<SampleEvent, UUID>`
- [x] T010 Create base `src/main/resources/application.yml` with Atomikos datasource/connectionfactory properties, Artemis broker URL, JPA DDL auto `create-drop`, logging levels
- [x] T011 Create `src/test/resources/application-test.yml` and `src/test/java/com/example/xapoc/AbstractIntegrationTest.java` with `MySQLContainer` + `ArtemisContainer` and `@DynamicPropertySource`

**Checkpoint**: A minimal `@SpringBootTest` loading the application context with Testcontainers passes without errors — XA beans wire correctly.

---

## Phase 3: User Story 1 — Happy-Path: Committed Event Reaches Consumer (Priority: P1) 🎯 MVP

**Goal**: Prove that when a producer publishes an event with no fault, exactly one DB record
is created AND the consumer receives exactly one matching message.

**Independent Test**: Run `HappyPathIT` in isolation; it starts MySQL + Artemis via
Testcontainers, sends one event, asserts the DB record exists and the consumer received a
message with matching payload.

### Implementation for User Story 1

- [x] T012 [US1] Implement `EventProducerService` in `src/main/java/com/example/xapoc/producer/EventProducerService.java`: method `produceEvent(String payload)` annotated `@Transactional`; saves `SampleEvent` via repository; sends JMS TextMessage to `sample.events`; fault injection flag included
- [x] T013 [P] [US1] Implement `EventConsumerService` in `src/main/java/com/example/xapoc/consumer/EventConsumerService.java`: `@JmsListener(destination="sample.events")` collects messages in `CopyOnWriteArrayList<String>` with `getReceivedMessages()` and `clearMessages()` for test assertions
- [x] T014 [US1] Write `HappyPathIT` in `src/test/java/com/example/xapoc/integration/HappyPathIT.java`: assert 1 consumer message + 1 DB record after `produceEvent("hello-world")`

**Checkpoint**: `./mvnw test -Dtest=HappyPathIT` passes green. US1 is independently validated.

---

## Phase 4: User Story 2 — Failure Scenario: Rolled-Back Producer Delivers Nothing (Priority: P1)

**Goal**: Prove that an XA rollback (exception after MQ send, before DB commit) leaves the
consumer with zero messages and the database with zero records.

**Independent Test**: Run `FaultInjectionIT` in isolation; enable fault profile; send one
event; assert consumer receives nothing within 5 seconds and DB is empty.

### Implementation for User Story 2

- [x] T015 [US2] Fault injection added to `EventProducerService`: `@Value("${xa-poc.fault-injection.enabled:false}") boolean faultInjectionEnabled`; throws `RuntimeException` after `jmsTemplate.send(...)` when enabled
- [x] T016 [P] [US2] Create `src/main/resources/application-fault.yml`: `xa-poc.fault-injection.enabled: true` + Atomikos DEBUG logging
- [x] T017 [US2] Write `FaultInjectionIT` in `src/test/java/com/example/xapoc/integration/FaultInjectionIT.java` with `@ActiveProfiles("fault")`: assert 0 messages received in 5s + 0 DB records

**Checkpoint**: `./mvnw test -Dtest=FaultInjectionIT` passes green — XA rollback confirmed.

---

## Phase 5: User Story 3 — Performance Baseline: Throughput and Latency Under Load (Priority: P2)

**Goal**: Quantify XA overhead — measure committed throughput (msgs/sec) and latency
distribution (p50, p95, p99 ms) end-to-end, and export results as JSON.

**Independent Test**: Run `PerformanceBenchmarkIT` in isolation; it produces a `benchmark-result.json` file and asserts it contains all required metric fields.

### Implementation for User Story 3

- [x] T018 [P] [US3] Create `BenchmarkResult` record in `src/main/java/com/example/xapoc/benchmark/BenchmarkResult.java`: fields `runId`, `eventCount`, `durationMs`, `throughputPerSec`, `p50Ms`, `p95Ms`, `p99Ms` with `@JsonProperty`
- [x] T019 [P] [US3] Create `src/main/resources/application-benchmark.yml`: `xa-poc.benchmark.event-count: 1000` + Micrometer percentile histogram config
- [x] T020 [US3] Implement `BenchmarkRunner` in `src/main/java/com/example/xapoc/benchmark/BenchmarkRunner.java`: Micrometer `Timer` with `publishPercentiles(0.50, 0.95, 0.99)`, loops `eventCount` times, extracts `HistogramSnapshot`, writes `benchmark-result.json` via Jackson
- [x] T021 [US3] Write `PerformanceBenchmarkIT` in `src/test/java/com/example/xapoc/benchmark/PerformanceBenchmarkIT.java` with `@ActiveProfiles("benchmark")`: validates result file structure and p50 ≤ p95 ≤ p99 ordering

**Checkpoint**: `./mvnw test -Dtest=PerformanceBenchmarkIT` passes; `benchmark-result.json` is present with all metric fields.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Documentation, cleanup, and end-to-end validation.

- [x] T022 [P] `specs/001-xa-transaction-poc/quickstart.md` — created during plan phase with docker-compose startup, per-test commands, and troubleshooting
- [x] T023 [P] `CLAUDE.md` updated between `<!-- SPECKIT START -->` and `<!-- SPECKIT END -->` to reference `specs/001-xa-transaction-poc/plan.md`
- [x] T024 Code cleanup: profiles documented in `application.yml` comments; no `System.out.println` in production classes; `.gitignore` created covering `target/`, `atomikos-logs/`, `benchmark-result.json`, `*.iml`, `.DS_Store`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 completion — BLOCKS all user stories
- **US1 — Phase 3**: Depends on Phase 2 completion; no dependency on US2 or US3
- **US2 — Phase 4**: Depends on Phase 2 completion AND T012 (EventProducerService from US1)
- **US3 — Phase 5**: Depends on Phase 2 completion AND T012 (EventProducerService)
- **Polish (Phase 6)**: Depends on all user stories being complete

### Parallel Opportunities

```bash
# Phase 1 — launch together:
T003  # Directory structure
T004  # docker-compose.yml

# Phase 2 — after T001/T002, T006+ after T005:
T006  # XA DataSource  \
T007  # XA JMS Config   > all parallel after T005 (auto-configured by starter)
T008  # SampleEvent     /
T009  # Repository      /

# Phase 3 — after Phase 2:
T012  # EventProducerService  \
T013  # EventConsumerService   > parallel

# Phase 5 — after T012:
T018  # BenchmarkResult  \
T019  # application-benchmark.yml > parallel
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001–T004)
2. Complete Phase 2: Foundational (T005–T011)
3. Complete Phase 3: User Story 1 (T012–T014)
4. **STOP and VALIDATE**: Run `./mvnw test -Dtest=HappyPathIT` — must pass
5. XA happy path proven ✓

### Incremental Delivery

1. Setup + Foundational → XA infrastructure ready
2. US1 (Happy Path) → `HappyPathIT` green ✓ (MVP)
3. US2 (Fault Injection) → `FaultInjectionIT` green ✓
4. US3 (Benchmark) → `PerformanceBenchmarkIT` green + `benchmark-result.json` ✓
5. Polish → Documentation complete

---

## Notes

- `[P]` tasks = different files, no incomplete dependencies — safe to parallelize
- `[Story]` maps each task to its user story for traceability
- T005 was superseded: Atomikos starter auto-configures JTA — no `AtomikosJtaConfig.java` needed
- Atomikos uniqueResourceName values (`mysqlXaDs`, `artemisXaCf`) MUST be unique per JVM
- Use `@ActiveProfiles` in tests for fault/benchmark modes; base profile is `test`
- `EventConsumerService.clearMessages()` MUST be called in `@BeforeEach` to isolate tests
- Performance thresholds (≥500 msg/s, ≤200ms p95) apply to real Docker infra, not Testcontainers
