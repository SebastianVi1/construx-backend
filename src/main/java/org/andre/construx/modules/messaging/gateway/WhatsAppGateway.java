package org.andre.construx.modules.messaging.gateway;

import java.util.List;

/**
 * Talks to WhatsApp on behalf of the one Construx number: free-form replies inside the 24-hour service window, and
 * media the sender attached. {@code to} is the sender id Meta gave ({@code wa_id}, digits only).
 */
public interface WhatsAppGateway {

    /** A row of an interactive list. {@code id} comes back as the list reply id; the title is at most 24 characters. */
    record ListRow(String id, String title, String description) {}

    /** A reply button. {@code id} comes back as the button reply id; the title is at most 20 characters. */
    record Button(String id, String title) {}

    /** A downloaded attachment. */
    record Media(byte[] bytes, String contentType) {}

    void sendText(String to, String body);

    /** An interactive list; {@code buttonLabel} opens it. At most 10 rows. */
    void sendList(String to, String body, String buttonLabel, List<ListRow> rows);

    /** Reply buttons under {@code body}. At most 3 buttons. */
    void sendButtons(String to, String body, List<Button> buttons);

    Media downloadMedia(String mediaId);
}
