package org.andre.construx.modules.messaging.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

/** A Meta message id already handled; Meta retries a post with the same ids. */
@Entity
@Table(name = "whatsapp_processed_message")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedMessage {
    /** Meta stops retrying well before this. */
    public static final Duration RETENTION = Duration.ofDays(7);

    @Id
    @Column(name = "message_id", length = 255)
    private String messageId;

    @Column(name = "wa_id", length = 20, nullable = false)
    private String waId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
}
