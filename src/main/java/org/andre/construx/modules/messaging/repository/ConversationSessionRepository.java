package org.andre.construx.modules.messaging.repository;

import org.andre.construx.modules.messaging.model.ConversationSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ConversationSessionRepository extends JpaRepository<ConversationSession, String> {
    /** Conversations whose last message came at or before {@code cutoff}. */
    List<ConversationSession> findByUpdatedAtLessThanEqual(Instant cutoff);
}
