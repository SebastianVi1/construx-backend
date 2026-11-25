package org.andre.construx.modules.messaging.webhook;

import org.andre.construx.modules.messaging.inbound.InboundMessage;
import org.andre.construx.modules.messaging.inbound.InboundMessage.Kind;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the inbound messages out of a Meta webhook post ({@code entry[].changes[].value.messages[]}). Status callbacks
 * ({@code value.statuses}: sent, delivered, read) carry no message and are skipped, as is anything unreadable.
 */
final class MetaWebhookPayload {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MetaWebhookPayload() {
    }

    static List<InboundMessage> messages(byte[] body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JacksonException e) {
            return List.of();
        }
        List<InboundMessage> found = new ArrayList<>();
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                for (JsonNode message : change.path("value").path("messages")) {
                    InboundMessage parsed = message(message);
                    if (parsed != null) {
                        found.add(parsed);
                    }
                }
            }
        }
        return found;
    }

    private static InboundMessage message(JsonNode node) {
        String id = text(node, "id");
        String from = text(node, "from");
        if (id == null || from == null || !from.matches("\\d{1,20}")) {
            return null;
        }
        Instant sentAt = parseEpochSeconds(text(node, "timestamp"));
        return switch (node.path("type").asString("")) {
            case "text" -> new InboundMessage(id, from, sentAt, Kind.TEXT, text(node.path("text"), "body"), null,
                    null, null);
            case "interactive" -> reply(id, from, sentAt, node.path("interactive"));
            case "image" -> new InboundMessage(id, from, sentAt, Kind.IMAGE, null, null,
                    text(node.path("image"), "id"), text(node.path("image"), "mime_type"));
            default -> other(id, from, sentAt);
        };
    }

    private static InboundMessage reply(String id, String from, Instant sentAt, JsonNode interactive) {
        String replyId = switch (interactive.path("type").asString("")) {
            case "button_reply" -> text(interactive.path("button_reply"), "id");
            case "list_reply" -> text(interactive.path("list_reply"), "id");
            default -> null;
        };
        return replyId == null ? other(id, from, sentAt)
                : new InboundMessage(id, from, sentAt, Kind.REPLY, null, replyId, null, null);
    }

    private static InboundMessage other(String id, String from, Instant sentAt) {
        return new InboundMessage(id, from, sentAt, Kind.OTHER, null, null, null, null);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : null;
    }

    private static Instant parseEpochSeconds(String value) {
        try {
            return value == null ? null : Instant.ofEpochSecond(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
