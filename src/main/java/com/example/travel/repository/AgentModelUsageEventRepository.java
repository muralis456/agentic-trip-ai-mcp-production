package com.example.travel.repository;

import com.example.travel.entity.AgentModelUsageEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AgentModelUsageEventRepository extends JpaRepository<AgentModelUsageEvent, Long> {
    List<AgentModelUsageEvent> findByUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
            String userId, Instant since, Pageable pageable);
    long countByUserIdAndCreatedAtAfter(String userId, Instant since);
}