package org.andre.construx.modules.messaging.repository;

import org.andre.construx.modules.messaging.model.WhatsAppLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WhatsAppLinkRepository extends JpaRepository<WhatsAppLink, UUID> {
    Optional<WhatsAppLink> findByWaId(String waId);

    Optional<WhatsAppLink> findByUserId(UUID userId);
}
