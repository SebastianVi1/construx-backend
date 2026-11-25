package org.andre.construx.modules.messaging.conversation;

import java.util.Map;

/** What the bot says, in both languages (Field updates contract §6). {@code {name}}-style parts are placeholders. */
public enum BotText {
    NOT_LINKED("Este número no está vinculado a Construx. Entra a tu perfil, toca \"Vincular WhatsApp\" y envía aquí el código.",
            "This number isn't linked to Construx. Open your profile, tap \"Link WhatsApp\" and send the code here."),
    CODE_INVALID("Ese código no es válido o ya venció. Genera uno nuevo en tu perfil.",
            "That code is invalid or expired. Generate a new one in your profile."),
    LINKED("¡Listo, {name}! Tu WhatsApp quedó vinculado. Escribe cualquier mensaje para actualizar tus tareas. Envía BAJA para desvincularlo.",
            "Done, {name}! Your WhatsApp is linked. Send any message to update your tasks. Send STOP to unlink."),
    UNLINKED("Tu número se desvinculó de Construx.",
            "Your number was unlinked from Construx.");

    private final String es;
    private final String en;

    BotText(String es, String en) {
        this.es = es;
        this.en = en;
    }

    public String in(Language language) {
        return language == Language.ES ? es : en;
    }

    /** The text with each {@code {key}} replaced by its value. */
    public String in(Language language, Map<String, String> values) {
        String text = in(language);
        for (Map.Entry<String, String> value : values.entrySet()) {
            text = text.replace("{" + value.getKey() + "}", value.getValue());
        }
        return text;
    }

    /** Before a number is linked its language is unknown: Spanish first, then English, in one message. */
    public String bilingual() {
        return es + "\n\n" + en;
    }
}
