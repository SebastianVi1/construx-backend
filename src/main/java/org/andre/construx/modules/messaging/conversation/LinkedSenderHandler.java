package org.andre.construx.modules.messaging.conversation;

import org.andre.construx.modules.messaging.inbound.InboundMessage;

/** Handles a message from a Linked number (contract §5 rules 3–5: BAJA/STOP, then the menu session). */
public interface LinkedSenderHandler {
    void handle(LinkedNumber sender, InboundMessage message);
}
