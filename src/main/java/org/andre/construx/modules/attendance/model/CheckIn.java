package org.andre.construx.modules.attendance.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.shared.validation.DomainValidation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One person recorded as present on a Site at a moment (ADR 0007). There is no check-out: a later Check-in the
 * same day replaces this one as the person's current one, and this row stays as history. Never updated.
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

    public CheckIn(Project site, OrganizationMembership membership, LocalDate day, Instant checkedInAt,
                   OrganizationMembership recordedBy) {
        this.id = UUID.randomUUID();
        this.site = DomainValidation.required(site, "Site is required");
        this.membership = DomainValidation.required(membership, "Person is required");
        this.day = DomainValidation.required(day, "Day is required");
        this.checkedInAt = DomainValidation.required(checkedInAt, "Moment is required");
        this.recordedBy = DomainValidation.required(recordedBy, "Recorder is required");
    }
}
