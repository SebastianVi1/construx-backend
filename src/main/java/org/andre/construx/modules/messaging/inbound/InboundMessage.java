package org.andre.construx.modules.messaging.inbound;

import java.time.Instant;

/**
 * One message a person sent to the Construx number, as Meta delivered it.
 *
 * @param id       Meta's message id ({@code wamid...}); Meta retries with the same id
 * @param from     sender id ({@code wa_id}: digits only, E.164 without {@code +})
 * @param sentAt   when the person sent it, per Meta
 * @param kind     what it holds
 * @param text     the typed text, for {@link Kind#TEXT}
 * @param replyId  the id of the tapped list row or button, for {@link Kind#REPLY}
 * @param mediaId  Meta's media id, for {@link Kind#IMAGE}
 * @param mimeType the media type Meta declared, for {@link Kind#IMAGE}
 */
public record InboundMessage(String id, String from, Instant sentAt, Kind kind, String text, String replyId,
        String mediaId, String mimeType) {

    /** Audio, stickers, locations, documents and anything else the bot does not take are {@link #OTHER}. */
    public enum Kind { TEXT, REPLY, IMAGE, OTHER }
}
