package com.example.travel.rag;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Persistence boundary for PostgreSQL full-text retrieval from Spring AI's
 * vector_store table. SQL stays in the repository; the RAG service remains
 * focused on retrieval orchestration.
 */
@org.springframework.stereotype.Repository
public interface RagKeywordSearchRepository extends Repository<Object, String> {

    @Query(value = """
            select content as content, metadata::text as metadata
            from vector_store
            where to_tsvector('simple', content) @@ plainto_tsquery('simple', :query)
            and (metadata ->> 'destinationKey' = 'global'
                 or metadata ->> 'destinationKey' in (:destinationKeys))
            order by ts_rank(
                to_tsvector('simple', content),
                plainto_tsquery('simple', :rankQuery)
            ) desc
            limit :limit
            """, nativeQuery = true)
    List<RagKeywordRow> searchForDestinations(
            @Param("query") String query,
            @Param("rankQuery") String rankQuery,
            @Param("limit") int limit,
            @Param("destinationKeys") List<String> destinationKeys);

    @Query(value = """
            select content as content, metadata::text as metadata
            from vector_store
            where to_tsvector('simple', content) @@ plainto_tsquery('simple', :query)
            order by ts_rank(
                to_tsvector('simple', content),
                plainto_tsquery('simple', :rankQuery)
            ) desc
            limit :limit
            """, nativeQuery = true)
    List<RagKeywordRow> searchAll(
            @Param("query") String query,
            @Param("rankQuery") String rankQuery,
            @Param("limit") int limit);

    default List<RagKeywordRow> search(
            String query, String rankQuery, int limit, List<String> destinationKeys) {
        if (destinationKeys == null || destinationKeys.isEmpty()) {
            return searchAll(query, rankQuery, limit);
        }
        return searchForDestinations(query, rankQuery, limit, destinationKeys);
    }

    interface RagKeywordRow {
        String content();
        String metadata();
    }
}
