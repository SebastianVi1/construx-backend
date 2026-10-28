package org.andre.construx.modules.project.model;

import org.andre.construx.shared.validation.DomainValidation;
import org.andre.construx.shared.validation.MoneyAmount;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Dynamic updates write only changed columns, so a budget change and a concurrent edit never undo each other. */
@Entity
@DynamicUpdate
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
    private Instant startAt;

    @NotNull
    @Column(nullable = false)
    private Instant dueAt;

    @NotNull @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActivityStatus status = ActivityStatus.TODO;

    private Instant completedAt;

    /** Bar color in the schedule as lowercase #rrggbb; null is the default color. */
    @Column(length = 7)
    private String color;

    /** Optional share of the phase budget; null means not set. */
    @Column(precision = 14, scale = 2)
    private BigDecimal allocatedBudget;

    public Activity(Goal goal, String name, ProjectAccess assigneeAccess, Instant startAt, Instant dueAt) {
        this.goal = DomainValidation.required(goal, "Goal is required");
        rename(name);
        changeDates(startAt, dueAt);
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
        changeDates(startAt, dueAt);
    }

    public void changeStartAt(Instant startAt) {
        changeDates(startAt, dueAt);
    }

    /** A task lies inside its phase; both dates are checked as a pair so a move is accepted whole. */
    public void changeDates(Instant startAt, Instant dueAt) {
        DomainValidation.required(startAt, "Activity start is required");
        DomainValidation.required(dueAt, "Activity deadline is required");
        if (startAt.isAfter(dueAt)) {
            throw new IllegalArgumentException("Activity cannot start after it ends");
        }
        if (startAt.isBefore(goal.getStartAt())) {
            throw new IllegalArgumentException("Activity cannot start before its goal");
        }
        if (dueAt.isAfter(goal.getDueAt())) {
            throw new IllegalArgumentException("Activity cannot end after its goal");
        }
        this.startAt = startAt;
        this.dueAt = dueAt;
    }

    /** Only the goal moves its tasks, after moving itself by the same offset. */
    void shiftBy(Duration offset) {
        startAt = startAt.plus(offset);
        dueAt = dueAt.plus(offset);
    }

    /** Null clears the custom color. */
    public void changeColor(String color) {
        this.color = BarColor.normalize(color);
    }

    /** Sets or, with null, clears the task budget; it must fit in what the phase has left after its other tasks. */
    public void assignBudget(BigDecimal amount, BigDecimal allocatedToOtherActivities) {
        BigDecimal checked = amount == null ? null : MoneyAmount.nonNegative(amount, "Allocated budget");
        BudgetAllocation.checkChild(goal.getAllocatedBudget(), allocatedToOtherActivities, checked);
        this.allocatedBudget = checked;
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
