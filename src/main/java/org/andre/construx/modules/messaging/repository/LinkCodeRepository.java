package org.andre.construx.modules.messaging.repository;

import org.andre.construx.modules.messaging.model.LinkCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LinkCodeRepository extends JpaRepository<LinkCode, UUID> {
    List<LinkCode> findByCodeHash(String codeHash);

    /** Whether another user holds a code with this hash that still works, so two people never share one. */
    boolean existsByCodeHashAndExpiresAtAfterAndUserIdNot(String codeHash, Instant now, UUID userId);

    @Modifying
    @Query("delete from LinkCode c where c.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);
}
