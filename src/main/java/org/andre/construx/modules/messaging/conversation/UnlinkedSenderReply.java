package org.andre.construx.modules.messaging.conversation;

import org.andre.construx.modules.messaging.gateway.WhatsAppGateway;
import org.andre.construx.modules.messaging.inbound.InboundMessage;
import org.andre.construx.modules.messaging.model.UnlinkedReply;
import org.andre.construx.modules.messaging.repository.UnlinkedReplyRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Contract §5 rule 1: a sender without a link is told how to link, at most once per 24 hours, and the message is not
 * processed. Linking by code (a message matching {@code CX-\d{4}}) belongs in front of this reply.
 */
@Component
public class UnlinkedSenderReply {
    private final WhatsAppGateway gateway;
    private final UnlinkedReplyRepository replies;
    private final Clock clock;

    public UnlinkedSenderReply(WhatsAppGateway gateway, UnlinkedReplyRepository replies, Clock clock) {
        this.gateway = gateway;
        this.replies = replies;
        this.clock = clock;
    }

    @Transactional
    public void handle(InboundMessage message) {
        Instant now = clock.instant();
        UnlinkedReply last = replies.findById(message.from()).orElse(null);
        if (last != null && !last.isDue(now)) {
            return;
        }
        gateway.sendText(message.from(), BotText.NOT_LINKED.bilingual());
        if (last == null) {
            replies.save(new UnlinkedReply(message.from(), now));
        } else {
            last.repliedAt(now);
        }
    }
}
