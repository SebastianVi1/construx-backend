package org.andre.construx.modules.messaging.service;

import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.service.AuthMailer;
import org.andre.construx.modules.messaging.conversation.Language;
import org.andre.construx.shared.mail.BrandedEmail;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Tells the previous owner of a recycled number that it was linked to another account (Field updates contract §5
 * rule 1), in the language of the link they lost. Only the last 4 digits are shown. Delivery problems are logged by
 * {@link AuthMailer} and never stop the move.
 */
@Component
class NumberMovedMailer {
    private final AuthMailer mailer;
    private final String profileUrl;

    NumberMovedMailer(AuthMailer mailer, @Value("${app.security.frontend-url}") String frontendUrl) {
        this.mailer = mailer;
        this.profileUrl = frontendUrl + "/account";
    }

    void send(UserAccount previousOwner, String lastDigits, Language language) {
        mailer.send(previousOwner, language == Language.EN ? english(lastDigits) : spanish(lastDigits));
    }

    private BrandedEmail spanish(String lastDigits) {
        return new BrandedEmail("Tu WhatsApp se desvinculó de Construx",
                "El número terminado en " + lastDigits + " ahora está vinculado a otra cuenta.",
                "Tu WhatsApp se desvinculó",
                List.of("Hola:",
                        "El número de WhatsApp terminado en " + lastDigits + " se vinculó a otra cuenta de Construx "
                                + "con un código enviado desde ese número, así que ya no está vinculado a la tuya.",
                        "Si fue un error, entra a tu perfil y vincula tu número de nuevo."),
                "Ir a mi perfil",
                profileUrl,
                "Si cambiaste de número, no tienes que hacer nada.");
    }

    private BrandedEmail english(String lastDigits) {
        return new BrandedEmail("Your WhatsApp was unlinked from Construx",
                "The number ending in " + lastDigits + " is now linked to another account.",
                "Your WhatsApp was unlinked",
                List.of("Hi,",
                        "The WhatsApp number ending in " + lastDigits + " was linked to another Construx account "
                                + "with a code sent from that number, so it is no longer linked to yours.",
                        "If this was a mistake, open your profile and link your number again."),
                "Open my profile",
                profileUrl,
                "If you changed numbers, you don't need to do anything.");
    }
}
