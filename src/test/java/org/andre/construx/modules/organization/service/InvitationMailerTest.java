package org.andre.construx.modules.organization.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.andre.construx.shared.mail.BrandedEmail;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
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

    private static JavaMailSender senderMock() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage((Session) null));
        return sender;
    }

    @Test
    void sendsTheInvitationToTheInvitedAddress() throws Exception {
        JavaMailSender sender = senderMock();
        mailerWith(sender).send("bob@acme.com", "Acme Builders", "RAW-TOKEN");
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(sent.capture());
        MimeMessage message = sent.getValue();
        assertEquals("noreply@example.test", message.getFrom()[0].toString());
        assertEquals("bob@acme.com", message.getAllRecipients()[0].toString());
        assertEquals("Te invitaron a unirte a Acme Builders en Construx", message.getSubject());
    }

    @Test
    void invitationCarriesTheAcceptLinkInSpanish() {
        BrandedEmail email = mailerWith(senderMock()).invitation("Acme Builders", "RAW-TOKEN");
        assertEquals("https://app.example.test/invitations/accept?token=RAW-TOKEN", email.actionUrl());
        assertTrue(email.text().contains("Te invitaron a unirte al equipo de Acme Builders"));
        assertTrue(email.html().contains("href=\"https://app.example.test/invitations/accept?token=RAW-TOKEN\""));
    }

    @Test
    void companyNameIsEscapedInTheHtml() {
        String html = mailerWith(senderMock()).invitation("<b>{{actionUrl}}</b>", "T").html();
        assertFalse(html.contains("<b>"));
        assertTrue(html.contains("&lt;b&gt;{{actionUrl}}&lt;/b&gt;"));
    }

    @Test
    void neverPropagatesDeliveryProblems() {
        JavaMailSender sender = senderMock();
        doThrow(new MailSendException("smtp down")).when(sender).send(any(MimeMessage.class));
        assertDoesNotThrow(() -> mailerWith(sender).send("bob@acme.com", "Acme", "T"));
        assertDoesNotThrow(() -> mailerWith(null).send("bob@acme.com", "Acme", "T"));
    }
}
