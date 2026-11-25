package org.andre.construx.modules.messaging.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.messaging.conversation.Language;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A Linked number: the WhatsApp sender id one account proved is theirs, and the language the bot speaks to it. */
@Entity
@Table(name = "whatsapp_link")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WhatsAppLink {
    @Id
    private UUID id;

    @Column(name = "user_account_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "wa_id", length = 20, nullable = false, unique = true)
    private String waId;

    @Convert(converter = LanguageConverter.class)
    @Column(nullable = false, length = 2)
    private Language language;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    public WhatsAppLink(UUID userId, String waId, Language language, Instant linkedAt) {
        this.id = UUID.randomUUID();
        this.userId = Objects.requireNonNull(userId);
        relink(waId, language, linkedAt);
    }

    /** The user linked a number again: it replaces the one they had. */
    public void relink(String waId, Language language, Instant linkedAt) {
        this.waId = Objects.requireNonNull(waId);
        this.language = Objects.requireNonNull(language);
        this.linkedAt = Objects.requireNonNull(linkedAt);
    }

    public void changeLanguage(Language language) {
        this.language = Objects.requireNonNull(language);
    }
}
