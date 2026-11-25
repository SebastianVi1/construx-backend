package org.andre.construx.modules.messaging.conversation;

import org.andre.construx.modules.messaging.inbound.InboundMessage;

/** Handles a message from a Linked number (contract §5 rules 3–5: BAJA/STOP, then the menu session). */
public interface LinkedSenderHandler {
    void handle(LinkedNumber sender, InboundMessage message);

    /** Whether the sender's next text is free typing (a note), so text shaped like a link code may be meant as it. */
    default boolean awaitsText(LinkedNumber sender) {
        return false;
    }
}
