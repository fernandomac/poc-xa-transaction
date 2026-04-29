# Quickstart: XA Load Testing

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)
**Date**: 2026-04-29

## Prerequisites

- Docker Desktop (or Docker Engine + Compose plugin) running
- Java 25 (`JAVA_HOME` pointing to Temurin 25 JDK)
- Maven 3.9+
- Ports 3000, 3306, 8080, 8161, 9090, 61616 free

Verify:
```bash
docker info
java -version   # should print: openjdk 25...
mvn -version
```

---

## Scenario 1: Full Stack Load Test (Primary Scenario)

This is the end-to-end happy path. It validates User Story 1 (sustained load) and User Story 2 (real-time metrics).

### Step 1: Build the application JAR

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home \
  mvn package -DskipTests
```

Expected: `BUILD SUCCESS` and `target/xa-poc-*.jar` created.

### Step 2: Start the full stack

```bash
docker-compose up -d
```

Wait for all services to be healthy:
```bash
docker-compose ps
```

All five services (`app`, `mysql`, `artemis`, `prometheus`, `grafana`) should show `healthy`.

The `app` service takes ~30 seconds to pass its health check after MySQL and Artemis are ready.

### Step 3: Verify the endpoint manually

```bash
curl -X POST http://localhost:8080/api/events \
  -H "Content-Type: application/json" \
  -d '{"payload": "smoke-test"}' \
  -w "\nHTTP %{http_code}\n"
```

Expected: `HTTP 201` and a JSON body with `id` and `payload`.

### Step 4: Open the Grafana dashboard

Open `http://localhost:3000` in a browser (admin / admin).

The **XA Load Test** dashboard loads automatically. All panels display "No data" until the load test begins.

### Step 5: Run the Gatling load test

```bash
mvn gatling:test -f load-tests/pom.xml
```

Default parameters: 60s ramp to 5,000 RPS → 5 minutes sustained at 5,000 RPS.

While the test runs, the Grafana dashboard updates every 5 seconds with live XA and HTTP metrics.

### Step 6: Review results

**Gatling HTML report**:
```bash
open load-tests/target/gatling/*/index.html
```

Key metrics to check:
- Global p95 < 100ms
- Global p99 < 150ms
- Error rate ≤ 0.1%

**Grafana**: Review the time-series panels to correlate the ramp-up shape with XA latency changes.

### Step 7: Tear down

```bash
docker-compose down -v
```

The `-v` flag removes the Prometheus data volume. Omit it to retain historical metrics across runs.

---

## Scenario 2: Custom RPS Target

Override the default 5,000 RPS to probe the system ceiling at a lower rate:

```bash
mvn gatling:test -f load-tests/pom.xml \
  -Dgatling.peakRps=1000 \
  -Dgatling.rampSeconds=30 \
  -Dgatling.sustainSeconds=120
```

---

## Scenario 3: Remote Target

Point the load driver at a remote host:

```bash
mvn gatling:test -f load-tests/pom.xml \
  -Dgatling.baseUrl=http://192.168.1.100:8080 \
  -Dgatling.peakRps=2000
```

The target host must have the Spring Boot app running with MySQL and Artemis reachable.

---

## Scenario 4: Existing Integration Test Suite

To verify the existing XA correctness tests are unaffected:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home \
  mvn test
```

This runs only the Surefire integration and benchmark tests — Gatling is not invoked.

---

## Troubleshooting

### App service not starting

Check logs:
```bash
docker-compose logs app
```

Common causes:
- MySQL not yet accepting connections — wait longer and retry
- Port 8080 already in use — stop conflicting processes

### Gatling reports all requests as errors

The `app` service may not be healthy yet. Run the smoke-test curl (Step 3) before starting Gatling.

### Grafana shows no data

Confirm Prometheus can scrape the app:
```bash
curl http://localhost:9090/targets
```

The `xa-producer` target should show `State: UP`.

### 5,000 RPS not sustained

On a single developer machine with a local MySQL and Artemis, XA 2PC involves ≥4 network round-trips per transaction. The ceiling depends on available CPU and I/O. The test is a ceiling-discovery exercise — the measured maximum is the valuable output regardless of whether 5,000 RPS is achieved.
