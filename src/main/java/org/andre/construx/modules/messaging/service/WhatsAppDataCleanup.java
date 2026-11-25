package org.andre.construx.modules.messaging.service;

import org.andre.construx.modules.messaging.model.ProcessedMessage;
import org.andre.construx.modules.messaging.model.UnlinkedReply;
import org.andre.construx.modules.messaging.model.WrongCodes;
import org.andre.construx.modules.messaging.repository.ProcessedMessageRepository;
import org.andre.construx.modules.messaging.repository.UnlinkedReplyRepository;
import org.andre.construx.modules.messaging.repository.WrongCodesRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Removes WhatsApp data that no longer matters, every 5 minutes (contract §2 cleanup, D7, D12): conversations idle 30
 * minutes or more with their pending photos (rows and stored objects), expired link codes (an expired code already
 * fails; this only clears the row), and the webhook bookkeeping, none of which holds message content and all of which
 * goes well within the 30 days the contract allows: handled message ids after 7 days, NOT_LINKED marks after 24 hours
 * and wrong-code counts once their hour is over. Only saved data and audit events stay.
 */
@Component
public class WhatsAppDataCleanup {
    private static final Logger log = LoggerFactory.getLogger(WhatsAppDataCleanup.class);

    private final ProcessedMessageRepository processed;
    private final UnlinkedReplyRepository unlinkedReplies;
    private final WrongCodesRepository wrongCodes;
    private final WhatsAppLinkService links;
    private final ConversationSessions sessions;
    private final Clock clock;

    public WhatsAppDataCleanup(ProcessedMessageRepository processed, UnlinkedReplyRepository unlinkedReplies,
            WrongCodesRepository wrongCodes, WhatsAppLinkService links, ConversationSessions sessions, Clock clock) {
        this.processed = processed;
        this.unlinkedReplies = unlinkedReplies;
        this.wrongCodes = wrongCodes;
        this.links = links;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    @Transactional
    public void purge() {
        Instant now = clock.instant();
        int idleSessions = sessions.endIdle();
        int codes = links.deleteExpiredCodes();
        int messageIds = processed.deleteReceivedBefore(now.minus(ProcessedMessage.RETENTION));
        int replies = unlinkedReplies.deleteRepliedBefore(now.minus(UnlinkedReply.QUIET_PERIOD));
        int counts = wrongCodes.deleteExpired(now.minus(WrongCodes.WINDOW), now);
        if (idleSessions + codes + messageIds + replies + counts > 0) {
            log.info("Removed {} idle WhatsApp conversations, {} expired link codes, {} old message ids, {} expired "
                    + "not-linked replies and {} expired wrong-code counts", idleSessions, codes, messageIds, replies,
                    counts);
        }
    }
}
