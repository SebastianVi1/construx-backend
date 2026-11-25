package org.andre.construx.modules.messaging.conversation;

import org.andre.construx.modules.messaging.model.Language;

import java.util.UUID;

/** A sender id proven to belong to an enabled account, and the language the bot speaks to it. */
public record LinkedNumber(String waId, UUID userId, Language language) {
}
