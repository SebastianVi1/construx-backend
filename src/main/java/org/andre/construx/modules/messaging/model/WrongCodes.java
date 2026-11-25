package org.andre.construx.modules.messaging.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

/**
 * The wrong link codes a number sent in its current one-hour window (contract §4.1). The 10th wrong code in the window
 * blocks the number for an hour; a code after the window or the block starts a new window.
 */
@Entity
@Table(name = "whatsapp_wrong_code")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WrongCodes {
    public static final int LIMIT = 10;
    public static final Duration WINDOW = Duration.ofHours(1);
    public static final Duration BLOCK = Duration.ofHours(1);

    @Id
    @Column(name = "wa_id", length = 20)
    private String waId;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "wrong_count", nullable = false)
    private int count;

    @Column(name = "blocked_until")
    private Instant blockedUntil;

    public WrongCodes(String waId, Instant now) {
        this.waId = waId;
        this.windowStart = now;
        this.count = 1;
    }

    public boolean blocksAt(Instant now) {
        return blockedUntil != null && now.isBefore(blockedUntil);
    }

    /** Counts one more wrong code at {@code now}. */
    public void countAt(Instant now) {
        boolean blockOver = blockedUntil != null && !now.isBefore(blockedUntil);
        if (blockOver || !now.isBefore(windowStart.plus(WINDOW))) {
            windowStart = now;
            count = 0;
            blockedUntil = null;
        }
        count++;
        if (count >= LIMIT) {
            blockedUntil = now.plus(BLOCK);
        }
    }
}
