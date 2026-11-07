package org.andre.construx.shared.audit;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.organization.model.OrganizationMembership;

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_membership_id")
    private OrganizationMembership actorMembership;

    @Column(name = "before_state", columnDefinition = "text")
    private String beforeState;

    @Column(name = "after_state", columnDefinition = "text")
    private String afterState;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public AuditEvent(String entityType, UUID entityId, String action,
                      OrganizationMembership actorMembership,
                      String beforeState, String afterState, Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.actorMembership = actorMembership;
        this.beforeState = beforeState;
        this.afterState = afterState;
        this.occurredAt = occurredAt;
    }
}
