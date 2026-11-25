package org.andre.construx.modules.messaging.repository;

import org.andre.construx.modules.messaging.model.ConversationSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationSessionRepository extends JpaRepository<ConversationSession, String> {
}
