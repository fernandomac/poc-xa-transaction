# Research: XA Transaction POC — Spring Boot 3.5 + Java 25

**Branch**: `001-xa-transaction-poc` | **Date**: 2026-04-28
**Status**: Complete — all NEEDS CLARIFICATION resolved

---

## Decision 1: JTA Transaction Manager

**Decision**: Atomikos via `com.atomikos:transactions-spring-boot3-starter:6.0.0`

**Rationale**: Spring Boot 3.x removed the built-in vendor JTA starters
(`spring-boot-starter-jta-atomikos`, `spring-boot-starter-jta-narayana`). Atomikos now ships
its own Spring Boot 3-compatible starter (`transactions-spring-boot3-starter`) that restores
equivalent auto-configuration. It automatically registers `XADataSourceWrapper` and
`XAConnectionFactoryWrapper` beans that Spring Boot's existing JDBC/JMS auto-configuration
picks up. This is the vendor-maintained path with the best documentation for MySQL + JMS XA
scenarios.

**Alternatives considered**:
- Narayana (`me.snowdrop:narayana-spring-boot-starter`) — also supports Spring Boot 3 but is
  community-maintained; fewer real-world examples for the MySQL + Artemis combination.
- Manual JTA configuration without any starter — feasible but adds significant boilerplate
  (`UserTransactionManager`, `UserTransaction`, `JtaTransactionManager` beans all by hand).

**Maven coordinate** (not managed by Spring Boot BOM — pin explicitly):
```xml
<dependency>
    <groupId>com.atomikos</groupId>
    <artifactId>transactions-spring-boot3-starter</artifactId>
    <version>6.0.0</version>
</dependency>
```

**Java 25 note**: Atomikos 6.x is compiled for Java 11+ and runs fine on Java 25. No known
breaking changes from the module system. If `InaccessibleObjectException` appears, add
`--add-opens` to `maven-surefire-plugin`.

---

## Decision 2: MySQL XA DataSource Configuration

**Decision**: Declare `MysqlXADataSource` bean manually; Atomikos starter wraps it automatically.

**Rationale**: MySQL Connector/J ships `com.mysql.cj.jdbc.MysqlXADataSource` which implements
`javax.sql.XADataSource`. The Atomikos starter registers an `XADataSourceWrapper` that, when
given an `XADataSource` bean, produces a pooled `AtomikosDataSourceBean`. This avoids
writing Atomikos pool configuration in code — only the raw XA datasource needs to be declared.

**Critical flags** (must be set on `MysqlXADataSource`):
- `pinGlobalTxToPhysicalConnection=true` — prevents MySQL from confusing XA sessions when
  connections are reused from the pool
- JDBC URL must include `allowXAStatements=true` — enables the driver to issue XA commands

**Application properties** for Atomikos pool (read by `AtomikosDataSourceBean`):
```yaml
spring.jta.atomikos.datasource:
  unique-resource-name: mysqlXaDs
  max-pool-size: 10
  min-pool-size: 2
  borrow-connection-timeout: 30
  test-query: "SELECT 1"
spring.jta.atomikos.properties:
  log-base-dir: ./atomikos-logs
  transaction-manager-unique-id: xa-poc-tm
  max-timeout: 60000
```

**Maven coordinate**: `com.mysql:mysql-connector-j` — managed by Spring Boot BOM.

**Do NOT wrap in HikariCP**: Atomikos manages its own connection pool for XA datasources.
Adding HikariCP breaks XA enrollment.

---

## Decision 3: Apache Artemis XA JMS ConnectionFactory Configuration

**Decision**: Declare `ActiveMQXAConnectionFactory` bean from `artemis-jakarta-client`; Atomikos starter wraps it.

**Rationale**: Artemis implements the JMS API and ships `ActiveMQXAConnectionFactory` which
implements `javax.jms.XAConnectionFactory`. The Atomikos starter registers an
`XAConnectionFactoryWrapper` that wraps it into an `AtomikosConnectionFactoryBean`.

**Critical**: Use `artemis-jakarta-client` (Jakarta EE 10 / `jakarta.*` namespaces), NOT
`artemis-jms-client` (which uses `javax.*` namespaces and is incompatible with Spring Boot 3).

**Maven coordinate** (managed by Spring Boot BOM):
```xml
<dependency>
    <groupId>org.apache.activemq</groupId>
    <artifactId>artemis-jakarta-client</artifactId>
</dependency>
```

**Application properties** for Atomikos JMS pool:
```yaml
spring.jta.atomikos.connectionfactory:
  unique-resource-name: artemisXaCf
  max-pool-size: 10
  min-pool-size: 2
  local-transaction-mode: false
spring.artemis:
  mode: native
  broker-url: "tcp://localhost:61616"
  user: admin
  password: admin
```

---

## Decision 4: Fault Injection Mechanism

**Decision**: `@Value`-injected boolean flag in `EventProducerService`, controlled via Spring profile.

**Rationale**: A simple configurable flag is transparent, testable, and trivially toggled via
application properties or `@ActiveProfiles`. No AOP, no separate classpath dependencies, no
method-signature coupling. The flag is off by default (`false`) — production safety guaranteed
by the default value.

**Pattern**:
```java
@Value("${xa-poc.fault-injection.enabled:false}")
private boolean faultInjectionEnabled;
```

After `jmsTemplate.send(...)` and before method return, throw `RuntimeException` if flag is
true. This positions the fault inside the XA transaction boundary, after the JMS enlistment,
triggering a two-phase rollback of both MySQL and Artemis.

**Profile activation**: `application-fault.yml` sets `xa-poc.fault-injection.enabled: true`.
Tests activate it with `@ActiveProfiles("fault")`.

---

## Decision 5: Performance Measurement (Micrometer)

**Decision**: Micrometer `Timer` with `publishPercentiles(0.50, 0.95, 0.99)` + custom
throughput calculation; results serialized to JSON via Jackson.

**Rationale**: Micrometer is already on the Spring Boot classpath. `Timer.builder(...).publishPercentiles(...)` uses HDR Histogram for in-process percentile computation — accurate for a single-JVM POC. The `Timer` records both `count` and `totalTime`, from which throughput is computable as `count / elapsedSeconds`.

**Prometheus endpoint**: Add `micrometer-registry-prometheus` for optional Prometheus scraping
during manual benchmark runs. Results file export (Jackson → JSON) is the primary output.

**Configuration in `application-benchmark.yml`**:
```yaml
management:
  metrics:
    distribution:
      percentiles:
        xa.transaction.duration: 0.5, 0.95, 0.99
      percentiles-histogram:
        xa.transaction.duration: true
      minimum-expected-value:
        xa.transaction.duration: 1ms
      maximum-expected-value:
        xa.transaction.duration: 10s
  endpoints:
    web:
      exposure:
        include: prometheus, metrics
```

**Maven coordinates** (managed by Spring Boot BOM):
```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

---

## Decision 6: Testcontainers Integration

**Decision**: `MySQLContainer` + `ArtemisContainer` with `@DynamicPropertySource` (not
`@ServiceConnection`) to override XA datasource properties dynamically.

**Rationale**: `@ServiceConnection` auto-wires standard JDBC/JMS properties, but since the XA
DataSource and ConnectionFactory are configured manually from `application.yml` properties,
`@DynamicPropertySource` is required to feed container-assigned ports back into the manually
declared beans.

**Pattern**:
```java
@Container
static MySQLContainer<?> mysql =
    new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("xapoc")
        .withUsername("xa")
        .withPassword("xa");

@Container
static ArtemisContainer artemis =
    new ArtemisContainer("apache/activemq-artemis:latest-alpine")
        .withUser("admin").withPassword("admin");

@DynamicPropertySource
static void overrideProps(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",
        () -> mysql.getJdbcUrl() + "?allowXAStatements=true&pinGlobalTxToPhysicalConnection=true");
    r.add("spring.datasource.username", mysql::getUsername);
    r.add("spring.datasource.password", mysql::getPassword);
    r.add("spring.artemis.broker-url",
        () -> "tcp://" + artemis.getHost() + ":" + artemis.getMappedPort(61616));
}
```

**Maven coordinates** (versions managed by Spring Boot BOM):
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mysql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>artemis</artifactId>
    <scope>test</scope>
</dependency>
```

**Atomikos log dir in tests**: Set `spring.jta.atomikos.properties.log-base-dir` to a
`@TempDir` path via `@DynamicPropertySource` to prevent test runs from sharing or corrupting
the Atomikos transaction log. Without this, parallel test JVMs conflict.

---

## Cross-Cutting Gotchas

1. **Jakarta namespaces only** — All imports must be `jakarta.*`. `artemis-jms-client` uses
   `javax.*` and silently fails at runtime with Spring Boot 3.
2. **Atomikos version not in Spring BOM** — Pin `transactions-spring-boot3-starter` explicitly;
   do not rely on BOM management for Atomikos.
3. **MySQL XA flags mandatory** — `allowXAStatements=true` and `pinGlobalTxToPhysicalConnection=true`
   must be in the JDBC URL or XA datasource properties; without them, XA commands fail or behave
   incorrectly.
4. **No HikariCP for XA** — The `AtomikosDataSourceBean` manages its own pool. Wrapping in
   HikariCP breaks XA transaction enrollment.
5. **Atomikos log directory must be per-test-JVM** — Use `@TempDir` + `@DynamicPropertySource`.
6. **Spring Boot ArtemisAutoConfiguration conflict** — Declaring your own `ConnectionFactory`
   bean causes the auto-configuration to back off. Set `spring.artemis.mode=native` to avoid
   ambiguity.
