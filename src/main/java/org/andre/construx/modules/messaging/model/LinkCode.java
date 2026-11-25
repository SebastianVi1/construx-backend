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

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** The one active code a user sends from WhatsApp to link that number. Only its hash is kept. */
@Entity
@Table(name = "whatsapp_link_code")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LinkCode {
    public static final Duration LIFETIME = Duration.ofMinutes(10);

    @Id
    @Column(name = "user_account_id")
    private UUID userId;

    @Column(name = "code_hash", length = 64, nullable = false)
    private String codeHash;

    /** The dashboard's language when the code was made; the linked number keeps it. */
    @Convert(converter = LanguageConverter.class)
    @Column(nullable = false, length = 2)
    private Language language;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public LinkCode(UUID userId, String codeHash, Language language, Instant now) {
        this.userId = userId;
        replace(codeHash, language, now);
    }

    /** A new code replaces the old one, which stops working at once. */
    public void replace(String codeHash, Language language, Instant now) {
        this.codeHash = codeHash;
        this.language = language;
        this.expiresAt = now.plus(LIFETIME);
    }

    public boolean isValidAt(Instant now) {
        return now.isBefore(expiresAt);
    }
}
