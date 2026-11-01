package com.example.travel.repository;

import com.example.travel.entity.ConversationSession;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationSessionRepository extends JpaRepository<ConversationSession, Long> {
    Optional<ConversationSession> findByUserIdAndConversationId(String userId, String conversationId);
    @Modifying
    @Query(value = """
            insert into conversation_session (
                user_id, conversation_id, compacted_through_message_id, message_count,
                compaction_version, last_activity_at, created_at, updated_at
            ) values (
                :userId, :conversationId, 0, 0, 0, current_timestamp, current_timestamp, current_timestamp
            ) on conflict (user_id, conversation_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") String userId, @Param("conversationId") String conversationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from ConversationSession session "
            + "where session.userId = :userId and session.conversationId = :conversationId")
    Optional<ConversationSession> findByUserIdAndConversationIdForUpdate(
            @Param("userId") String userId, @Param("conversationId") String conversationId);

    long deleteByUserIdAndConversationId(String userId, String conversationId);
}
