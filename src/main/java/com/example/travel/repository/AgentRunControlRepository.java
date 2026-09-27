package com.example.travel.repository;

import com.example.travel.entity.AgentRunControl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentRunControlRepository extends JpaRepository<AgentRunControl, Long> {
    Optional<AgentRunControl> findByThreadId(String threadId);
    List<AgentRunControl> findByUserIdAndConversationIdAndStatusOrderByUpdatedAtDesc(
            String userId, String conversationId, String status, Pageable pageable);

    List<AgentRunControl> findByUserIdAndStatusOrderByUpdatedAtDesc(
            String userId, String status, Pageable pageable);
}
