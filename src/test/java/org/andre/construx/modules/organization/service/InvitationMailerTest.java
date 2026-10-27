package org.andre.construx.modules.organization.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InvitationMailerTest {
    @SuppressWarnings("unchecked")
    private InvitationMailer mailerWith(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        return new InvitationMailer(provider, "noreply@example.test", "https://app.example.test");
    }

    @Test
    void sendsBilingualMailWithTheAcceptLinkToTheInvitedAddress() {
        JavaMailSender sender = mock(JavaMailSender.class);
        mailerWith(sender).send("bob@acme.com", "Acme Builders", "RAW-TOKEN");
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(sent.capture());
        SimpleMailMessage message = sent.getValue();
        assertEquals("noreply@example.test", message.getFrom());
        assertArrayEquals(new String[] {"bob@acme.com"}, message.getTo());
        assertTrue(message.getSubject().contains("Acme Builders"));
        assertTrue(message.getText().contains("https://app.example.test/invitations/accept?token=RAW-TOKEN"));
        assertTrue(message.getText().contains("Has sido invitado"));
        assertTrue(message.getText().contains("You have been invited"));
    }

    @Test
    void neverPropagatesDeliveryProblems() {
        JavaMailSender sender = mock(JavaMailSender.class);
        doThrow(new MailSendException("smtp down")).when(sender).send(any(SimpleMailMessage.class));
        assertDoesNotThrow(() -> mailerWith(sender).send("bob@acme.com", "Acme", "T"));
        assertDoesNotThrow(() -> mailerWith(null).send("bob@acme.com", "Acme", "T"));
    }
}
