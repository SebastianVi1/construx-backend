package org.andre.construx.modules.messaging.service;

import org.andre.construx.modules.messaging.conversation.LinkedNumber;
import org.andre.construx.modules.messaging.model.ConversationSession;
import org.andre.construx.modules.messaging.repository.ConversationSessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The menu conversation of each Linked number (contract §2 {@code whatsapp_session}). {@link #end} is the one way a
 * conversation stops, whatever the reason (YES, NO, a change found, unlinking, expiry), so whatever hangs off it goes
 * with it: the pending photos, rows and stored objects, unless YES already gave them to a Progress note. A conversation
 * idle for {@link ConversationSession#IDLE_LIMIT} is over: {@link #current} no longer returns it, and the cleanup job
 * {@link #endIdle ends} it.
 */
@Component
public class ConversationSessions {
    private final ConversationSessionRepository sessions;
    private final PendingPhotos photos;
    private final Clock clock;

    public ConversationSessions(ConversationSessionRepository sessions, PendingPhotos photos, Clock clock) {
        this.sessions = sessions;
        this.photos = photos;
        this.clock = clock;
    }

    /**
     * The sender's conversation; one gone idle, or one another account left behind on this number, is ended instead.
     */
    @Transactional
    public Optional<ConversationSession> current(LinkedNumber sender) {
        Optional<ConversationSession> session = sessions.findById(sender.waId());
        if (session.isPresent() && (session.get().isIdle(clock.instant())
                || !session.get().belongsTo(sender.userId()))) {
            end(sender.waId());
            return Optional.empty();
        }
        return session;
    }

    @Transactional
    public ConversationSession save(ConversationSession session) {
        return sessions.save(session);
    }

    /** Ends the number's conversation and deletes its pending photos; nothing happens when there is none. */
    @Transactional
    public void end(String waId) {
        photos.discard(waId);
        sessions.findById(waId).ifPresent(sessions::delete);
    }

    /** Ends every conversation idle for {@link ConversationSession#IDLE_LIMIT} or more; returns how many. */
    @Transactional
    public int endIdle() {
        Instant now = clock.instant();
        List<ConversationSession> idle = sessions.findByUpdatedAtLessThanEqual(now.minus(ConversationSession.IDLE_LIMIT));
        idle.forEach(session -> end(session.getWaId()));
        return idle.size();
    }
}
