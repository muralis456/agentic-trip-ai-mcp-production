package com.example.travel.repository;

import com.example.travel.entity.ConversationMemory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationMemoryRepository extends JpaRepository<ConversationMemory, Long> {
    List<ConversationMemory> findByUserIdAndSessionIdOrderByCreatedAtDesc(String userId, String sessionId, Pageable pageable);
    java.util.Optional<ConversationMemory> findFirstByUserIdAndSessionIdOrderByCreatedAtDesc(String userId, String sessionId);
    List<ConversationMemory> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
    List<ConversationMemory> findByUserIdAndConversationIdOrderByCreatedAtDesc(String userId, String conversationId, Pageable pageable);
    long deleteByUserIdAndConversationId(String userId, String conversationId);
    long countByUserId(String userId);
    long countByUserIdAndConversationId(String userId, String conversationId);
    List<ConversationMemory> findTop12ByUserIdAndConversationIdOrderByIdDesc(String userId, String conversationId);
    List<ConversationMemory> findByUserIdAndConversationIdAndIdGreaterThanAndIdLessThanOrderByIdAsc(
            String userId, String conversationId, Long afterId, Long beforeId, Pageable pageable);
    long deleteByUserIdAndSessionId(String userId, String sessionId);
}
