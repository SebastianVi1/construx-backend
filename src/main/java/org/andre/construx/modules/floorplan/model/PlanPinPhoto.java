package org.andre.construx.modules.floorplan.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.organization.model.OrganizationMembership;

import java.time.Instant;
import java.util.UUID;

/** A photo on a pin: REPORT photos show what was found, RESOLUTION photos show the fix. */
@Entity
@Table(name = "plan_pin_photo")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlanPinPhoto {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pin_id", nullable = false)
    private PlanPin pin;

    @Column(nullable = false, unique = true, length = 300)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PhotoPhase phase;

    @Column(nullable = false, length = 100)
    private String contentType;

    @Column(nullable = false)
    private long sizeBytes;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by_membership_id", nullable = false)
    private OrganizationMembership uploadedBy;

    @Column(nullable = false)
    private Instant createdAt;

    public PlanPinPhoto(PlanPin pin, OrganizationMembership uploadedBy, String storageKey,
                        PhotoPhase phase, String contentType, long sizeBytes) {
        this.id = UUID.randomUUID();
        this.pin = pin;
        this.uploadedBy = uploadedBy;
        this.storageKey = storageKey;
        this.phase = phase;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.createdAt = Instant.now();
    }
}
