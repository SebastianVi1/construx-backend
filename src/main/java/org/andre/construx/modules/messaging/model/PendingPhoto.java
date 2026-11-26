package org.andre.construx.modules.messaging.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;
import java.util.UUID;

/**
 * A photo a Linked number sent during a Field update, stored before YES (contract §2 {@code whatsapp_pending_photo}).
 * {@code position} is the order it arrived in, from 0.
 */
@Entity
@Table(name = "whatsapp_pending_photo")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PendingPhoto {
    @Id
    private UUID id;

    @Column(name = "wa_id", nullable = false, length = 20)
    private String waId;

    @Column(name = "storage_key", nullable = false, length = 300)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private int position;

    public PendingPhoto(String waId, String storageKey, String contentType, long sizeBytes, int position) {
        this.id = UUID.randomUUID();
        this.waId = Objects.requireNonNull(waId);
        this.storageKey = Objects.requireNonNull(storageKey);
        this.contentType = Objects.requireNonNull(contentType);
        this.sizeBytes = sizeBytes;
        this.position = position;
    }
}
