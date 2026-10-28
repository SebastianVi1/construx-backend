package org.andre.construx.modules.project.model;

import org.andre.construx.modules.organization.model.MembershipStatus;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    @OneToMany(mappedBy = "activity", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ActivityAssignee> team = new ArrayList<>();

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

    public Activity(Goal goal, String name, List<TeamMember> team, Instant startAt, Instant dueAt) {
        this.goal = DomainValidation.required(goal, "Goal is required");
        rename(name);
        changeDates(startAt, dueAt);
        assignTeam(team);
        goal.addActivity(this);
    }

    /** A task whose team is only its Lead. */
    public Activity(Goal goal, String name, ProjectAccess lead, Instant startAt, Instant dueAt) {
        this(goal, name, List.of(new TeamMember(lead, TaskRole.LEAD)), startAt, dueAt);
    }

    public List<ActivityAssignee> getTeam() {
        return Collections.unmodifiableList(team);
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

    /**
     * Replaces the whole team: at least one person, exactly one Lead, nobody twice, every member an active
     * person of this site. Members who stay keep their row and only change role, so the unique pair
     * (task, person) is never inserted twice in one flush.
     */
    public void assignTeam(List<TeamMember> members) {
        if (members == null || members.isEmpty()) {
            throw new IllegalArgumentException("A task needs at least one assignee");
        }
        Set<Object> seen = new HashSet<>();
        int leads = 0;
        for (TeamMember member : members) {
            DomainValidation.required(member, "Assignee is required");
            ProjectAccess access = DomainValidation.required(member.access(), "Assignee access is required");
            DomainValidation.required(member.taskRole(), "Task role is required");
            if (!goal.getProject().sameAs(access.getProject())) {
                throw new IllegalArgumentException("Assignee has no access to this project");
            }
            if (access.getOrganizationMembership().getStatus() != MembershipStatus.ACTIVE) {
                throw new IllegalArgumentException("Assignee must be an active member");
            }
            if (!seen.add(key(access))) {
                throw new IllegalArgumentException("A person can be in a task's team only once");
            }
            if (member.taskRole() == TaskRole.LEAD) {
                leads++;
            }
        }
        if (leads != 1) {
            throw new IllegalArgumentException("A task needs exactly one Lead");
        }
        team.removeIf(current -> members.stream().noneMatch(m -> key(m.access()).equals(key(current.getAccess()))));
        for (TeamMember member : members) {
            team.stream().filter(current -> key(current.getAccess()).equals(key(member.access()))).findFirst()
                    .ifPresentOrElse(current -> current.changeRole(member.taskRole()),
                            () -> team.add(new ActivityAssignee(this, member.access(), member.taskRole())));
        }
    }

    public boolean isOnTeam(UUID membershipId) {
        return membershipId != null && team.stream().anyMatch(a -> membershipId.equals(a.membershipId()));
    }

    public boolean isLead(UUID membershipId) {
        return membershipId != null && team.stream()
                .anyMatch(a -> a.getTaskRole() == TaskRole.LEAD && membershipId.equals(a.membershipId()));
    }

    /** Saved accesses compare by id; new ones (in unit tests) by identity. */
    private static Object key(ProjectAccess access) {
        return access.getId() != null ? access.getId() : access;
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
