package com.example.xapoc.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sample_event")
public class SampleEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 500)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public Instant getCreatedAt() { return createdAt; }
}
