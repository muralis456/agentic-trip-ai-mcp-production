package com.example.travel.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface SpringSessionRepository extends Repository<Object, String> {

    @Modifying
    @Query(value = """
            DELETE FROM SPRING_SESSION_ATTRIBUTES
            WHERE SESSION_PRIMARY_ID IN (
                SELECT PRIMARY_ID
                FROM SPRING_SESSION
                WHERE PRINCIPAL_NAME = :username
            )
            """, nativeQuery = true)
    int deleteAttributesByPrincipal(@Param("username") String username);

    @Modifying
    @Query(value = """
            DELETE FROM SPRING_SESSION
            WHERE PRINCIPAL_NAME = :username
            """, nativeQuery = true)
    int deleteSessionsByPrincipal(@Param("username") String username);
}
