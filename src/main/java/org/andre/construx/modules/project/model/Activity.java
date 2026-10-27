package org.andre.construx.modules.project.model;

import org.andre.construx.shared.validation.DomainValidation;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "activity")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Activity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "goal_id", nullable = false)
    private Goal goal;

    @NotBlank @Size(max = 160)
    @Column(nullable = false, length = 160)
    private String name;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignee_access_id", nullable = false)
    private ProjectAccess assigneeAccess;

    @NotNull
    @Column(nullable = false)
    private Instant dueAt;

    @NotNull @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActivityStatus status = ActivityStatus.TODO;

    private Instant completedAt;

    public Activity(Goal goal, String name, ProjectAccess assigneeAccess, Instant dueAt) {
        this.goal = DomainValidation.required(goal, "Goal is required");
        rename(name);
        reschedule(dueAt);
        assignTo(assigneeAccess);
        goal.addActivity(this);
    }

    public void rename(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 160) {
            throw new IllegalArgumentException("Activity name must have 1 to 160 characters");
        }
        this.name = name.strip();
    }

    public void reschedule(Instant dueAt) {
        DomainValidation.required(dueAt, "Activity deadline is required");
        if (dueAt.isAfter(goal.getDueAt())) {
            throw new IllegalArgumentException("Activity cannot end after its goal");
        }
        this.dueAt = dueAt;
    }

    public void assignTo(ProjectAccess assigneeAccess) {
        DomainValidation.required(assigneeAccess, "Assignee access is required");
        if (!goal.getProject().sameAs(assigneeAccess.getProject())) {
            throw new IllegalArgumentException("Assignee has no access to this project");
        }
        if (assigneeAccess.getOrganizationMembership().getStatus()
                != org.andre.construx.modules.organization.model.MembershipStatus.ACTIVE) {
            throw new IllegalArgumentException("Assignee must be an active member");
        }
        this.assigneeAccess = assigneeAccess;
    }

    public void start() {
        if (status != ActivityStatus.TODO) {
            throw new IllegalStateException("Only pending activities can start");
        }
        status = ActivityStatus.IN_PROGRESS;
    }

    public void complete(Instant completedAt) {
        if (status == ActivityStatus.DONE) {
            throw new IllegalStateException("Activity is already completed");
        }
        this.completedAt = DomainValidation.required(completedAt, "Completion time is required");
        status = ActivityStatus.DONE;
    }
}
