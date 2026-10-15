package org.andre.construx.modules.identity.repository;

import org.andre.construx.modules.identity.model.GoogleIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface GoogleIdentityRepository extends JpaRepository<GoogleIdentity, UUID> {
    Optional<GoogleIdentity> findBySubject(String subject);
}
