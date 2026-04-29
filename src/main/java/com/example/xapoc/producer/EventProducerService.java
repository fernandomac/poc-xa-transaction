package com.example.xapoc.producer;

import com.example.xapoc.domain.SampleEvent;
import com.example.xapoc.repository.SampleEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * XA-transactional event producer.
 *
 * Both the JPA save and the JMS send happen within the same Atomikos XA transaction.
 * If fault injection is enabled, an exception is thrown AFTER the JMS send but BEFORE
 * the method returns — triggering a two-phase XA rollback that cancels both the DB write
 * and the queued message. The consumer will receive nothing.
 */
@Service
public class EventProducerService {

    private static final Logger log = LoggerFactory.getLogger(EventProducerService.class);
    private static final String QUEUE = "sample.events";

    private final SampleEventRepository repository;
    private final JmsTemplate jmsTemplate;

    @Value("${xa-poc.fault-injection.enabled:false}")
    private boolean faultInjectionEnabled;

    public EventProducerService(SampleEventRepository repository, JmsTemplate jmsTemplate) {
        this.repository = repository;
        this.jmsTemplate = jmsTemplate;
    }

    /**
     * Produces a sample event within a single XA transaction spanning MySQL and Artemis.
     *
     * @param payload arbitrary string payload
     * @return the persisted SampleEvent (only reachable if the transaction commits)
     * @throws RuntimeException when fault injection is enabled — triggers XA rollback
     */
    @Transactional
    public SampleEvent produceEvent(String payload) {
        SampleEvent event = new SampleEvent();
        event.setPayload(payload);
        repository.save(event);
        log.info("Saved SampleEvent id={} payload={}", event.getId(), payload);

        String body = String.format("{\"eventId\":\"%s\",\"payload\":\"%s\"}",
                event.getId(), payload);
        jmsTemplate.send(QUEUE, session -> session.createTextMessage(body));
        log.info("Sent JMS message to queue={} body={}", QUEUE, body);

        if (faultInjectionEnabled) {
            log.warn("Fault injection active — throwing RuntimeException before XA commit");
            throw new RuntimeException("Simulated fault — XA rollback triggered");
        }

        return event;
    }
}
