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
