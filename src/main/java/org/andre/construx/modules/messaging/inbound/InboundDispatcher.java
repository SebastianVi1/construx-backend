package org.andre.construx.modules.messaging.inbound;

import org.andre.construx.modules.messaging.conversation.ConversationRouter;
import org.andre.construx.modules.messaging.repository.ProcessedMessageRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Takes the messages of an accepted webhook post: skips ids already handled, drops senders over the rate limit, and
 * hands the rest to the {@link ConversationRouter}.
 */
@Component
public class InboundDispatcher {
    private final ConversationRouter router;
    private final ProcessedMessageRepository processed;
    private final SenderRateLimiter rateLimiter;
    private final InboundQueue queue;
    private final Clock clock;

    InboundDispatcher(ConversationRouter router, ProcessedMessageRepository processed, SenderRateLimiter rateLimiter,
            InboundQueue queue, Clock clock) {
        this.router = router;
        this.queue = queue;
        this.processed = processed;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    /** Returns at once; each message is processed later on the {@link InboundQueue}, in order per sender. */
    public void accept(List<InboundMessage> messages) {
        for (InboundMessage message : messages) {
            queue.submit(message.from(), () -> process(message));
        }
    }

    /** A retried id is skipped before it counts against the sender's rate limit. */
    private void process(InboundMessage message) {
        if (processed.record(message.id(), message.from(), clock.instant()) == 0) {
            return;
        }
        if (!rateLimiter.allow(message.from())) {
            return;
        }
        router.route(message);
    }
}
