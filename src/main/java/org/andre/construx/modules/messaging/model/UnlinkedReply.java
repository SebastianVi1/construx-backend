package org.andre.construx.modules.messaging.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

/** The last time a number without a link was told how to link. */
@Entity
@Table(name = "whatsapp_unlinked_reply")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UnlinkedReply {
    public static final Duration QUIET_PERIOD = Duration.ofHours(24);

    @Id
    @Column(name = "wa_id", length = 20)
    private String waId;

    @Column(name = "replied_at", nullable = false)
    private Instant repliedAt;

    public UnlinkedReply(String waId, Instant repliedAt) {
        this.waId = waId;
        this.repliedAt = repliedAt;
    }

    /** Another reply may go out once 24 hours have passed since the last one. */
    public boolean isDue(Instant now) {
        return !now.isBefore(repliedAt.plus(QUIET_PERIOD));
    }

    public void repliedAt(Instant now) {
        this.repliedAt = now;
    }
}
