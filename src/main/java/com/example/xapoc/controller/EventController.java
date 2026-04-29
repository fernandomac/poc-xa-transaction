package com.example.xapoc.controller;

import com.example.xapoc.domain.SampleEvent;
import com.example.xapoc.producer.EventProducerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventProducerService eventProducerService;

    public EventController(EventProducerService eventProducerService) {
        this.eventProducerService = eventProducerService;
    }

    @PostMapping
    public ResponseEntity<EventResponse> produce(@RequestBody EventRequest request) {
        SampleEvent event = eventProducerService.produceEvent(request.payload());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new EventResponse(event.getId().toString(), event.getPayload()));
    }

    record EventRequest(String payload) {}

    record EventResponse(String id, String payload) {}
}
