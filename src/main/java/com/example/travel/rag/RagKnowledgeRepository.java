package com.example.travel.rag;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Persistence boundary for maintenance operations on Spring AI's vector_store.
 * PostgreSQL-specific SQL is intentionally isolated here rather than in the RAG service.
 */
@org.springframework.stereotype.Repository
public interface RagKnowledgeRepository extends Repository<Object, String> {

    @Query(value = "select count(*) from vector_store", nativeQuery = true)
    int countDocuments();

    @Modifying
    @Query(value = "truncate table vector_store", nativeQuery = true)
    int truncateVectorStore();

    @Modifying
    @Query(value = "delete from vector_store where metadata ->> 'type' = :type", nativeQuery = true)
    int deleteGeneratedAirportCityKnowledge(@Param("type") String type);
}
