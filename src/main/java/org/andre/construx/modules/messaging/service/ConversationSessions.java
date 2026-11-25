package org.andre.construx.modules.messaging.service;

import org.andre.construx.modules.messaging.conversation.LinkedNumber;
import org.andre.construx.modules.messaging.model.ConversationSession;
import org.andre.construx.modules.messaging.repository.ConversationSessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The menu conversation of each Linked number (contract §2 {@code whatsapp_session}). {@link #end} is the one way a
 * conversation stops, whatever the reason (YES, NO, a change found, unlinking, expiry), so whatever hangs off it goes
 * with it: the pending photos, rows and stored objects, unless YES already gave them to a Progress note.
 */
@Component
public class ConversationSessions {
    private final ConversationSessionRepository sessions;
    private final PendingPhotos photos;

    public ConversationSessions(ConversationSessionRepository sessions, PendingPhotos photos) {
        this.sessions = sessions;
        this.photos = photos;
    }

    /** The sender's conversation; one another account left behind on this number is ended instead. */
    @Transactional
    public Optional<ConversationSession> current(LinkedNumber sender) {
        Optional<ConversationSession> session = sessions.findById(sender.waId());
        if (session.isPresent() && !session.get().belongsTo(sender.userId())) {
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
}
