package com.example.xapoc.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * JMS consumer for the sample.events queue.
 * Received messages are stored in a thread-safe list for integration test assertions.
 * clearMessages() must be called in @BeforeEach to isolate tests.
 */
@Service
public class EventConsumerService {

    private static final Logger log = LoggerFactory.getLogger(EventConsumerService.class);

    private final List<String> receivedMessages = new CopyOnWriteArrayList<>();

    @JmsListener(destination = "sample.events", containerFactory = "jmsListenerContainerFactory")
    public void onMessage(String message) {
        log.info("Consumer received message: {}", message);
        receivedMessages.add(message);
    }

    public List<String> getReceivedMessages() {
        return receivedMessages;
    }

    public void clearMessages() {
        receivedMessages.clear();
    }
}
