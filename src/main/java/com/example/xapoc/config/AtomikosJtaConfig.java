package com.example.xapoc.config;

import com.atomikos.icatch.jta.UserTransactionImp;
import com.atomikos.icatch.jta.UserTransactionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.SystemException;
import jakarta.transaction.UserTransaction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.jta.JtaTransactionManager;

/**
 * Manual Atomikos JTA configuration for Spring Boot 3.5.x.
 *
 * AtomikosAutoConfiguration from transactions-spring-boot3-starter:6.0.0 is incompatible
 * with Spring Boot 3.3+ (TransactionManagerCustomizers.customize API changed from
 * PlatformTransactionManager to TransactionManager). This class provides equivalent beans
 * without depending on the broken auto-configuration.
 *
 * Atomikos properties (log dir, TM unique name, timeouts) are read from
 * jta.properties on the classpath — see src/main/resources/jta.properties.
 */
@Configuration
public class AtomikosJtaConfig {

    @Bean(initMethod = "init", destroyMethod = "close")
    public UserTransactionManager atomikosTransactionManager() {
        UserTransactionManager manager = new UserTransactionManager();
        manager.setForceShutdown(true);
        return manager;
    }

    @Bean
    public UserTransaction atomikosUserTransaction() throws SystemException {
        UserTransactionImp impl = new UserTransactionImp();
        impl.setTransactionTimeout(300);
        return impl;
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @Primary
    public JtaTransactionManager transactionManager(
            UserTransaction atomikosUserTransaction,
            UserTransactionManager atomikosTransactionManager) {
        JtaTransactionManager tm = new JtaTransactionManager();
        tm.setTransactionManager(atomikosTransactionManager);
        tm.setUserTransaction(atomikosUserTransaction);
        return tm;
    }
}
