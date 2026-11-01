package com.example.travel.repository;

import com.example.travel.entity.ConversationSession;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationSessionRepository extends JpaRepository<ConversationSession, Long> {
    Optional<ConversationSession> findByUserIdAndConversationId(String userId, String conversationId);
}
