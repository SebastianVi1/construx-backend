package org.andre.construx.modules.messaging.model;

import java.util.Locale;

/** The language the bot speaks to a Linked number, stored as {@code es} / {@code en}. */
public enum Language {
    ES, EN;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** {@code es} or {@code en}, any case; anything else is an {@link IllegalArgumentException}. */
    public static Language of(String code) {
        for (Language language : values()) {
            if (language.code().equalsIgnoreCase(code == null ? "" : code.strip())) {
                return language;
            }
        }
        throw new IllegalArgumentException("Language must be es or en");
    }
}
