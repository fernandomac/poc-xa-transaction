package com.example.xapoc.integration;

import com.example.xapoc.AbstractIntegrationTest;
import com.example.xapoc.consumer.EventConsumerService;
import com.example.xapoc.domain.SampleEvent;
import com.example.xapoc.producer.EventProducerService;
import com.example.xapoc.repository.SampleEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * US1 — Happy-Path: Committed Event Reaches Consumer.
 *
 * Verifies that when the XA transaction commits, exactly one DB record is created
 * and the consumer receives exactly one message containing the expected payload.
 */
class HappyPathIT extends AbstractIntegrationTest {

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
    void committedEventPersistsAndReachesConsumer() {
        SampleEvent event = eventProducerService.produceEvent("hello-world");

        await().atMost(5, SECONDS)
                .until(() -> eventConsumerService.getReceivedMessages().size() == 1);

        assertThat(eventConsumerService.getReceivedMessages()).hasSize(1);
        assertThat(eventConsumerService.getReceivedMessages().get(0)).contains("hello-world");
        assertThat(eventConsumerService.getReceivedMessages().get(0))
                .contains(event.getId().toString());
        assertThat(sampleEventRepository.count()).isEqualTo(1);
    }

    @Test
    void multipleEventsAllReachConsumer() {
        eventProducerService.produceEvent("event-one");
        eventProducerService.produceEvent("event-two");
        eventProducerService.produceEvent("event-three");

        await().atMost(5, SECONDS)
                .until(() -> eventConsumerService.getReceivedMessages().size() == 3);

        assertThat(eventConsumerService.getReceivedMessages()).hasSize(3);
        assertThat(sampleEventRepository.count()).isEqualTo(3);
    }
}
