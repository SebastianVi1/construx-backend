package org.andre.construx.modules.organization.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Emails an invitation link. The invitee may not have an account yet, so it takes a plain address.
 * Delivery problems are logged and never propagated: the invitation exists and can be resent.
 */
@Component
public class InvitationMailer {
    private static final Logger log = LoggerFactory.getLogger(InvitationMailer.class);

    private final ObjectProvider<JavaMailSender> mail;
    private final String mailFrom;
    private final String frontendUrl;

    public InvitationMailer(ObjectProvider<JavaMailSender> mail, @Value("${app.security.mail-from}") String mailFrom,
                            @Value("${app.security.frontend-url}") String frontendUrl) {
        this.mail = mail;
        this.mailFrom = mailFrom;
        this.frontendUrl = frontendUrl;
    }

    public void send(String email, String organizationName, String rawToken) {
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null) {
            log.error("Invitation email not sent: no mail sender configured");
            return;
        }
        String link = frontendUrl + "/invitations/accept?token=" + rawToken;
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(email);
        message.setSubject("Invitation to " + organizationName + " / Invitación a " + organizationName);
        message.setText("You have been invited to join " + organizationName + " on Construx. Open this link: "
                + link + "\n\n"
                + "Has sido invitado a unirte a " + organizationName + " en Construx. Abre este enlace: " + link);
        try {
            sender.send(message);
        } catch (MailException ex) {
            log.error("Invitation email could not be sent", ex);
        }
    }
}
