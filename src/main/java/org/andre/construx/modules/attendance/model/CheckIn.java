package org.andre.construx.modules.attendance.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.floorplan.model.FloorPlanZone;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.shared.validation.DomainValidation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One person recorded as present on a Site at a moment (ADR 0007). There is no check-out: a later Check-in the
 * same day replaces this one as the person's current one, and this row stays as history. The only change it
 * accepts is its removal, on its own day: the row is kept, marked with who removed it and when, and ignored.
 */
@Entity
@Table(name = "check_in")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CheckIn {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project site;

    /** The person who is present. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "membership_id", nullable = false)
    private OrganizationMembership membership;

    /**
     * The floor plan zone the person is in, or null for present with no zone. It keeps pointing at the zone
     * after the zone is deleted, so history still shows its name.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zone_id")
    private FloorPlanZone zone;

    /** The calendar day in the configured time zone that the Check-in counts for. */
    @Column(name = "check_in_day", nullable = false)
    private LocalDate day;

    /** The exact moment. */
    @Column(nullable = false)
    private Instant checkedInAt;

    /** Who recorded it: the person themselves, or someone answering for them. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recorded_by_membership_id", nullable = false)
    private OrganizationMembership recordedBy;

    /** Set when the Check-in was removed as a mistake; a removed Check-in is ignored everywhere. */
    private Instant removedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_by_membership_id")
    private OrganizationMembership removedBy;

    public CheckIn(Project site, OrganizationMembership membership, FloorPlanZone zone, LocalDate day,
                   Instant checkedInAt, OrganizationMembership recordedBy) {
        this.id = UUID.randomUUID();
        this.site = DomainValidation.required(site, "Site is required");
        this.membership = DomainValidation.required(membership, "Person is required");
        this.zone = zone;
        this.day = DomainValidation.required(day, "Day is required");
        this.checkedInAt = DomainValidation.required(checkedInAt, "Moment is required");
        this.recordedBy = DomainValidation.required(recordedBy, "Recorder is required");
    }

    /** Whether this Check-in already says the person is in that zone ({@code null}: in no zone). */
    public boolean isIn(FloorPlanZone other) {
        return zone == null ? other == null : other != null && zone.getId().equals(other.getId());
    }

    public boolean isRemoved() {
        return removedAt != null;
    }

    /**
     * Marks the Check-in as removed. Removing an already removed one changes nothing and keeps the first
     * removal. Only the day the Check-in belongs to accepts it: {@code today} is the current day.
     */
    public void remove(OrganizationMembership by, Instant at, LocalDate today) {
        DomainValidation.required(by, "Who removes it is required");
        DomainValidation.required(at, "Moment is required");
        if (isRemoved()) {
            return;
        }
        if (!day.equals(today)) {
            throw new CheckInLockedException();
        }
        this.removedAt = at;
        this.removedBy = by;
    }
}
