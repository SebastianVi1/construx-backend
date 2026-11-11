package org.andre.construx.modules.floorplan.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.shared.exception.RuleViolationException;

import java.time.Instant;
import java.util.UUID;

/**
 * A spot marked on a floor plan, in the pixel space of its rendered image (the space zone polygons use).
 * An ISSUE is a punch-list item tracked from OPEN to CLOSED; a PHOTO is a progress record with no status
 * and no assignee.
 */
@Entity
@Table(name = "plan_pin")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlanPin {

    public static final int MAX_TITLE = 120;
    public static final int MAX_DESCRIPTION = 500;

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "floor_plan_id", nullable = false)
    private FloorPlan floorPlan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PinKind kind;

    @Column(nullable = false)
    private double x;

    @Column(nullable = false)
    private double y;

    @Column(length = MAX_TITLE)
    private String title;

    @Column(length = MAX_DESCRIPTION)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PinStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_membership_id")
    private OrganizationMembership assignee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_membership_id", nullable = false)
    private OrganizationMembership createdBy;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant resolvedAt;

    private Instant closedAt;

    private Instant deletedAt;

    private PlanPin(FloorPlan floorPlan, OrganizationMembership createdBy, PinKind kind,
                    double x, double y, String title, String description) {
        this.id = UUID.randomUUID();
        this.floorPlan = floorPlan;
        this.createdBy = createdBy;
        this.kind = kind;
        this.status = kind == PinKind.ISSUE ? PinStatus.OPEN : null;
        this.createdAt = Instant.now();
        edit(title, description, x, y);
    }

    public static PlanPin issue(FloorPlan floorPlan, OrganizationMembership createdBy,
                                double x, double y, String title, String description) {
        return new PlanPin(floorPlan, createdBy, PinKind.ISSUE, x, y, title, description);
    }

    public static PlanPin photo(FloorPlan floorPlan, OrganizationMembership createdBy,
                                double x, double y, String title, String description) {
        return new PlanPin(floorPlan, createdBy, PinKind.PHOTO, x, y, title, description);
    }

    public void edit(String title, String description, double x, double y) {
        String cleanTitle = clean(title);
        String cleanDescription = clean(description);
        if (kind == PinKind.ISSUE && cleanTitle == null) {
            throw new RuleViolationException("PIN_TITLE_REQUIRED", "An issue needs a title");
        }
        if (cleanTitle != null && cleanTitle.length() > MAX_TITLE) {
            throw new RuleViolationException("PIN_TITLE_TOO_LONG", "Pin titles are at most 120 characters");
        }
        if (cleanDescription != null && cleanDescription.length() > MAX_DESCRIPTION) {
            throw new RuleViolationException("PIN_DESCRIPTION_TOO_LONG",
                    "Pin descriptions are at most 500 characters");
        }
        // The plan stores no image size, so only the lower bound can be checked here.
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0) {
            throw new RuleViolationException("PIN_OUT_OF_BOUNDS", "The pin must sit on the floor plan");
        }
        this.title = cleanTitle;
        this.description = cleanDescription;
        this.x = x;
        this.y = y;
        this.updatedAt = Instant.now();
    }

    /** {@code null} leaves the issue unassigned. */
    public void assign(OrganizationMembership assignee) {
        requireIssue();
        this.assignee = assignee;
        this.updatedAt = Instant.now();
    }

    public void resolve() {
        requireIssue();
        if (status != PinStatus.OPEN) {
            throw invalidTransition("Only an open issue can be resolved");
        }
        this.status = PinStatus.RESOLVED;
        this.resolvedAt = Instant.now();
        this.updatedAt = this.resolvedAt;
    }

    /** Closing straight from OPEN is allowed: a manager may verify the fix on the spot. */
    public void close() {
        requireIssue();
        if (status == PinStatus.CLOSED) {
            throw invalidTransition("The issue is already closed");
        }
        this.status = PinStatus.CLOSED;
        this.closedAt = Instant.now();
        this.updatedAt = this.closedAt;
    }

    public void reopen() {
        requireIssue();
        if (status == PinStatus.OPEN) {
            throw invalidTransition("The issue is already open");
        }
        this.status = PinStatus.OPEN;
        this.resolvedAt = null;
        this.closedAt = null;
        this.updatedAt = Instant.now();
    }

    public void softDelete() {
        this.deletedAt = Instant.now();
    }

    public boolean isIssue() {
        return kind == PinKind.ISSUE;
    }

    /** Whether the person who reported the pin may still change it: always for photos, issues only while open. */
    public boolean isOpenForReporter() {
        return kind == PinKind.PHOTO || status == PinStatus.OPEN;
    }

    private void requireIssue() {
        if (kind != PinKind.ISSUE) {
            throw new RuleViolationException("PIN_NOT_AN_ISSUE", "Only issues have an assignee and a status");
        }
    }

    private static RuleViolationException invalidTransition(String message) {
        return new RuleViolationException("PIN_INVALID_TRANSITION", message);
    }

    private static String clean(String value) {
        if (value == null) return null;
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
