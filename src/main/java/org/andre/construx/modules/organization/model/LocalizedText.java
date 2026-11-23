package org.andre.construx.modules.organization.model;

/** A user-facing text in both languages the app speaks. Serialized as {@code { "en": …, "es": … }}. */
public record LocalizedText(String en, String es) {
    /** The same text in both languages, e.g. a custom role name typed by a user. */
    public static LocalizedText same(String text) {
        return new LocalizedText(text, text);
    }
}
