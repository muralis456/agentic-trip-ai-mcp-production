package com.example.travel.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentIdempotencyRepository extends JpaRepository<AgentIdempotency, AgentIdempotencyId> {

    @Modifying
    @Query(value = """
            insert into agent_idempotency
                (user_id, idempotency_key, request_hash, status, created_at)
            values
                (:userId, :key, :requestHash, 'IN_PROGRESS', :createdAt)
            on conflict (user_id, idempotency_key) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") String userId,
                       @Param("key") String key,
                       @Param("requestHash") String requestHash,
                       @Param("createdAt") java.time.OffsetDateTime createdAt);
}
