package com.example.xapoc.integration;

import com.example.xapoc.AbstractIntegrationTest;
import com.example.xapoc.consumer.EventConsumerService;
import com.example.xapoc.producer.EventProducerService;
import com.example.xapoc.repository.SampleEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * US2 — Failure Scenario: Rolled-Back Producer Delivers Nothing.
 *
 * Activates the "fault" profile to enable xa-poc.fault-injection.enabled=true.
 * Verifies that when the XA transaction rolls back (exception thrown after JMS send,
 * before DB commit), the consumer receives zero messages and the database is empty.
 */
@ActiveProfiles("fault")
class FaultInjectionIT extends AbstractIntegrationTest {

    @Autowired
    private EventProducerService eventProducerService;

    @Autowired
    private EventConsumerService eventConsumerService;

    @Autowired
    private SampleEventRepository sampleEventRepository;

    @BeforeEach
    void setUp() {
        eventConsumerService.clearMessages();
        sampleEventRepository.deleteAll();
    }

    @Test
    void rolledBackProducerLeavesConsumerEmpty() {
        assertThatThrownBy(() -> eventProducerService.produceEvent("should-not-arrive"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated fault");

        // Hold for 5 seconds to confirm no late delivery
        await().during(5, SECONDS)
                .atMost(6, SECONDS)
                .until(() -> eventConsumerService.getReceivedMessages().isEmpty());

        assertThat(eventConsumerService.getReceivedMessages())
                .as("Consumer must receive nothing when XA transaction rolls back")
                .isEmpty();
        assertThat(sampleEventRepository.count())
                .as("Database must have no record when XA transaction rolls back")
                .isEqualTo(0);
    }

    @Test
    void multipleRolledBackProducersLeaveConsumerEmpty() {
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> eventProducerService.produceEvent("payload-" + System.nanoTime()))
                    .isInstanceOf(RuntimeException.class);
        }

        await().during(5, SECONDS)
                .atMost(6, SECONDS)
                .until(() -> eventConsumerService.getReceivedMessages().isEmpty());

        assertThat(eventConsumerService.getReceivedMessages()).isEmpty();
        assertThat(sampleEventRepository.count()).isEqualTo(0);
    }
}
