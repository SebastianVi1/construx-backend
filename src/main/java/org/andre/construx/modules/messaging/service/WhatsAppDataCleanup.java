package org.andre.construx.modules.messaging.service;

import org.andre.construx.modules.messaging.model.ProcessedMessage;
import org.andre.construx.modules.messaging.model.UnlinkedReply;
import org.andre.construx.modules.messaging.repository.ProcessedMessageRepository;
import org.andre.construx.modules.messaging.repository.UnlinkedReplyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/** Daily removal of webhook bookkeeping that no longer matters: old message ids and expired NOT_LINKED marks. */
@Component
public class WhatsAppDataCleanup {
    private static final Logger log = LoggerFactory.getLogger(WhatsAppDataCleanup.class);

    private final ProcessedMessageRepository processed;
    private final UnlinkedReplyRepository unlinkedReplies;
    private final Clock clock;

    public WhatsAppDataCleanup(ProcessedMessageRepository processed, UnlinkedReplyRepository unlinkedReplies,
            Clock clock) {
        this.processed = processed;
        this.unlinkedReplies = unlinkedReplies;
        this.clock = clock;
    }

    @Scheduled(cron = "0 45 3 * * *")
    @Transactional
    public void purge() {
        Instant now = clock.instant();
        log.info("Removed {} old WhatsApp message ids and {} expired not-linked replies",
                processed.deleteReceivedBefore(now.minus(ProcessedMessage.RETENTION)),
                unlinkedReplies.deleteRepliedBefore(now.minus(UnlinkedReply.QUIET_PERIOD)));
    }
}
