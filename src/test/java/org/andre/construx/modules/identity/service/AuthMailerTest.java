package org.andre.construx.modules.identity.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.andre.construx.modules.identity.model.UserAccount;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class AuthMailerTest {
    private final UserAccount user = new UserAccount("person@example.test", "hash");

    @SuppressWarnings("unchecked")
    private AuthMailer mailerWith(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        return new AuthMailer(provider, "noreply@example.test");
    }

    @Test
    void buildsAMessageWithFromToSubjectAndText() {
        JavaMailSender sender = mock(JavaMailSender.class);
        mailerWith(sender).send(user, "Subject line", "Body text");
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(sent.capture());
        assertEquals("noreply@example.test", sent.getValue().getFrom());
        assertArrayEquals(new String[] {"person@example.test"}, sent.getValue().getTo());
        assertEquals("Subject line", sent.getValue().getSubject());
        assertEquals("Body text", sent.getValue().getText());
    }

    @Test
    void missingSenderIsLoggedAndDoesNotThrow() {
        assertDoesNotThrow(() -> mailerWith(null).send(user, "s", "t"));
    }

    @Test
    void mailExceptionIsSwallowed() {
        JavaMailSender sender = mock(JavaMailSender.class);
        doThrow(new MailSendException("smtp down")).when(sender).send(any(SimpleMailMessage.class));
        assertDoesNotThrow(() -> mailerWith(sender).send(user, "s", "t"));
    }
}
