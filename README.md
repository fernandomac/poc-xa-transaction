# XA Transaction POC

Validates distributed XA transactions across MySQL and Apache Artemis using Spring Boot 3.5 + Atomikos. Proves that a single two-phase commit (2PC) coordinates a DB write and a JMS send atomically — if the transaction rolls back, neither the database record nor the queue message survives.

## What it validates

| Scenario | Expected outcome |
|----------|-----------------|
| Happy path: producer commits | DB record created + consumer receives message |
| Fault injection: exception after JMS send, before DB commit | XA rollback — consumer receives nothing, DB stays empty |
| Benchmark: 100 XA transactions | Throughput and latency (p50/p95/p99) reported to `benchmark-result.json` |

## Prerequisites

- Java 25 (`temurin-25` recommended)
- Maven 3.9+
- Docker (Testcontainers pulls MySQL 8 and Apache Artemis automatically)

## Running the tests

> All commands assume Java 25 is not your default JDK. Adjust `JAVA_HOME` if it is.

**Full test suite** (integration tests run first, benchmark last):

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home mvn test
```

**Integration tests only** (happy-path + fault injection):

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home mvn surefire:test@integration-tests
```

**Benchmark only**:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home mvn surefire:test@benchmark-tests
```

**Single test class**:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home mvn test -Dtest=HappyPathIT
```

## Tech stack

- **Spring Boot 3.5** + JTA (`spring.jta.enabled=true`)
- **Atomikos 6.0.0** — XA transaction manager (manually configured; the auto-configuration is incompatible with Spring Boot 3.3+)
- **MySQL 8** — XA-capable datasource (`MysqlXADataSource`)
- **Apache Artemis** — XA-capable JMS broker (`ActiveMQXAConnectionFactory`)
- **Testcontainers** — spins up MySQL and Artemis for every test run

## Load Testing

Gatling-based load tests live in the `load-tests/` directory. They drive `POST /api/events` against the full Docker stack (app + MySQL + Artemis) and expose live metrics in Grafana.

### Prerequisites

- Docker Desktop running
- Fat JAR built: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home mvn package -DskipTests`
- Ports 3000, 3306, 8080, 9090, 61616 free

### Start the stack

```bash
docker-compose up -d
docker-compose ps   # wait until all five services show "healthy"
```

### Scenarios

**Default (5,000 RPS for 5 min)**

```bash
mvn gatling:test -f load-tests/pom.xml
```

**Custom RPS / duration**

```bash
mvn gatling:test -f load-tests/pom.xml \
  -Dgatling.peakRps=1000 \
  -Dgatling.rampSeconds=30 \
  -Dgatling.sustainSeconds=120
```

**Remote target**

```bash
mvn gatling:test -f load-tests/pom.xml \
  -Dgatling.baseUrl=http://<host>:8080 \
  -Dgatling.peakRps=2000
```

All parameters and their defaults:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `gatling.baseUrl` | `http://localhost:8080` | Target host |
| `gatling.peakRps` | `5000` | Peak requests per second |
| `gatling.rampSeconds` | `60` | Ramp-up duration |
| `gatling.sustainSeconds` | `300` | Sustained load duration |

### Checking results

**Gatling HTML report** — opens automatically after each run:

```bash
open load-tests/target/gatling/*/index.html
```

Key thresholds: p95 < 100 ms, p99 < 150 ms, error rate ≤ 0.1%.

**Grafana dashboard** — live metrics during a running test:

Open `http://localhost:3000` (admin / admin). The **XA Load Test** dashboard loads automatically with six panels:

- XA transaction throughput and p50/p95/p99 latency
- HTTP request rate and p95/p99 latency
- XA error rate (5xx responses)
- JVM heap usage

Panels update every 5 seconds. No import step required.

**Prometheus raw metrics**: `http://localhost:9090`

### Persist and compare runs

Save the result of any Gatling run to a structured JSON file:

```bash
load-tests/scripts/save-result.sh
# writes load-tests/results/run-<timestamp>.json
```

Compare two runs and flag regressions > 10%:

```bash
load-tests/scripts/compare-results.sh \
  load-tests/results/run-<baseline>.json \
  load-tests/results/run-<candidate>.json
```

Exits 0 if all metrics are within 10%, exits 1 and prints `REGRESSION DETECTED` if any metric regresses.

### Tear down

```bash
docker-compose down -v   # -v removes the Prometheus data volume
```

---

### Running on a GCP VM

Use `docker-compose.gcp.yml` when Rancher Desktop or a local machine can't sustain the load. Key differences from the local compose file:

| | Local (`docker-compose.yml`) | GCP (`docker-compose.gcp.yml`) |
|---|---|---|
| App image | Pre-built JAR (`Dockerfile`) | Multi-stage build inside Docker (`Dockerfile.gcp`) |
| Platform | Host default | `linux/amd64` explicit |
| Resource limits | None | Set per service (prevents OOM kills) |
| Restart policy | None | `unless-stopped` / `on-failure` |
| Data volumes | Ephemeral | Named volumes (survive restarts) |
| Gatling | Run from host | Docker service (`--profile gatling`) |
| Reports | `load-tests/target/gatling/` | `./gatling-reports/` on VM disk |

#### Architecture: two VMs

Running the load generator on the same VM as the app skews results — Gatling and the JVM compete for CPU, and the Docker network adds overhead. The recommended setup uses two VMs in the same GCP region/VPC so traffic stays on Google's internal backbone (sub-millisecond latency, no egress cost):

```
┌─────────────────────────────┐      GCP internal network      ┌──────────────────────────┐
│  VM 1 — App VM              │ ────────────────────────────── │  VM 2 — Gatling VM       │
│  docker-compose.gcp.yml     │  http://<internal-ip>:8080     │  mvn gatling:test        │
│  app + mysql + artemis      │                                 │  Java + Maven only       │
│  prometheus + grafana       │                                 │  no Docker needed        │
└─────────────────────────────┘                                 └──────────────────────────┘
```

**Recommended VM sizes**: App VM → e2-standard-4 (4 vCPU, 16 GB) or larger. Gatling VM → e2-standard-2 (2 vCPU, 8 GB) is sufficient. Both must be in the same region.

#### VM 1 — App VM setup

SSH into the App VM, clone the repo, and run the full setup script:

```bash
git clone <repo-url> && cd poc-xa-transaction
chmod +x scripts/setup-gcp-vm.sh
./scripts/setup-gcp-vm.sh
newgrp docker
```

Start the stack (builds the app image from source):

```bash
docker compose -f docker-compose.gcp.yml up -d --build
docker compose -f docker-compose.gcp.yml ps   # wait for all healthy
```

Smoke test:

```bash
curl -X POST http://localhost:8080/api/events \
  -H "Content-Type: application/json" \
  -d '{"payload": "smoke-test"}' -w "\nHTTP %{http_code}\n"
```

Get the App VM's **internal** IP (use this from the Gatling VM — never the external IP):

```bash
hostname -I | awk '{print $1}'
```

#### VM 2 — Gatling VM setup

SSH into the Gatling VM, clone the repo, and run the lighter setup script (Java + Maven, no Docker):

```bash
git clone <repo-url> && cd poc-xa-transaction
chmod +x scripts/setup-gatling-vm.sh
./scripts/setup-gatling-vm.sh
source ~/.bashrc
```

Run the load test targeting VM 1's internal IP:

```bash
mvn gatling:test -f load-tests/pom.xml \
  -Dgatling.baseUrl=http://<APP-VM-INTERNAL-IP>:8080 \
  -Dgatling.peakRps=1000 \
  -Dgatling.rampSeconds=60 \
  -Dgatling.sustainSeconds=300
```

HTML report is written to `load-tests/target/gatling/*/index.html` on the Gatling VM.

**Check results**:

- Grafana: `http://<APP-VM-EXTERNAL-IP>:3000` (admin / admin) — requires GCP firewall port 3000 open, or use an SSH tunnel:

```bash
ssh -L 3000:localhost:3000 -L 9090:localhost:9090 <user>@<app-vm-external-ip>
# then open http://localhost:3000 locally
```

**Tear down**:

```bash
docker compose -f docker-compose.gcp.yml down -v
```

---

## Repomix

```aiignore
repomix --output "repomix.xml" \
  --style xml --compress --remove-comments --no-file-summary --output-show-line-numbers --token-count-tree \
  --style xml \
  --compress \
  --remove-comments \
  --no-file-summary \
  --output-show-line-numbers \
  --include "service/**/src/main/**,service/**/pom.xml,pom.xml,specs/**,docs/**,*.yml,*.yaml,*.md,CLAUDE.md" \
  --ignore "**/target/**,**/*.class,**/*.jar,**/test-output/**,**/functional-test/**,**/*.log,**/src/test/**,build_scripts/**,doc/**,docs/**,keyspipeline/**,scripts/**,.idea/**,.cursor/**,.claude/**,**/archunit_store/**,**/docker/**,keys/**,hs_err_pid*.log,.specify/**,repomix/**,.gitlab-ci.yml,pipeline/**,**/wiremock/**,**/fixtures/**"
```
