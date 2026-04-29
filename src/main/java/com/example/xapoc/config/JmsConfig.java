package com.example.xapoc.config;

import com.atomikos.jms.AtomikosConnectionFactoryBean;
import org.apache.activemq.artemis.jms.client.ActiveMQXAConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.transaction.jta.JtaTransactionManager;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.Session;

/**
 * Configures the Apache Artemis XA JMS connection factory wrapped in Atomikos.
 * The JmsTemplate and listener container factory are wired to the same XA connection factory
 * so that message sends and receives can participate in the JTA transaction.
 */
@Configuration
@EnableJms
public class JmsConfig {

    @Value("${spring.artemis.broker-url}")
    private String brokerUrl;

    @Value("${spring.artemis.user:admin}")
    private String user;

    @Value("${spring.artemis.password:admin}")
    private String password;

    @Value("${spring.jta.atomikos.connectionfactory.unique-resource-name:artemisXaCf}")
    private String uniqueResourceName;

    @Value("${spring.jta.atomikos.connectionfactory.max-pool-size:10}")
    private int maxPoolSize;

    @Value("${spring.jta.atomikos.connectionfactory.borrow-connection-timeout:30}")
    private int borrowConnectionTimeout;

    @Bean
    public ConnectionFactory connectionFactory() {
        ActiveMQXAConnectionFactory artemisXACf =
                new ActiveMQXAConnectionFactory(brokerUrl, user, password);

        AtomikosConnectionFactoryBean cf = new AtomikosConnectionFactoryBean();
        cf.setUniqueResourceName(uniqueResourceName);
        cf.setXaConnectionFactory(artemisXACf);
        cf.setMaxPoolSize(maxPoolSize);
        cf.setMinPoolSize(0);
        cf.setBorrowConnectionTimeout(borrowConnectionTimeout);
        cf.setLocalTransactionMode(false);
        return cf;
    }

    @Bean
    public JmsTemplate jmsTemplate(ConnectionFactory connectionFactory) {
        JmsTemplate template = new JmsTemplate(connectionFactory);
        template.setSessionTransacted(true);
        return template;
    }

    @Bean
    public DefaultJmsListenerContainerFactory jmsListenerContainerFactory(
            ConnectionFactory connectionFactory,
            JtaTransactionManager transactionManager) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setTransactionManager(transactionManager);
        factory.setSessionTransacted(true);
        factory.setSessionAcknowledgeMode(Session.SESSION_TRANSACTED);
        return factory;
    }
}
