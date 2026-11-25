package org.andre.construx.modules.messaging.conversation;

/** What the bot says, in both languages (Field updates contract §6). */
public enum BotText {
    NOT_LINKED("Este número no está vinculado a Construx. Entra a tu perfil, toca \"Vincular WhatsApp\" y envía aquí el código.",
            "This number isn't linked to Construx. Open your profile, tap \"Link WhatsApp\" and send the code here.");

    private final String es;
    private final String en;

    BotText(String es, String en) {
        this.es = es;
        this.en = en;
    }

    public String in(Language language) {
        return language == Language.ES ? es : en;
    }

    /** Before a number is linked its language is unknown: Spanish first, then English, in one message. */
    public String bilingual() {
        return es + "\n\n" + en;
    }
}
