package org.andre.construx.modules.messaging;

import com.jayway.jsonpath.JsonPath;
import jakarta.mail.Address;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.service.TokenService;
import org.andre.construx.modules.messaging.service.WhatsAppDataCleanup;
import org.andre.construx.support.Api;
import org.andre.construx.support.Api.Session;
import org.andre.construx.support.MutableClock;
import org.andre.construx.shared.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.andre.construx.modules.messaging.WhatsAppWebhookFlowTest.newSender;
import static org.andre.construx.modules.messaging.WhatsAppWebhookFlowTest.sign;
import static org.andre.construx.modules.messaging.WhatsAppWebhookFlowTest.textMessage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Linking a WhatsApp number from the profile (Field updates contract §4.1, §5 rules 1–3): the dashboard endpoints
 * and signed webhook posts from the number, with what the bot sent read from the {@link InMemoryWhatsAppGateway}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(WhatsAppLinkFlowTest.Fakes.class)
class WhatsAppLinkFlowTest {
    static final Instant START = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    static final String PROFILE = "/api/profile/whatsapp";

    @TestConfiguration
    static class Fakes {
        @Bean @Primary InMemoryWhatsAppGateway inMemoryWhatsAppGateway() {
            return new InMemoryWhatsAppGateway();
        }

        @Bean @Primary MutableClock whatsAppClock() {
            return new MutableClock(START);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;
    @Autowired InMemoryWhatsAppGateway gateway;
    @Autowired MutableClock clock;
    @Autowired AuditService audit;
    @Autowired WhatsAppDataCleanup cleanup;
    @MockitoBean JavaMailSender mail;

    Api api;

    @BeforeEach
    void setUp() {
        api = new Api(mvc, users, tokens);
        clock.set(START.toString());
        gateway.clear();
        when(mail.createMimeMessage()).thenAnswer(invocation -> new MimeMessage((jakarta.mail.Session) null));
    }

    @Test
    void anUnlinkedProfileShowsTheConstruxNumberAndNothingElse() throws Exception {
        Session user = api.signUp();

        api.get(user, PROFILE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(false))
                .andExpect(jsonPath("$.number").isEmpty())
                .andExpect(jsonPath("$.language").isEmpty())
                .andExpect(jsonPath("$.linkedAt").isEmpty())
                .andExpect(jsonPath("$.construxNumber").value("+15550000001"));
    }

    @Test
    void aLinkCodeComesWithAWaMeLinkAndExpiresInTenMinutes() throws Exception {
        Session user = api.signUp();

        String body = api.post(user, PROFILE + "/link-code", "{\"language\":\"es\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.construxNumber").value("+15550000001"))
                .andReturn().getResponse().getContentAsString();

        String code = JsonPath.read(body, "$.code");
        assertThat(code).matches("CX-\\d{4}");
        assertThat((String) JsonPath.read(body, "$.waMeUrl")).isEqualTo("https://wa.me/15550000001?text=" + code);
        assertThat(Instant.parse(JsonPath.read(body, "$.expiresAt"))).isEqualTo(START.plus(Duration.ofMinutes(10)));
    }

    @Test
    void aLinkCodeNeedsSpanishOrEnglish() throws Exception {
        Session user = api.signUp();

        api.post(user, PROFILE + "/link-code", "{\"language\":\"fr\"}").andExpect(status().isBadRequest());
        api.post(user, PROFILE + "/link-code", "{}").andExpect(status().isBadRequest());
    }

    @Test
    void sendingTheCodeLinksTheNumberInTheCodesLanguage() throws Exception {
        Session user = named("Ana López");
        String code = code(user, "es");
        String waId = newSender();
        clock.set(START.plusSeconds(90).toString());

        say(waId, code);

        assertThat(replies(waId)).containsExactly("¡Listo, Ana López! Tu WhatsApp quedó vinculado. Escribe cualquier "
                + "mensaje para actualizar tus tareas. Envía BAJA para desvincularlo.");
        api.get(user, PROFILE)
                .andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.number").value("+" + waId))
                .andExpect(jsonPath("$.language").value("es"))
                .andExpect(jsonPath("$.linkedAt").value(START.plusSeconds(90).toString()))
                .andExpect(jsonPath("$.construxNumber").value("+15550000001"));
        assertThat(audit.listForEntity("WHATSAPP_LINK", user.user().getId()))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.action()).isEqualTo("LINK");
                    assertThat(event.afterState()).contains("\"source\":\"WHATSAPP\"").doesNotContain(waId);
                });
    }

    @Test
    void theCodeIsComparedTrimmedAndUppercasedAndTheReplyFollowsItsLanguage() throws Exception {
        Session user = named("Ana");
        String code = code(user, "en");
        String waId = newSender();

        say(waId, "  " + code.toLowerCase() + " ");

        assertThat(replies(waId)).containsExactly(
                "Done, Ana! Your WhatsApp is linked. Send any message to update your tasks. Send STOP to unlink.");
    }

    static final String CODE_INVALID = """
            Ese código no es válido o ya venció. Genera uno nuevo en tu perfil.

            That code is invalid or expired. Generate a new one in your profile.""";

    @Test
    void aCodeWorksOnlyOnce() throws Exception {
        Session user = named("Ana");
        String code = code(user, "es");
        String first = newSender();
        String second = newSender();

        say(first, code);
        say(second, code);

        assertThat(replies(second)).containsExactly(CODE_INVALID);
        api.get(user, PROFILE).andExpect(jsonPath("$.number").value("+" + first));
    }

    @Test
    void aCodeExpiresAfterTenMinutes() throws Exception {
        Session user = named("Ana");
        String code = code(user, "es");
        String waId = newSender();

        clock.set(START.plus(Duration.ofMinutes(10)).toString());
        say(waId, code);

        assertThat(replies(waId)).containsExactly(CODE_INVALID);
        api.get(user, PROFILE).andExpect(jsonPath("$.linked").value(false));
    }

    @Test
    void aNewCodeReplacesTheOldOne() throws Exception {
        Session user = named("Ana");
        String old = code(user, "es");
        String current = code(user, "en");
        while (current.equals(old)) {
            current = code(user, "en");
        }
        String waId = newSender();

        say(waId, old);
        assertThat(replies(waId)).containsExactly(CODE_INVALID);

        say(waId, current);
        api.get(user, PROFILE).andExpect(jsonPath("$.linked").value(true)).andExpect(jsonPath("$.language").value("en"));
    }

    @Test
    void aWrongCodeIsInvalid() throws Exception {
        String waId = newSender();

        say(waId, "CX-0000");

        assertThat(replies(waId)).containsExactly(CODE_INVALID);
    }

    @Test
    void linkingAnotherNumberReplacesTheOneTheUserHad() throws Exception {
        Session user = named("Ana");
        String oldNumber = newSender();
        say(oldNumber, code(user, "es"));
        String newNumber = newSender();

        say(newNumber, code(user, "en"));

        api.get(user, PROFILE).andExpect(jsonPath("$.number").value("+" + newNumber))
                .andExpect(jsonPath("$.language").value("en"));
        gateway.clear();
        say(oldNumber, "hola");
        assertThat(replies(oldNumber)).containsExactly(WhatsAppWebhookFlowTest.NOT_LINKED);
    }

    @Test
    void theLanguageCanBeChangedOnlyOnceLinked() throws Exception {
        Session user = named("Ana");
        api.put(user, PROFILE + "/language", "{\"language\":\"en\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WHATSAPP_NOT_LINKED"));
        String waId = newSender();
        say(waId, code(user, "es"));

        api.put(user, PROFILE + "/language", "{\"language\":\"en\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.number").value("+" + waId))
                .andExpect(jsonPath("$.language").value("en"));

        gateway.clear();
        say(waId, "stop");
        assertThat(replies(waId)).containsExactly("Your number was unlinked from Construx.");
    }

    @Test
    void unlinkingFromTheProfileFreesTheNumber() throws Exception {
        Session user = named("Ana");
        String waId = newSender();
        say(waId, code(user, "es"));

        api.delete(user, PROFILE).andExpect(status().isNoContent());

        api.get(user, PROFILE).andExpect(jsonPath("$.linked").value(false)).andExpect(jsonPath("$.number").isEmpty());
        gateway.clear();
        say(waId, "hola");
        assertThat(replies(waId)).containsExactly(WhatsAppWebhookFlowTest.NOT_LINKED);
        assertThat(audit.listForEntity("WHATSAPP_LINK", user.user().getId()))
                .extracting(AuditService.AuditEventData::action).containsExactly("UNLINK", "LINK");
        assertThat(audit.listForEntity("WHATSAPP_LINK", user.user().getId()).getFirst().beforeState())
                .contains("\"source\":\"APP\"");
        api.delete(user, PROFILE).andExpect(status().isNoContent());
    }

    @Test
    void stopOrBajaInAnyCaseUnlinksTheNumberAndTheNextMessageIsToldToLink() throws Exception {
        for (String stop : List.of("STOP", "baja", " Stop ", "Baja")) {
            Session user = named("Ana");
            String waId = newSender();
            say(waId, "hola");
            say(waId, code(user, "es"));
            gateway.clear();

            say(waId, stop);
            say(waId, "hola");

            assertThat(replies(waId)).containsExactly("Tu número se desvinculó de Construx.",
                    WhatsAppWebhookFlowTest.NOT_LINKED);
            api.get(user, PROFILE).andExpect(jsonPath("$.linked").value(false));
            assertThat(audit.listForEntity("WHATSAPP_LINK", user.user().getId()).getFirst())
                    .satisfies(event -> {
                        assertThat(event.action()).isEqualTo("UNLINK");
                        assertThat(event.beforeState()).contains("\"source\":\"WHATSAPP\"");
                    });
        }
    }

    @Test
    void aDisabledAccountsNumberIsTreatedAsUnlinked() throws Exception {
        Session user = named("Ana");
        String waId = newSender();
        say(waId, code(user, "es"));
        gateway.clear();
        UserAccount account = users.findById(user.user().getId()).orElseThrow();
        account.disable();
        users.save(account);

        say(waId, "STOP");

        assertThat(replies(waId)).containsExactly(WhatsAppWebhookFlowTest.NOT_LINKED);
    }

    @Test
    void aDisabledAccountsCodeIsInvalid() throws Exception {
        Session user = named("Ana");
        String code = code(user, "es");
        UserAccount account = users.findById(user.user().getId()).orElseThrow();
        account.disable();
        users.save(account);
        String waId = newSender();

        say(waId, code);

        assertThat(replies(waId)).containsExactly(CODE_INVALID);
    }

    @Test
    void aCodeFromANumberLinkedToSomeoneElseMovesItAndEmailsThePreviousOwner() throws Exception {
        Session previous = named("Ana");
        Session next = named("Beto");
        String waId = newSender();
        say(waId, code(previous, "es"));
        gateway.clear();
        clearInvocations(mail);

        say(waId, code(next, "en"));

        assertThat(replies(waId)).containsExactly(
                "Done, Beto! Your WhatsApp is linked. Send any message to update your tasks. Send STOP to unlink.");
        api.get(previous, PROFILE).andExpect(jsonPath("$.linked").value(false));
        api.get(next, PROFILE).andExpect(jsonPath("$.number").value("+" + waId));
        MimeMessage sent = sentMail();
        assertThat(sent.getAllRecipients()).extracting(Address::toString).containsExactly(previous.user().getEmail());
        assertThat(sent.getSubject()).isEqualTo("Tu WhatsApp se desvinculó de Construx");
        assertThat(plainText(sent)).contains("terminado en " + waId.substring(waId.length() - 4))
                .doesNotContain(waId);
        assertThat(audit.listForEntity("WHATSAPP_LINK", previous.user().getId()).getFirst())
                .satisfies(event -> {
                    assertThat(event.action()).isEqualTo("MOVE");
                    assertThat(event.afterState()).contains("\"source\":\"WHATSAPP\"").doesNotContain(waId);
                });
        assertThat(audit.listForEntity("WHATSAPP_LINK", next.user().getId()))
                .extracting(AuditService.AuditEventData::action).containsExactly("LINK");
    }

    @Test
    void thePreviousOwnerIsEmailedInTheLanguageOfTheirLink() throws Exception {
        Session previous = named("Ana");
        Session next = named("Beto");
        String waId = newSender();
        say(waId, code(previous, "en"));
        clearInvocations(mail);

        say(waId, code(next, "es"));

        MimeMessage sent = sentMail();
        assertThat(sent.getSubject()).isEqualTo("Your WhatsApp was unlinked from Construx");
        assertThat(plainText(sent)).contains("ending in " + waId.substring(waId.length() - 4));
    }

    @Test
    void relinkingYourOwnNumberSendsNoEmail() throws Exception {
        Session user = named("Ana");
        String waId = newSender();
        say(waId, code(user, "es"));

        say(waId, code(user, "en"));

        api.get(user, PROFILE).andExpect(jsonPath("$.language").value("en"));
        verify(mail, never()).send(any(MimeMessage.class));
    }

    @Test
    void afterTenWrongCodesInAnHourTheNumberIsIgnoredForAnHour() throws Exception {
        Session user = named("Ana");
        String waId = newSender();
        for (int i = 0; i < 10; i++) {
            clock.set(START.plus(Duration.ofMinutes(5 * i)).toString());
            say(waId, "CX-0000");
        }
        assertThat(replies(waId)).hasSize(10).containsOnly(CODE_INVALID);
        gateway.clear();
        String code = code(user, "es");

        say(waId, "hola");
        say(waId, code);
        clock.set(START.plus(Duration.ofMinutes(45 + 59)).toString());
        say(waId, "hola");

        assertThat(replies(waId)).isEmpty();
        api.get(user, PROFILE).andExpect(jsonPath("$.linked").value(false));

        clock.set(START.plus(Duration.ofMinutes(45 + 60)).toString());
        say(waId, "hola");
        assertThat(replies(waId)).containsExactly(WhatsAppWebhookFlowTest.NOT_LINKED);
    }

    @Test
    void wrongCodesSpreadOverMoreThanAnHourAreNotBlocked() throws Exception {
        String waId = newSender();
        for (int i = 0; i < 9; i++) {
            say(waId, "CX-0000");
        }
        clock.set(START.plus(Duration.ofHours(1)).toString());
        for (int i = 0; i < 9; i++) {
            say(waId, "CX-0000");
        }

        assertThat(replies(waId)).hasSize(18).containsOnly(CODE_INVALID);
    }

    @Test
    void theCleanupNeverLiftsABlock() throws Exception {
        String waId = newSender();
        for (int i = 0; i < 10; i++) {
            clock.set(START.plus(Duration.ofMinutes(5 * i)).toString());
            say(waId, "CX-0000");
        }
        gateway.clear();

        clock.set(START.plus(Duration.ofMinutes(100)).toString());
        cleanup.purge();
        say(waId, "hola");

        assertThat(replies(waId)).isEmpty();
    }

    @Test
    void aLinkedNumberSendingWrongCodesIsAlsoIgnoredForAnHour() throws Exception {
        Session user = named("Ana");
        String waId = newSender();
        say(waId, code(user, "es"));
        for (int i = 0; i < 10; i++) {
            say(waId, "CX-0000");
        }
        gateway.clear();

        say(waId, "STOP");

        assertThat(replies(waId)).isEmpty();
        api.get(user, PROFILE).andExpect(jsonPath("$.linked").value(true));
    }

    void say(String waId, String text) throws Exception {
        String body = textMessage(waId, "wamid." + java.util.UUID.randomUUID(), text);
        mvc.perform(post("/api/webhooks/whatsapp").contentType("application/json").content(body)
                        .header("X-Hub-Signature-256", sign(body)))
                .andExpect(status().isOk());
    }

    MimeMessage sentMail() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(captor.capture());
        return captor.getValue();
    }

    static String plainText(Part part) throws Exception {
        Object content = part.getContent();
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                String text = plainText(multipart.getBodyPart(i));
                if (text != null) return text;
            }
            return null;
        }
        return part.getDataHandler().getContentType().startsWith("text/plain") ? (String) content : null;
    }

    List<String> replies(String waId) {
        return gateway.sentTo(waId).stream().map(InMemoryWhatsAppGateway.Sent::body).toList();
    }

    Session named(String fullName) throws Exception {
        Session user = api.signUp();
        api.put(user, "/api/profile", "{\"fullName\":\"" + fullName + "\",\"phoneNumber\":\"+525512345678\"}")
                .andExpect(status().isOk());
        return user;
    }

    String code(Session user, String language) throws Exception {
        String body = api.post(user, PROFILE + "/link-code", "{\"language\":\"" + language + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.code");
    }
}
