package org.andre.construx.modules.organization.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.shared.exception.RuleViolationException;
import org.andre.construx.shared.validation.DomainValidation;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One member's progress through the dashboard's getting-started guide (docs/guide.md). The step list lives in the
 * dashboard; this only stores which step ids the member completed or skipped, and whether they hid the guide.
 */
@Entity
@Table(name = "guide_progress")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GuideProgress {
    /** Caps the rows one member can create, since step ids come from the client. */
    public static final int MAX_STEPS = 50;
    private static final Pattern STEP_ID = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");

    @Id
    private UUID id;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_membership_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private OrganizationMembership membership;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "guide_progress_step",
            joinColumns = @JoinColumn(name = "organization_membership_id"))
    @MapKeyColumn(name = "step_id", length = 64)
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Map<String, GuideStepStatus> steps = new HashMap<>();

    @Column(name = "dismissed_at")
    private Instant dismissedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public GuideProgress(OrganizationMembership membership) {
        this.membership = membership;
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** Records a step's status, replacing any earlier one, so a skipped step can be completed later. */
    public void mark(String stepId, GuideStepStatus status) {
        if (stepId == null || !STEP_ID.matcher(stepId).matches()) {
            throw new RuleViolationException("GUIDE_STEP_INVALID", "Step id is invalid");
        }
        DomainValidation.required(status, "Step status is required");
        if (!steps.containsKey(stepId) && steps.size() >= MAX_STEPS) {
            throw new RuleViolationException("GUIDE_TOO_MANY_STEPS", "Too many guide steps");
        }
        steps.put(stepId, status);
        touch();
    }

    /** Hides the guide ({@code true}, keeping the first time) or shows it again ({@code false}). */
    public void dismiss(boolean dismissed) {
        if (!dismissed) {
            dismissedAt = null;
        } else if (dismissedAt == null) {
            dismissedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
        touch();
    }

    private void touch() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Map<String, GuideStepStatus> getSteps() {
        return Collections.unmodifiableMap(steps);
    }
}
