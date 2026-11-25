package org.andre.construx.modules.messaging.conversation;

import org.andre.construx.modules.messaging.inbound.InboundMessage;
import org.andre.construx.modules.messaging.service.WhatsAppLinkService;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Routes each inbound message (contract §5): a sender without a usable link, and any {@code CX-1234} code (a
 * recycled number may still be linked to its previous owner), goes to {@link UnlinkedSenderReply}; a Linked number
 * goes to the {@link LinkedSenderHandler}. A number blocked for too many wrong codes is ignored before any of that. Both seams are optional beans, so linking and the menu
 * flow plug in without touching the webhook.
 */
@Component
public class ConversationRouter {
    private final Optional<LinkedNumbers> linkedNumbers;
    private final Optional<LinkedSenderHandler> linkedSenders;
    private final UnlinkedSenderReply unlinked;
    private final WrongCodeLimit wrongCodes;

    public ConversationRouter(Optional<LinkedNumbers> linkedNumbers, Optional<LinkedSenderHandler> linkedSenders,
            UnlinkedSenderReply unlinked, WrongCodeLimit wrongCodes) {
        this.linkedNumbers = linkedNumbers;
        this.linkedSenders = linkedSenders;
        this.unlinked = unlinked;
        this.wrongCodes = wrongCodes;
    }

    public void route(InboundMessage message) {
        if (wrongCodes.blocks(message.from())) {
            return;
        }
        if (isLinkCode(message)) {
            // A recycled number is still linked to its previous owner, so a code is always a link attempt.
            unlinked.handle(message);
            return;
        }
        Optional<LinkedNumber> sender = linkedNumbers.flatMap(numbers -> numbers.find(message.from()));
        if (sender.isEmpty()) {
            unlinked.handle(message);
            return;
        }
        linkedSenders.ifPresent(handler -> handler.handle(sender.get(), message));
    }

    private static boolean isLinkCode(InboundMessage message) {
        return message.kind() == InboundMessage.Kind.TEXT && WhatsAppLinkService.looksLikeCode(message.text());
    }
}
