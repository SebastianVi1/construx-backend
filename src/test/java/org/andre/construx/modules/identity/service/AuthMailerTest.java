package org.andre.construx.modules.identity.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.shared.mail.BrandedEmail;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

class AuthMailerTest {
    private final UserAccount user = new UserAccount("person@example.test", "hash");
    private final BrandedEmail email = new BrandedEmail("Asunto", "Vista previa", "Título", List.of("Hola"),
            "Abrir", "https://app.example.test/x", "Nota");

    @SuppressWarnings("unchecked")
    private AuthMailer mailerWith(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        return new AuthMailer(provider, "noreply@example.test");
    }

    private static JavaMailSender senderMock() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage((Session) null));
        return sender;
    }

    @Test
    void buildsAMessageWithFromToAndSubject() throws Exception {
        JavaMailSender sender = senderMock();
        mailerWith(sender).send(user, email);
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(sent.capture());
        assertArrayEquals(new InternetAddress[] {new InternetAddress("noreply@example.test")}, sent.getValue().getFrom());
        assertEquals("person@example.test", sent.getValue().getAllRecipients()[0].toString());
        assertEquals("Asunto", sent.getValue().getSubject());
    }

    @Test
    void missingSenderIsLoggedAndDoesNotThrow() {
        assertDoesNotThrow(() -> mailerWith(null).send(user, email));
    }

    @Test
    void mailExceptionIsSwallowed() {
        JavaMailSender sender = senderMock();
        doThrow(new MailSendException("smtp down")).when(sender).send(any(MimeMessage.class));
        assertDoesNotThrow(() -> mailerWith(sender).send(user, email));
    }
}
