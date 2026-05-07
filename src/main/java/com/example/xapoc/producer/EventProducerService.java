package com.example.xapoc.producer;

import com.example.xapoc.config.XaPocProperties;
import com.example.xapoc.domain.SampleEvent;
import com.example.xapoc.repository.SampleEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.Semaphore;

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

    // xa | non-transactional | disabled
    enum JmsMode { xa, non_transactional, disabled }

    private final SampleEventRepository repository;
    private final JmsTemplate xaJmsTemplate;
    private final JmsTemplate nonXaJmsTemplate;
    private final JmsMode jmsMode;
    private final boolean faultInjectionEnabled;
    private final Timer xaTimer;
    private final Semaphore concurrencyLimiter;

    public EventProducerService(SampleEventRepository repository,
                                JmsTemplate xaJmsTemplate,
                                @Qualifier("nonXa") JmsTemplate nonXaJmsTemplate,
                                MeterRegistry meterRegistry,
                                XaPocProperties props) {
        this.repository = repository;
        this.xaJmsTemplate = xaJmsTemplate;
        this.nonXaJmsTemplate = nonXaJmsTemplate;
        this.jmsMode = JmsMode.valueOf(props.getJms().getMode().replace('-', '_'));
        this.faultInjectionEnabled = props.getFaultInjection().isEnabled();
        this.xaTimer = Timer.builder("xa.transaction.duration")
                .description("XA transaction duration (DB write + JMS send + 2PC)")
                .publishPercentileHistogram()
                .register(meterRegistry);
        this.concurrencyLimiter = new Semaphore(props.getMaxConcurrentTransactions());
    }

    @PostConstruct
    void logStartupConfig() {
        log.info("┌─────────────────────────────────────────────────┐");
        log.info("│              XA POC — Active Configuration       │");
        log.info("├─────────────────────────────────────────────────┤");
        log.info("│  JMS mode              : {}", pad(jmsMode.name(), 28) + "│");
        log.info("│  Fault injection       : {}", pad(String.valueOf(faultInjectionEnabled), 28) + "│");
        log.info("│  Max concurrent XA     : {}", pad(String.valueOf(concurrencyLimiter.availablePermits()), 28) + "│");
        log.info("└─────────────────────────────────────────────────┘");
    }

    private static String pad(String value, int width) {
        return String.format("%-" + width + "s", value);
    }

    public boolean tryAcquire() {
        return concurrencyLimiter.tryAcquire();
    }

    public void release() {
        concurrencyLimiter.release();
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
        return xaTimer.record(() -> {
            SampleEvent event = new SampleEvent();
            event.setPayload(payload);
            repository.save(event);
            log.debug("Saved SampleEvent id={} payload={}", event.getId(), payload);

            if (jmsMode == JmsMode.xa) {
                String body = buildBody(event);
                xaJmsTemplate.send(QUEUE, session -> session.createTextMessage(body));
                log.debug("Sent XA JMS message to queue={}", QUEUE);
            }

            if (faultInjectionEnabled) {
                log.warn("Fault injection active — throwing RuntimeException before XA commit");
                throw new RuntimeException("Simulated fault — XA rollback triggered");
            }

            return event;
        });
    }

    // Called after @Transactional commits — non-XA send is outside 2PC scope
    public void sendNonXa(SampleEvent event) {
        if (jmsMode == JmsMode.non_transactional) {
            String body = buildBody(event);
            nonXaJmsTemplate.send(QUEUE, session -> session.createTextMessage(body));
            log.debug("Sent non-XA JMS message to queue={}", QUEUE);
        }
    }

    private String buildBody(SampleEvent event) {
        return String.format("{\"eventId\":\"%s\",\"payload\":\"%s\"}", event.getId(), event.getPayload());
    }
}
