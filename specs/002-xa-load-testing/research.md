# Research: XA Load Testing — Phase 0

**Feature**: [spec.md](spec.md)
**Plan**: [plan.md](plan.md)
**Date**: 2026-04-29

## Decision 1: Gatling Load Generation

**Decision**: Use Gatling 3.9.5 with the Java DSL (`io.gatling.highcharts:gatling-charts-highcharts`) as a separate Maven submodule (`load-tests/`), driven by `gatling-maven-plugin:4.9.0`.

**Rationale**: Gatling is team-mandated. The Java DSL (available since Gatling 3.7) avoids Scala dependency while keeping access to all injection APIs. The `gatling-maven-plugin` version 4.x aligns with Gatling 3.9.x and provides the `gatling:test` goal used to isolate load test execution from the main `mvn test` cycle.

**Open-loop injection pattern**:
```java
setUp(
    scenario("XA Load")
        .exec(http("POST /api/events")
            .post("/api/events")
            .header("Content-Type", "application/json")
            .body(StringBody(session -> "{\"payload\": \"" + UUID.randomUUID() + "\"}"))
            .check(status().is(201)))
        .injectOpen(
            rampUsersPerSec(0).to(peakRps).during(rampSeconds),
            constantUsersPerSec(peakRps).during(sustainSeconds)
        )
).protocols(httpProtocol);
```

`rampUsersPerSec`/`constantUsersPerSec` are open-loop: the injector does not wait for previous users to complete before injecting new ones. This mirrors real-world HTTP load where arrivals are independent of service completion.

**Alternatives considered**:
- Closed-loop (`rampUsers`, `atOnceUsers`): rejected — closed-loop does not sustain target RPS when latency increases, which is exactly the condition under test.
- k6 / JMeter: rejected — team mandate specifies Gatling.

**Configuration via system properties** (set in `load-tests/pom.xml` `<systemProperties>` or passed on CLI):

| Property | Default | Notes |
|----------|---------|-------|
| `gatling.baseUrl` | `http://localhost:8080` | Override for remote targets |
| `gatling.peakRps` | `5000` | Integer; Gatling casts automatically |
| `gatling.rampSeconds` | `60` | Ramp-up window |
| `gatling.sustainSeconds` | `300` | Steady-state window |

**Output**: HTML report at `load-tests/target/gatling/*/index.html`. p50/p95/p99 are native Gatling stats; no custom parsing needed.

---

## Decision 2: Multi-Module Maven Structure

**Decision**: Add a `<modules>` block to the root `pom.xml` listing both the main module and `load-tests/`. The `load-tests/pom.xml` declares parent as the root pom and uses `gatling-maven-plugin`.

**Rationale**: The root pom's existing Surefire configuration (with two named executions) runs only classes matching `**/integration/**IT.java` and `**/benchmark/**IT.java`. Gatling classes live under `src/gatling/java/` and match neither pattern, so `mvn test` at root remains unaffected. Gatling runs only via `mvn gatling:test -pl load-tests`.

**Key `load-tests/pom.xml` structure**:
```xml
<build>
  <sourceDirectory>src/gatling/java</sourceDirectory>
  <testSourceDirectory>src/gatling/java</testSourceDirectory>
  <plugins>
    <plugin>
      <groupId>io.gatling</groupId>
      <artifactId>gatling-maven-plugin</artifactId>
      <version>4.9.0</version>
      <configuration>
        <simulationClass>com.example.xapoc.loadtest.XaLoadSimulation</simulationClass>
      </configuration>
    </plugin>
  </plugins>
</build>
```

**Alternatives considered**:
- Separate standalone project: rejected — shared parent pom simplifies dependency management.
- Surefire execution in the same module: rejected — Gatling uses its own classloader; mixing with Surefire causes classpath conflicts.

---

## Decision 3: docker-compose Health Checks and Startup Order

**Decision**: Use `depends_on` with `condition: service_healthy` for the `app` service. MySQL and Artemis each expose their own health checks. The app service starts only after both pass.

**MySQL health check**:
```yaml
healthcheck:
  test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-u", "root", "-p${MYSQL_ROOT_PASSWORD}"]
  interval: 5s
  timeout: 5s
  retries: 10
  start_period: 15s
```

**Artemis health check**: Artemis exposes a web console on port 8161. The health check uses `curl` against the REST management API:
```yaml
healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost:8161/console/"]
  interval: 5s
  timeout: 5s
  retries: 10
  start_period: 20s
```

**App service startup**: Spring Boot Actuator health endpoint confirms all datasource and JMS connections are up before the container is marked healthy:
```yaml
healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
  interval: 5s
  timeout: 5s
  retries: 15
  start_period: 30s
```

**Rationale**: `service_healthy` prevents race conditions where the app starts before MySQL schema migrations complete or Artemis accepts JMS connections. This was also the pattern validated by the Phase 1 Testcontainers integration tests.

**Alternatives considered**:
- `depends_on` without health checks (just `service_started`): rejected — Artemis needs 15–20s to fully initialize; app startup fails with connection refused.
- Shell `wait-for-it.sh` script: rejected — health checks are native to Compose and require no extra files.

---

## Decision 4: Dockerfile (Fat JAR Build)

**Decision**: Multi-stage build is unnecessary at POC scale. A single-stage `Dockerfile` using `eclipse-temurin:25-jdk` runs `mvn package -DskipTests` inside the container, then runs the fat JAR.

**Rationale**: The fat JAR produced by `spring-boot-maven-plugin` bundles all dependencies. For a POC on a single machine, build time inside Docker is acceptable. A multi-stage build would reduce final image size but adds complexity not warranted for local-only use.

**Java 25 note**: `eclipse-temurin:25-jdk` is the Adoptium Temurin distribution; it supports `--add-opens` JVM args required by Atomikos via the `JAVA_TOOL_OPTIONS` environment variable in the Compose `app` service.

---

## Decision 5: Prometheus Scrape Configuration

**Decision**: Prometheus scrapes `http://app:8080/actuator/prometheus` every 5 seconds. Service discovery is static (`static_configs`).

**`docker/prometheus.yml`**:
```yaml
global:
  scrape_interval: 5s
  evaluation_interval: 5s

scrape_configs:
  - job_name: 'xa-producer'
    static_configs:
      - targets: ['app:8080']
    metrics_path: '/actuator/prometheus'
```

**Rationale**: `scrape_interval: 5s` matches the FR-008 requirement of ≤5-second refresh. Static config is sufficient for a single-service POC; service discovery (Consul, Kubernetes) is out of scope.

---

## Decision 6: Grafana Auto-Provisioning

**Decision**: Grafana datasource and dashboard are provisioned via YAML files mounted at `/etc/grafana/provisioning/`. The dashboard JSON (`xa-load-test.json`) uses `__requires` to declare Prometheus datasource dependency.

**Provisioning directory structure** (mounted into the container):
```
docker/grafana/provisioning/
  datasources/
    prometheus.yml     ← registers Prometheus as default datasource
  dashboards/
    dashboards.yml     ← tells Grafana where to find dashboard JSON files
    xa-load-test.json  ← the actual dashboard
```

**`datasources/prometheus.yml`**:
```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
```

**`dashboards/dashboards.yml`**:
```yaml
apiVersion: 1
providers:
  - name: 'xa-load-test'
    orgId: 1
    folder: ''
    type: file
    disableDeletion: false
    updateIntervalSeconds: 10
    options:
      path: /etc/grafana/provisioning/dashboards
```

**Rationale**: File-based provisioning is the only approach that satisfies FR-011 (no manual imports). Grafana reads these files on startup; dashboards appear immediately at `http://localhost:3000`.

**Alternatives considered**:
- Grafana API import (`curl` POST in `docker-compose` command): rejected — requires the Grafana container to be fully started first; timing is fragile.
- Grafana image with embedded dashboard: rejected — adds a custom Dockerfile for monitoring-only infrastructure.

---

## Decision 7: Grafana Dashboard Panels

**Decision**: Six panels using Prometheus queries against Micrometer-exported metrics. All panels use a shared live time window so the Gatling ramp-up correlates visually with XA latency.

| Panel | PromQL (simplified) | Type |
|-------|---------------------|------|
| XA transaction rate | `rate(xa_transaction_duration_seconds_count[1m])` | Time series |
| XA p50/p95/p99 | `histogram_quantile(0.99, rate(xa_transaction_duration_seconds_bucket[5m]))` | Time series |
| HTTP request rate | `rate(http_server_requests_seconds_count{uri="/api/events"}[1m])` | Time series |
| HTTP p95/p99 | `histogram_quantile(0.99, rate(http_server_requests_seconds_bucket{uri="/api/events"}[5m]))` | Time series |
| XA error rate (5xx) | `rate(http_server_requests_seconds_count{status=~"5.."}[1m])` | Time series |
| JVM heap used | `jvm_memory_used_bytes{area="heap"}` | Time series |

**Rationale**: `xa_transaction_duration_seconds` is the Micrometer histogram from Phase 1. `histogram_quantile` with a 5-minute rate window smooths short spikes; 1-minute rate is sufficient for throughput panels.

---

## Alternatives Not Pursued

- **InfluxDB + Telegraf**: Gatling has a Graphite/InfluxDB reporter, but Prometheus pull model is simpler to configure and already present in the stack (Micrometer integration).
- **Testcontainers for load test**: Testcontainers manages ephemeral containers per test; sustained 5-minute load tests need persistent containers with stable ports — docker-compose is the correct tool.
- **Gatling Enterprise**: Cloud-based; POC scope is local single-machine only.
