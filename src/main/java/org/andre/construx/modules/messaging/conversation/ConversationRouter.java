package org.andre.construx.modules.messaging.conversation;

import org.andre.construx.modules.messaging.inbound.InboundMessage;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Routes each inbound message (contract §5): a sender without a usable link goes to {@link UnlinkedSenderReply};
 * a Linked number goes to the {@link LinkedSenderHandler}. Both seams are optional beans, so linking and the menu
 * flow plug in without touching the webhook.
 */
@Component
public class ConversationRouter {
    private final Optional<LinkedNumbers> linkedNumbers;
    private final Optional<LinkedSenderHandler> linkedSenders;
    private final UnlinkedSenderReply unlinked;

    public ConversationRouter(Optional<LinkedNumbers> linkedNumbers, Optional<LinkedSenderHandler> linkedSenders,
            UnlinkedSenderReply unlinked) {
        this.linkedNumbers = linkedNumbers;
        this.linkedSenders = linkedSenders;
        this.unlinked = unlinked;
    }

    public void route(InboundMessage message) {
        Optional<LinkedNumber> sender = linkedNumbers.flatMap(numbers -> numbers.find(message.from()));
        if (sender.isEmpty()) {
            unlinked.handle(message);
            return;
        }
        linkedSenders.ifPresent(handler -> handler.handle(sender.get(), message));
    }
}
