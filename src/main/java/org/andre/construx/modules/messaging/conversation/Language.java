package org.andre.construx.modules.messaging.conversation;

/** The language the bot speaks to a Linked number, stored as {@code es} / {@code en}. */
public enum Language {
    ES, EN;

    public String code() {
        return name().toLowerCase();
    }
}
