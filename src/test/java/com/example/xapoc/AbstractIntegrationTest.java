package com.example.xapoc;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for all integration tests.
 * Starts MySQL 8 and Apache Artemis via Testcontainers and feeds their dynamic
 * ports into the Spring context via @DynamicPropertySource.
 *
 * Note: @ServiceConnection is intentionally NOT used here because the XA datasource
 * is configured manually (MysqlXADataSource) and requires the URL to include
 * XA-specific parameters (allowXAStatements=true, pinGlobalTxToPhysicalConnection=true).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("xapoc")
            .withUsername("xa")
            .withPassword("xa");

    @Container
    static final GenericContainer<?> ARTEMIS =
            new GenericContainer<>("apache/activemq-artemis:latest-alpine")
                    .withExposedPorts(61616)
                    .withEnv("ARTEMIS_USER", "admin")
                    .withEnv("ARTEMIS_PASSWORD", "admin")
                    .waitingFor(Wait.forLogMessage(".*AMQ221007.*", 1));

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        // Append XA-required parameters to the Testcontainers-provided JDBC URL
        String xaUrl = MYSQL.getJdbcUrl()
                + "?allowXAStatements=true&pinGlobalTxToPhysicalConnection=true"
                + "&useSSL=false&allowPublicKeyRetrieval=true";
        registry.add("spring.datasource.url", () -> xaUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);

        registry.add("spring.artemis.broker-url",
                () -> "tcp://" + ARTEMIS.getHost() + ":" + ARTEMIS.getMappedPort(61616));
        registry.add("spring.artemis.user", () -> "admin");
        registry.add("spring.artemis.password", () -> "admin");
    }
}
