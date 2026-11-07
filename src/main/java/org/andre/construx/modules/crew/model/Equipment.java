package org.andre.construx.modules.crew.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.organization.model.Organization;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "equipment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Equipment {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 80)
    private String type;

    @Column(nullable = false, length = 80)
    private String identifier;

    @Column(name = "daily_cost_cents")
    private Long dailyCostCents;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private Instant createdAt;

    public Equipment(Organization organization, String type, String identifier, Long dailyCostCents) {
        this.id = UUID.randomUUID();
        this.organization = organization;
        this.type = type.trim();
        this.identifier = identifier.trim();
        this.dailyCostCents = dailyCostCents;
        this.active = true;
        this.createdAt = Instant.now();
    }

    public void update(String type, String identifier, Long dailyCostCents, Boolean active) {
        if (type != null) this.type = type.trim();
        if (identifier != null) this.identifier = identifier.trim();
        if (dailyCostCents != null) this.dailyCostCents = dailyCostCents;
        if (active != null) this.active = active;
    }

    public void deactivate() {
        this.active = false;
    }
}
