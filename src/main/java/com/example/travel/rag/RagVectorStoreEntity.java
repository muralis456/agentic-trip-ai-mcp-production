package com.example.travel.rag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * Minimal JPA mapping for Spring AI's pgvector table.
 *
 * <p>The application uses Spring AI's VectorStore for vector operations and
 * this entity only provides a managed JPA domain type for the keyword-search
 * repository. The embedding column is intentionally not mapped because this
 * repository never reads or writes it.</p>
 */
@Entity
@Table(name = "vector_store")
public class RagVectorStoreEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "content", nullable = false)
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private String metadata;

    protected RagVectorStoreEntity() {
    }

    public UUID getId() {
        return id;
    }

    public String getContent() {
        return content;
    }

    public String getMetadata() {
        return metadata;
    }
}
