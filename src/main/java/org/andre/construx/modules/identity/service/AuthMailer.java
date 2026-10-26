package org.andre.construx.modules.identity.service;

import org.andre.construx.modules.identity.model.UserAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends plain-text account emails. Delivery problems are logged with the user id only and never
 * propagated, so callers cannot leak whether an address exists.
 */
@Component
public class AuthMailer {
    private static final Logger log = LoggerFactory.getLogger(AuthMailer.class);

    private final ObjectProvider<JavaMailSender> mail;
    private final String mailFrom;

    public AuthMailer(ObjectProvider<JavaMailSender> mail, @Value("${app.security.mail-from}") String mailFrom) {
        this.mail = mail;
        this.mailFrom = mailFrom;
    }

    public void send(UserAccount user, String subject, String text) {
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null) {
            log.error("Email for user {} not sent: no mail sender configured", user.getId());
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(user.getEmail());
        message.setSubject(subject);
        message.setText(text);
        try {
            sender.send(message);
        } catch (MailException ex) {
            log.error("Email for user {} could not be sent", user.getId(), ex);
        }
    }
}
