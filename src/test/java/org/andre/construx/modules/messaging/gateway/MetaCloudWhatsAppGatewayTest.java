package org.andre.construx.modules.messaging.gateway;

import org.andre.construx.modules.messaging.gateway.WhatsAppGateway.Button;
import org.andre.construx.modules.messaging.gateway.WhatsAppGateway.ListRow;
import org.andre.construx.modules.messaging.gateway.WhatsAppGateway.Media;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The Graph API calls the adapter makes, checked against a mock HTTP server instead of Meta. */
class MetaCloudWhatsAppGatewayTest {
    private static final String GRAPH = "https://graph.example.test/v23.0";
    private static final String MESSAGES = GRAPH + "/1000000001/messages";
    private static final String TO = "5215512345678";
    private static final String SENT = "{\"messages\":[{\"id\":\"wamid.out\"}]}";

    private final RestClient.Builder builder = RestClient.builder().baseUrl(GRAPH)
            .defaultHeaders(headers -> headers.setBearerAuth("token-123"));
    private final MockRestServiceServer meta = MockRestServiceServer.bindTo(builder).build();
    private final WhatsAppGateway gateway = new MetaCloudWhatsAppGateway(builder.build(), "1000000001");

    @AfterEach
    void allCallsWereMade() {
        meta.verify();
    }

    @Test
    void sendsATextMessageFromTheConstruxNumber() {
        meta.expect(requestTo(MESSAGES)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer token-123"))
                .andExpect(jsonPath("$.messaging_product").value("whatsapp"))
                .andExpect(jsonPath("$.to").value(TO))
                .andExpect(jsonPath("$.type").value("text"))
                .andExpect(jsonPath("$.text.body").value("Hola"))
                .andRespond(withSuccess(SENT, MediaType.APPLICATION_JSON));

        gateway.sendText(TO, "Hola");
    }

    @Test
    void sendsAnInteractiveList() {
        meta.expect(requestTo(MESSAGES))
                .andExpect(jsonPath("$.type").value("interactive"))
                .andExpect(jsonPath("$.interactive.type").value("list"))
                .andExpect(jsonPath("$.interactive.body.text").value("¿En qué obra?"))
                .andExpect(jsonPath("$.interactive.action.button").value("Obras"))
                .andExpect(jsonPath("$.interactive.action.sections[0].rows[0].id").value("site:1"))
                .andExpect(jsonPath("$.interactive.action.sections[0].rows[0].title").value("Torre A"))
                .andExpect(jsonPath("$.interactive.action.sections[0].rows[0].description").value("Acme"))
                .andExpect(jsonPath("$.interactive.action.sections[0].rows[1].title").value("Torre B"))
                .andExpect(jsonPath("$.interactive.action.sections[0].rows[1].description").doesNotExist())
                .andRespond(withSuccess(SENT, MediaType.APPLICATION_JSON));

        gateway.sendList(TO, "¿En qué obra?", "Obras",
                List.of(new ListRow("site:1", "Torre A", "Acme"), new ListRow("site:2", "Torre B", null)));
    }

    @Test
    void sendsReplyButtons() {
        meta.expect(requestTo(MESSAGES))
                .andExpect(jsonPath("$.interactive.type").value("button"))
                .andExpect(jsonPath("$.interactive.body.text").value("¿Lo guardo?"))
                .andExpect(jsonPath("$.interactive.action.buttons[0].type").value("reply"))
                .andExpect(jsonPath("$.interactive.action.buttons[0].reply.id").value("yes"))
                .andExpect(jsonPath("$.interactive.action.buttons[1].reply.title").value("No"))
                .andRespond(withSuccess(SENT, MediaType.APPLICATION_JSON));

        gateway.sendButtons(TO, "¿Lo guardo?", List.of(new Button("yes", "Sí"), new Button("no", "No")));
    }

    @Test
    void downloadsMediaThroughItsShortLivedUrl() {
        String lookaside = "https://lookaside.example.test/whatsapp_business/v1/?mid=777";
        meta.expect(requestTo(GRAPH + "/777")).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token-123"))
                .andRespond(withSuccess("{\"url\":\"" + lookaside + "\",\"mime_type\":\"image/jpeg\",\"id\":\"777\"}",
                        MediaType.APPLICATION_JSON));
        meta.expect(requestTo(lookaside)).andExpect(header("Authorization", "Bearer token-123"))
                .andRespond(withSuccess(new byte[] {1, 2, 3}, MediaType.APPLICATION_OCTET_STREAM));

        Media media = gateway.downloadMedia("777");

        assertThat(media.bytes()).containsExactly(1, 2, 3);
        assertThat(media.contentType()).isEqualTo("image/jpeg");
    }
}
