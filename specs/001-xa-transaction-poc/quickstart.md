# Quickstart: XA Transaction POC

**Branch**: `001-xa-transaction-poc`

## Prerequisites

- Java 25 (`java -version` should show 25.x)
- Docker and Docker Compose
- Maven Wrapper (`./mvnw`) — included in the project

## 1. Start Infrastructure

```bash
docker-compose up -d
```

Expected: MySQL 8 on port 3306 and Apache Artemis on port 61616 (web console on 8161).

Verify:
```bash
docker-compose ps
# Both containers should show "Up"
```

## 2. Build the Project

```bash
./mvnw clean compile -DskipTests
```

## 3. Run the Happy-Path Test (User Story 1)

Proves that a committed XA transaction delivers the event to both MySQL and Artemis.

```bash
./mvnw test -Dtest=HappyPathIT
```

**Expected output** (abbreviated):
```
INFO  EventProducerService - Producing event with payload: hello-world
INFO  EventConsumerService - Received message: {"eventId":"...","payload":"hello-world"}
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

## 4. Run the Fault-Injection Test (User Story 2)

Proves that an XA rollback (exception after JMS send, before DB commit) leaves the consumer
with no messages and the database empty.

```bash
./mvnw test -Dtest=FaultInjectionIT
```

**Expected output** (abbreviated):
```
INFO  EventProducerService - Fault injection active — throwing before DB commit
ERROR EventProducerService - XA transaction rolled back: Simulated fault
INFO  FaultInjectionIT - Consumer received 0 messages — XA rollback confirmed ✓
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

## 5. Run the Performance Benchmark (User Story 3)

Measures throughput and latency of committed XA transactions under load.

```bash
./mvnw test -Dtest=PerformanceBenchmarkIT
```

**Expected output** (abbreviated):
```
INFO  BenchmarkRunner - Benchmark complete: 1000 events in 1842ms
INFO  BenchmarkRunner - Throughput: 542.8 msgs/sec | p50: 1.2ms | p95: 3.7ms | p99: 8.1ms
INFO  BenchmarkRunner - Results written to benchmark-result.json
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

Read the result file:
```bash
cat benchmark-result.json | python3 -m json.tool
```

## 6. Run All Tests

```bash
./mvnw test
```

## 7. Stop Infrastructure

```bash
docker-compose down
```

## Profiles Reference

| Profile | Activation | Purpose |
|---|---|---|
| `test` | `@ActiveProfiles("test")` in tests | Testcontainers overrides |
| `fault` | `@ActiveProfiles("fault")` | Enables `xa-poc.fault-injection.enabled=true` |
| `benchmark` | `@ActiveProfiles("benchmark")` | Sets event count + Micrometer histogram config |

## Troubleshooting

**`Connection refused` to MySQL or Artemis**: Run `docker-compose up -d` and wait 15 seconds
for containers to fully start before running tests.

**Atomikos log corruption**: Delete `./atomikos-logs/` and retry. This directory is not shared
across test runs when properly configured, but manual cleanup resolves stale log state.

**`InaccessibleObjectException`**: Add to `maven-surefire-plugin` configuration:
```xml
<argLine>--add-opens java.base/java.lang=ALL-UNNAMED</argLine>
```

**Artemis jar conflict (`javax` vs `jakarta`)**: Ensure `artemis-jakarta-client` is on the
classpath, NOT `artemis-jms-client`. Check `./mvnw dependency:tree | grep artemis`.
