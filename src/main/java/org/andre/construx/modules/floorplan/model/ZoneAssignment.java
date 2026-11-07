package org.andre.construx.modules.floorplan.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.crew.model.Crew;
import org.andre.construx.modules.crew.model.Equipment;
import org.andre.construx.modules.organization.model.OrganizationMembership;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "zone_assignment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ZoneAssignment {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "zone_id", nullable = false)
    private FloorPlanZone zone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "membership_id")
    private OrganizationMembership membership;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "crew_id")
    private Crew crew;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "equipment_id")
    private Equipment equipment;

    @Column(nullable = false, length = 255)
    private String activity;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AssignmentShift shift;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assigned_by_membership_id", nullable = false)
    private OrganizationMembership assignedBy;

    @Column(nullable = false)
    private Instant createdAt;

    @Column
    private Instant closedAt;

    public ZoneAssignment(FloorPlanZone zone,
                          OrganizationMembership membership,
                          Crew crew,
                          Equipment equipment,
                          String activity,
                          LocalDate startDate,
                          LocalDate endDate,
                          AssignmentShift shift,
                          OrganizationMembership assignedBy) {
        this.id = UUID.randomUUID();
        this.zone = zone;
        this.membership = membership;
        this.crew = crew;
        this.equipment = equipment;
        this.activity = activity.trim();
        this.startDate = startDate;
        this.endDate = endDate;
        this.shift = shift;
        this.assignedBy = assignedBy;
        this.createdAt = Instant.now();
    }

    public void close() {
        this.closedAt = Instant.now();
    }
}
