package com.example.xapoc.controller;

import com.example.xapoc.domain.SampleEvent;
import com.example.xapoc.producer.EventProducerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventProducerService eventProducerService;

    public EventController(EventProducerService eventProducerService) {
        this.eventProducerService = eventProducerService;
    }

    @PostMapping
    public ResponseEntity<?> produce(@RequestBody EventRequest request) {
        if (!eventProducerService.tryAcquire()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("XA transaction capacity exceeded — try again later");
        }
        try {
            SampleEvent event = eventProducerService.produceEvent(request.payload());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new EventResponse(event.getId().toString(), event.getPayload()));
        } finally {
            eventProducerService.release();
        }
    }

    record EventRequest(String payload) {}

    record EventResponse(String id, String payload) {}
}
