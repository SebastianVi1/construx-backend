package org.andre.construx.modules.identity.repository;

import org.andre.construx.modules.identity.model.AuthSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.EntityGraph;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;
import java.util.List;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {
    @Override
    @EntityGraph(attributePaths = "user")
    Optional<AuthSession> findById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "user")
    Optional<AuthSession> findWithLockById(UUID id);
    List<AuthSession> findByUserId(UUID userId);
}
