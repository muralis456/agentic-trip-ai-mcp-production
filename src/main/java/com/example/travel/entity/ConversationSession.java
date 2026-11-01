package com.example.travel.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** Conversation-scoped metadata and compacted prompt context, separate from graph checkpoints. */
@Entity
@Table(name = "conversation_session", uniqueConstraints = @UniqueConstraint(
        name = "uk_conversation_session_user_conversation",
        columnNames = {"user_id", "conversation_id"}))
public class ConversationSession {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false, length = 255) private String userId;
    @Column(name = "conversation_id", nullable = false, length = 120) private String conversationId;
    @Column(name = "last_graph_thread_id", length = 255) private String lastGraphThreadId;
    @Column(name = "last_role", length = 20) private String lastRole;
    @Column(name = "metadata_json", columnDefinition = "TEXT") private String metadataJson;
    @Column(name = "compacted_summary", columnDefinition = "TEXT") private String compactedSummary;
    @Column(name = "compacted_through_message_id", nullable = false) private Long compactedThroughMessageId = 0L;
    @Column(name = "message_count", nullable = false) private Long messageCount = 0L;
    @Column(name = "compaction_version", nullable = false) private Integer compactionVersion = 0;
    @Column(name = "last_activity_at", nullable = false) private Instant lastActivityAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getUserId(){return userId;} public void setUserId(String v){userId=v;}
    public String getConversationId(){return conversationId;} public void setConversationId(String v){conversationId=v;}
    public String getLastGraphThreadId(){return lastGraphThreadId;} public void setLastGraphThreadId(String v){lastGraphThreadId=v;}
    public String getLastRole(){return lastRole;} public void setLastRole(String v){lastRole=v;}
    public String getMetadataJson(){return metadataJson;} public void setMetadataJson(String v){metadataJson=v;}
    public String getCompactedSummary(){return compactedSummary;} public void setCompactedSummary(String v){compactedSummary=v;}
    public Long getCompactedThroughMessageId(){return compactedThroughMessageId;} public void setCompactedThroughMessageId(Long v){compactedThroughMessageId=v;}
    public Long getMessageCount(){return messageCount;} public void setMessageCount(Long v){messageCount=v;}
    public Integer getCompactionVersion(){return compactionVersion;} public void setCompactionVersion(Integer v){compactionVersion=v;}
    public Instant getLastActivityAt(){return lastActivityAt;} public void setLastActivityAt(Instant v){lastActivityAt=v;}
    public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;}
    public Instant getUpdatedAt(){return updatedAt;} public void setUpdatedAt(Instant v){updatedAt=v;}
}
