package org.andre.construx.modules.messaging.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.andre.construx.modules.messaging.conversation.Language;

/** Stores a {@link Language} as {@code es} / {@code en}. */
@Converter
class LanguageConverter implements AttributeConverter<Language, String> {
    @Override
    public String convertToDatabaseColumn(Language language) {
        return language == null ? null : language.code();
    }

    @Override
    public Language convertToEntityAttribute(String code) {
        return code == null ? null : Language.of(code);
    }
}
