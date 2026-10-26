package org.andre.construx.modules.identity.repository;

import org.andre.construx.modules.identity.model.AuthSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.util.List;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {
    @Override
    @EntityGraph(attributePaths = "user")
    Optional<AuthSession> findById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "user")
    Optional<AuthSession> findWithLockById(UUID id);
    List<AuthSession> findByUserId(UUID userId);

    @Modifying
    @Query("delete from AuthSession s where s.expiresAt < :cutoff or s.revokedAt < :cutoff")
    int deleteDeadBefore(@Param("cutoff") Instant cutoff);
}
