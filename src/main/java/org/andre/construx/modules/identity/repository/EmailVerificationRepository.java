package org.andre.construx.modules.identity.repository;

import org.andre.construx.modules.identity.model.EmailVerification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, UUID> {
    Optional<EmailVerification> findByTokenHash(String tokenHash);
}
