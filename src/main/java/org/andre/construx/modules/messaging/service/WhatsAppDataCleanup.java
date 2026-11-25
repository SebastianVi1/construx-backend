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
 * Daily removal of WhatsApp data that no longer matters: old message ids, expired NOT_LINKED marks, expired link
 * codes (an expired code already fails; this only clears the row) and wrong-code counts that no longer count.
 */
@Component
public class WhatsAppDataCleanup {
    private static final Logger log = LoggerFactory.getLogger(WhatsAppDataCleanup.class);

    private final ProcessedMessageRepository processed;
    private final UnlinkedReplyRepository unlinkedReplies;
    private final WrongCodesRepository wrongCodes;
    private final WhatsAppLinkService links;
    private final Clock clock;

    public WhatsAppDataCleanup(ProcessedMessageRepository processed, UnlinkedReplyRepository unlinkedReplies,
            WrongCodesRepository wrongCodes, WhatsAppLinkService links, Clock clock) {
        this.processed = processed;
        this.unlinkedReplies = unlinkedReplies;
        this.wrongCodes = wrongCodes;
        this.links = links;
        this.clock = clock;
    }

    @Scheduled(cron = "0 45 3 * * *")
    @Transactional
    public void purge() {
        Instant now = clock.instant();
        log.info("Removed {} old WhatsApp message ids, {} expired not-linked replies, {} expired link codes and {} "
                        + "expired wrong-code counts",
                processed.deleteReceivedBefore(now.minus(ProcessedMessage.RETENTION)),
                unlinkedReplies.deleteRepliedBefore(now.minus(UnlinkedReply.QUIET_PERIOD)),
                links.deleteExpiredCodes(),
                wrongCodes.deleteExpired(now.minus(WrongCodes.WINDOW), now));
    }
}
