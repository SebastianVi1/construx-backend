package org.andre.construx.modules.messaging.conversation;

import org.andre.construx.modules.messaging.gateway.WhatsAppGateway;
import org.andre.construx.modules.messaging.inbound.InboundMessage;
import org.andre.construx.modules.messaging.model.UnlinkedReply;
import org.andre.construx.modules.messaging.repository.UnlinkedReplyRepository;
import org.andre.construx.modules.messaging.service.WhatsAppLinkService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Contract §5 rule 1, for a sender without a usable link: a code links the number ({@code LINKED} in the code's
 * language, or {@code CODE_INVALID}, counted by {@link WrongCodeLimit}); anything else is told how to link, at most
 * once per 24 hours, and is not processed. A Linked number's code goes to {@link #linkIfValid} instead.
 */
@Component
public class UnlinkedSenderReply {
    private final WhatsAppGateway gateway;
    private final UnlinkedReplyRepository replies;
    private final WhatsAppLinkService links;
    private final WrongCodeLimit wrongCodes;
    private final Clock clock;

    public UnlinkedSenderReply(WhatsAppGateway gateway, UnlinkedReplyRepository replies, WhatsAppLinkService links,
            WrongCodeLimit wrongCodes, Clock clock) {
        this.gateway = gateway;
        this.replies = replies;
        this.links = links;
        this.wrongCodes = wrongCodes;
        this.clock = clock;
    }

    @Transactional
    public void handle(InboundMessage message) {
        if (message.kind() == InboundMessage.Kind.TEXT && WhatsAppLinkService.looksLikeCode(message.text())) {
            link(message);
            return;
        }
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

    /**
     * For a code from a Linked number: a valid code links the number as usual and answers {@code LINKED}, so a number
     * that changed hands still moves; a wrong one is counted by {@link WrongCodeLimit} but not answered, and false
     * lets the caller handle the text like any other (in a note it is the note, elsewhere the menu's answer).
     */
    @Transactional
    public boolean linkIfValid(InboundMessage message) {
        if (tryLink(message)) {
            return true;
        }
        wrongCodes.countWrongCode(message.from());
        return false;
    }

    private void link(InboundMessage message) {
        if (!tryLink(message)) {
            wrongCodes.countWrongCode(message.from());
            gateway.sendText(message.from(), BotText.CODE_INVALID.bilingual());
        }
    }

    /** A linked number that is unlinked later hears NOT_LINKED again at once, so its quiet period is cleared. */
    private boolean tryLink(InboundMessage message) {
        Optional<WhatsAppLinkService.Linked> linked = links.linkByCode(message.from(), message.text());
        if (linked.isEmpty()) {
            return false;
        }
        replies.findById(message.from()).ifPresent(replies::delete);
        gateway.sendText(message.from(),
                BotText.LINKED.in(linked.get().language(), Map.of("name", linked.get().name())));
        return true;
    }
}
