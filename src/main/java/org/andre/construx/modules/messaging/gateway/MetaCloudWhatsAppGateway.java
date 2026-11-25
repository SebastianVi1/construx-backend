package org.andre.construx.modules.messaging.gateway;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link WhatsAppGateway} on Meta's WhatsApp Cloud API (Graph API). {@code restClient} already carries the Graph API
 * base URL and the bearer token; failures surface as {@code RestClientException}.
 */
public class MetaCloudWhatsAppGateway implements WhatsAppGateway {
    private final RestClient restClient;
    private final String phoneNumberId;

    public MetaCloudWhatsAppGateway(RestClient restClient, String phoneNumberId) {
        this.restClient = restClient;
        this.phoneNumberId = phoneNumberId;
    }

    @Override
    public void sendText(String to, String body) {
        send(to, "text", Map.of("preview_url", false, "body", body));
    }

    @Override
    public void sendList(String to, String body, String buttonLabel, List<ListRow> rows) {
        List<Map<String, String>> listRows = rows.stream().map(MetaCloudWhatsAppGateway::row).toList();
        send(to, "interactive", Map.of(
                "type", "list",
                "body", Map.of("text", body),
                "action", Map.of("button", buttonLabel, "sections", List.of(Map.of("rows", listRows)))));
    }

    @Override
    public void sendButtons(String to, String body, List<Button> buttons) {
        List<Map<String, Object>> replies = buttons.stream()
                .map(b -> Map.<String, Object>of("type", "reply", "reply", Map.of("id", b.id(), "title", b.title())))
                .toList();
        send(to, "interactive", Map.of(
                "type", "button",
                "body", Map.of("text", body),
                "action", Map.of("buttons", replies)));
    }

    /** Two calls: the media id resolves to a short-lived URL, which is fetched with the same bearer token. */
    @Override
    public Media downloadMedia(String mediaId) {
        MediaInfo info = restClient.get().uri("/{mediaId}", mediaId).retrieve().body(MediaInfo.class);
        if (info == null || info.url() == null) {
            throw new IllegalStateException("Meta returned no URL for media " + mediaId);
        }
        ResponseEntity<byte[]> file = restClient.get().uri(URI.create(info.url())).retrieve().toEntity(byte[].class);
        MediaType type = file.getHeaders().getContentType();
        String contentType = info.mime_type() != null ? info.mime_type() : type == null ? null : type.toString();
        return new Media(file.getBody() == null ? new byte[0] : file.getBody(), contentType);
    }

    private record MediaInfo(String url, String mime_type) {}

    private void send(String to, String type, Map<String, Object> content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("messaging_product", "whatsapp");
        message.put("recipient_type", "individual");
        message.put("to", to);
        message.put("type", type);
        message.put(type, content);
        restClient.post().uri("/{phoneNumberId}/messages", phoneNumberId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(message)
                .retrieve()
                .toBodilessEntity();
    }

    private static Map<String, String> row(ListRow row) {
        Map<String, String> json = new LinkedHashMap<>();
        json.put("id", row.id());
        json.put("title", row.title());
        if (row.description() != null) {
            json.put("description", row.description());
        }
        return json;
    }
}
