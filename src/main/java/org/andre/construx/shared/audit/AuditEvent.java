package org.andre.construx.shared.audit;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditEvent {

    @Id
    private UUID id;

    @Column(name = "entity_type", nullable = false, length = 80)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "action", nullable = false, length = 40)
    private String action;

    /** The organization membership that acted; null for system changes. */
    @Column(name = "actor_membership_id")
    private UUID actorMembershipId;

    @Column(name = "before_state", columnDefinition = "text")
    private String beforeState;

    @Column(name = "after_state", columnDefinition = "text")
    private String afterState;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public AuditEvent(String entityType, UUID entityId, String action,
                      UUID actorMembershipId,
                      String beforeState, String afterState, Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.actorMembershipId = actorMembershipId;
        this.beforeState = beforeState;
        this.afterState = afterState;
        this.occurredAt = occurredAt;
    }
}
