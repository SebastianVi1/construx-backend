package org.andre.construx.modules.messaging.conversation;

import org.andre.construx.modules.messaging.gateway.WhatsAppGateway;
import org.andre.construx.modules.messaging.inbound.InboundMessage;
import org.andre.construx.modules.messaging.service.WhatsAppLinkService;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Messages from a Linked number (contract §5 rules 3–5). BAJA or STOP, in any case and from any step, unlinks the
 * number and says {@code UNLINKED} in its language; everything else goes to the menu session.
 */
@Component
public class LinkedConversation implements LinkedSenderHandler {
    private static final Set<String> STOP_WORDS = Set.of("BAJA", "STOP");

    private final WhatsAppGateway gateway;
    private final WhatsAppLinkService links;
    private final FieldUpdateMenu menu;

    public LinkedConversation(WhatsAppGateway gateway, WhatsAppLinkService links, FieldUpdateMenu menu) {
        this.gateway = gateway;
        this.links = links;
        this.menu = menu;
    }

    @Override
    public void handle(LinkedNumber sender, InboundMessage message) {
        if (isStop(message)) {
            // Unlinking also ends the menu session.
            links.unlinkNumber(sender.waId())
                    .ifPresent(language -> gateway.sendText(sender.waId(), BotText.UNLINKED.in(language)));
            return;
        }
        continueSession(sender, message);
    }

    @Override
    public boolean awaitsText(LinkedNumber sender) {
        return menu.awaitsText(sender);
    }

    /** The Site → Task → action menu session (contract §5 rules 4–5 and steps A–G). */
    void continueSession(LinkedNumber sender, InboundMessage message) {
        menu.handle(sender, message);
    }

    private static boolean isStop(InboundMessage message) {
        return message.kind() == InboundMessage.Kind.TEXT && message.text() != null
                && STOP_WORDS.contains(message.text().strip().toUpperCase(Locale.ROOT));
    }
}
