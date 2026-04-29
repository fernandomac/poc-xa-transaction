package com.example.xapoc.repository;

import com.example.xapoc.domain.SampleEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SampleEventRepository extends JpaRepository<SampleEvent, UUID> {
}
