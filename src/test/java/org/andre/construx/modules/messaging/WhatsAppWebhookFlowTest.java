package org.andre.construx.modules.messaging;

import jakarta.servlet.http.Cookie;
import org.andre.construx.modules.messaging.conversation.Language;
import org.andre.construx.modules.messaging.conversation.LinkedNumber;
import org.andre.construx.modules.messaging.conversation.LinkedNumbers;
import org.andre.construx.modules.messaging.conversation.LinkedSenderHandler;
import org.andre.construx.modules.messaging.inbound.InboundMessage;
import org.andre.construx.modules.messaging.service.WhatsAppDataCleanup;
import org.andre.construx.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Meta webhook end to end: signed, Meta-shaped posts go in, and what the bot sent is read from the
 * {@link InMemoryWhatsAppGateway}. Inbound processing runs synchronously in the test profile.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(WhatsAppWebhookFlowTest.Fakes.class)
class WhatsAppWebhookFlowTest {
    static final String START = "2026-03-10T15:00:00Z";
    static final String VERIFY_TOKEN = "test-verify-token";
    static final String WEBHOOK = "/api/webhooks/whatsapp";
    static final String APP_SECRET = "test-app-secret";
    static final AtomicInteger SENDERS = new AtomicInteger();
    static final String NOT_LINKED = """
            Este número no está vinculado a Construx. Entra a tu perfil, toca "Vincular WhatsApp" y envía aquí el código.

            This number isn't linked to Construx. Open your profile, tap "Link WhatsApp" and send the code here.""";

    @TestConfiguration
    static class Fakes {
        @Bean @Primary InMemoryWhatsAppGateway inMemoryWhatsAppGateway() {
            return new InMemoryWhatsAppGateway();
        }

        @Bean @Primary MutableClock whatsAppClock() {
            return new MutableClock(Instant.parse(START));
        }

        @Bean @Primary FakeLinks fakeLinks() {
            return new FakeLinks();
        }
    }

    /** Stands in for real Linked numbers: the numbers a test links, and every message they reach the linked side with. */
    static class FakeLinks implements LinkedNumbers, LinkedSenderHandler {
        final Map<String, LinkedNumber> links = new ConcurrentHashMap<>();
        final List<InboundMessage> handled = new CopyOnWriteArrayList<>();

        String link(String waId) {
            links.put(waId, new LinkedNumber(waId, UUID.randomUUID(), Language.ES));
            return waId;
        }

        List<String> handledIdsFrom(String waId) {
            return handled.stream().filter(m -> m.from().equals(waId)).map(InboundMessage::id).toList();
        }

        @Override
        public Optional<LinkedNumber> find(String waId) {
            return Optional.ofNullable(links.get(waId));
        }

        @Override
        public void handle(LinkedNumber sender, InboundMessage message) {
            handled.add(message);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired InMemoryWhatsAppGateway gateway;
    @Autowired MutableClock clock;
    @Autowired FakeLinks links;
    @Autowired WhatsAppDataCleanup cleanup;

    @BeforeEach
    void reset() {
        clock.set(START);
        gateway.clear();
    }

    @Test
    void verificationEchoesTheChallengeWhenTheVerifyTokenMatches() throws Exception {
        mvc.perform(get(WEBHOOK).param("hub.mode", "subscribe").param("hub.verify_token", VERIFY_TOKEN)
                        .param("hub.challenge", "1158201444"))
                .andExpect(status().isOk())
                .andExpect(content().string("1158201444"));
    }

    @Test
    void verificationIsForbiddenWithAWrongVerifyToken() throws Exception {
        mvc.perform(get(WEBHOOK).param("hub.mode", "subscribe").param("hub.verify_token", "guess")
                        .param("hub.challenge", "1158201444"))
                .andExpect(status().isForbidden());
        mvc.perform(get(WEBHOOK).param("hub.mode", "subscribe").param("hub.challenge", "1158201444"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aPostWithABadOrMissingSignatureIsRejectedAndNothingIsProcessed() throws Exception {
        String waId = newSender();
        String body = textMessage(waId, "wamid.bad-signature", "hola");

        mvc.perform(post(WEBHOOK).contentType("application/json").content(body)
                        .header("X-Hub-Signature-256", "sha256=" + "0".repeat(64)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(WEBHOOK).contentType("application/json").content(body)
                        .header("X-Hub-Signature-256", sign(body.replace("hola", "adios"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(WEBHOOK).contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());

        assertThat(gateway.sentTo(waId)).isEmpty();
    }

    @Test
    void deliveryAndReadStatusesAreAcceptedAndIgnored() throws Exception {
        String waId = newSender();

        receive(statusUpdate(waId, "wamid.outbound-1", "delivered"));
        receive(statusUpdate(waId, "wamid.outbound-1", "read"));

        assertThat(gateway.sentTo(waId)).isEmpty();
    }

    @Test
    void aStrayCookieSessionPlaysNoPartOnTheWebhook() throws Exception {
        String body = statusUpdate(newSender(), "wamid.outbound-2", "delivered");

        mvc.perform(post(WEBHOOK).contentType("application/json").content(body)
                        .cookie(new Cookie("access_token", "not-a-jwt"))
                        .header("X-Hub-Signature-256", sign(body)))
                .andExpect(status().isOk());
    }

    @Test
    void aMessageMetaDeliversTwiceIsProcessedOnce() throws Exception {
        String waId = newSender();
        receive(textMessage(waId, "wamid.retried", "hola"));

        clock.set("2026-03-12T15:00:00Z");
        receive(textMessage(waId, "wamid.retried", "hola"));
        assertThat(gateway.sentTo(waId)).hasSize(1);

        receive(textMessage(waId, "wamid.new", "hola"));
        assertThat(gateway.sentTo(waId)).hasSize(2);
    }

    @Test
    void anUnlinkedSenderIsToldHowToLinkInSpanishThenEnglish() throws Exception {
        String waId = newSender();

        receive(textMessage(waId, "wamid.first", "hola"));

        assertThat(gateway.sentTo(waId)).extracting(InMemoryWhatsAppGateway.Sent::body).containsExactly(NOT_LINKED);
    }

    @Test
    void anUnlinkedSenderIsToldAtMostOncePerDay() throws Exception {
        String waId = newSender();

        for (int i = 0; i < 5; i++) {
            clock.set("2026-03-10T15:%02d:00Z".formatted(i * 10));
            receive(textMessage(waId, "wamid.day-one-" + i, "hola " + i));
        }
        assertThat(gateway.sentTo(waId)).hasSize(1);

        clock.set("2026-03-11T14:59:00Z");
        receive(textMessage(waId, "wamid.almost-a-day", "hola"));
        assertThat(gateway.sentTo(waId)).hasSize(1);

        clock.set("2026-03-11T15:00:00Z");
        receive(textMessage(waId, "wamid.a-day-later", "hola"));
        assertThat(gateway.sentTo(waId)).extracting(InMemoryWhatsAppGateway.Sent::body)
                .containsExactly(NOT_LINKED, NOT_LINKED);
    }

    @Test
    void theDailyCleanupForgetsMessageIdsAfterAWeek() throws Exception {
        String waId = links.link(newSender());
        receive(textMessage(waId, "wamid.old", "hola"));

        clock.set("2026-03-17T14:59:00Z");
        cleanup.purge();
        receive(textMessage(waId, "wamid.old", "hola"));
        assertThat(links.handledIdsFrom(waId)).containsExactly("wamid.old");

        clock.set("2026-03-17T15:00:01Z");
        cleanup.purge();
        receive(textMessage(waId, "wamid.old", "hola"));
        assertThat(links.handledIdsFrom(waId)).containsExactly("wamid.old", "wamid.old");
    }

    @Test
    void aSenderPastThirtyMessagesAMinuteIsIgnoredForFiveMinutes() throws Exception {
        String waId = links.link(newSender());
        for (int i = 0; i < 30; i++) {
            clock.set("2026-03-10T15:00:%02dZ".formatted(i));
            receive(textMessage(waId, "wamid.flood-" + i, "hola"));
        }
        assertThat(links.handledIdsFrom(waId)).hasSize(30);

        clock.set("2026-03-10T15:00:30Z");
        receive(textMessage(waId, "wamid.flood-31", "hola"));
        clock.set("2026-03-10T15:05:29Z");
        receive(textMessage(waId, "wamid.still-blocked", "hola"));
        assertThat(links.handledIdsFrom(waId)).hasSize(30);

        clock.set("2026-03-10T15:05:30Z");
        receive(textMessage(waId, "wamid.after-the-block", "hola"));
        assertThat(links.handledIdsFrom(waId)).hasSize(31).last().isEqualTo("wamid.after-the-block");
        assertThat(gateway.sentTo(waId)).isEmpty();
    }

    @Test
    void aLinkedNumberGoesToTheLinkedSideInsteadOfBeingToldToLink() throws Exception {
        String waId = links.link(newSender());

        receive(textMessage(waId, "wamid.linked", "hola"));

        assertThat(links.handledIdsFrom(waId)).containsExactly("wamid.linked");
        assertThat(gateway.sentTo(waId)).isEmpty();
    }

    void receive(String body) throws Exception {
        mvc.perform(post(WEBHOOK).contentType("application/json").content(body)
                        .header("X-Hub-Signature-256", sign(body)))
                .andExpect(status().isOk());
    }

    /** A sender id never used by another test, so state kept per number does not leak between tests. */
    static String newSender() {
        return "5215500" + String.format("%06d", SENDERS.incrementAndGet());
    }

    static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    static String textMessage(String waId, String messageId, String text) {
        return envelope("""
                "contacts": [{"profile": {"name": "Juan"}, "wa_id": "%1$s"}],
                "messages": [{"from": "%1$s", "id": "%2$s", "timestamp": "1773154800", "type": "text",
                              "text": {"body": "%3$s"}}]
                """.formatted(waId, messageId, text));
    }

    static String statusUpdate(String waId, String messageId, String status) {
        return envelope("""
                "statuses": [{"id": "%2$s", "status": "%3$s", "timestamp": "1773154800", "recipient_id": "%1$s"}]
                """.formatted(waId, messageId, status));
    }

    private static String envelope(String value) {
        return """
                {"object": "whatsapp_business_account",
                 "entry": [{"id": "102290129340398", "changes": [{"field": "messages", "value": {
                   "messaging_product": "whatsapp",
                   "metadata": {"display_phone_number": "15550000001", "phone_number_id": "1000000001"},
                   %s
                 }}]}]}
                """.formatted(value);
    }
}
