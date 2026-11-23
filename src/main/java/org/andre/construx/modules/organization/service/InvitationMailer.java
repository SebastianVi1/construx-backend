package org.andre.construx.modules.organization.service;

import jakarta.mail.MessagingException;
import org.andre.construx.shared.mail.BrandedEmail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.List;

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
        try {
            sender.send(invitation(organizationName, rawToken).toMimeMessage(sender, mailFrom, email));
        } catch (MailException | MessagingException ex) {
            log.error("Invitation email could not be sent", ex);
        }
    }

    BrandedEmail invitation(String organizationName, String rawToken) {
        return new BrandedEmail(
                "Te invitaron a unirte a " + organizationName + " en Construx",
                "Acepta la invitación para empezar a colaborar con " + organizationName + ".",
                "Te invitaron a " + organizationName,
                List.of("Hola:",
                        "Te invitaron a unirte al equipo de " + organizationName + " en Construx, la plataforma "
                                + "para coordinar obras, equipos y avances en un solo lugar.",
                        "Acepta la invitación para acceder a las obras y tareas que te asignen."),
                "Aceptar invitación",
                frontendUrl + "/invitations/accept?token=" + rawToken,
                "Si no esperabas esta invitación, puedes ignorar este correo.");
    }
}
