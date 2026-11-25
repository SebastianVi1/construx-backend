package org.andre.construx.modules.messaging;

import org.andre.construx.modules.messaging.gateway.WhatsAppGateway;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Records every message the bot sends instead of calling Meta, and serves media a test put in with
 * {@link #addMedia}. Tests read what one number received with {@link #sentTo}.
 */
public class InMemoryWhatsAppGateway implements WhatsAppGateway {

    /** One outgoing message: plain text, a list ({@code rows}) or buttons ({@code buttons}). */
    public record Sent(String to, String body, List<ListRow> rows, List<Button> buttons) {}

    private final List<Sent> sent = new ArrayList<>();
    private final Map<String, Media> media = new HashMap<>();

    @Override
    public synchronized void sendText(String to, String body) {
        sent.add(new Sent(to, body, List.of(), List.of()));
    }

    @Override
    public synchronized void sendList(String to, String body, String buttonLabel, List<ListRow> rows) {
        sent.add(new Sent(to, body, List.copyOf(rows), List.of()));
    }

    @Override
    public synchronized void sendButtons(String to, String body, List<Button> buttons) {
        sent.add(new Sent(to, body, List.of(), List.copyOf(buttons)));
    }

    @Override
    public synchronized Media downloadMedia(String mediaId) {
        Media found = media.get(mediaId);
        if (found == null) {
            throw new IllegalArgumentException("No media " + mediaId);
        }
        return found;
    }

    public synchronized void addMedia(String mediaId, byte[] bytes, String contentType) {
        media.put(mediaId, new Media(bytes.clone(), contentType));
    }

    public synchronized List<Sent> sentTo(String waId) {
        return sent.stream().filter(s -> s.to().equals(waId)).toList();
    }

    public synchronized List<Sent> all() {
        return List.copyOf(sent);
    }

    public synchronized void clear() {
        sent.clear();
        media.clear();
    }
}
