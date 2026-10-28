package org.andre.construx.modules.project.service;

import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.MembershipGuard;
import org.andre.construx.modules.project.model.BudgetAllocation;
import org.andre.construx.modules.project.model.Goal;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.project.repository.ActivityRepository;
import org.andre.construx.modules.project.repository.GoalRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.security.DomainAuthorization;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Phases of a site. Tasks live inside a phase. */
@Service
public class GoalService {
    private final GoalRepository goals;
    private final ActivityRepository activities;
    private final ProjectService projects;
    private final MembershipGuard guard;
    private final DomainAuthorization authorization;
    private final EntityManager entityManager;

    public GoalService(GoalRepository goals, ActivityRepository activities, ProjectService projects,
                       MembershipGuard guard, DomainAuthorization authorization, EntityManager entityManager) {
        this.goals = goals;
        this.activities = activities;
        this.projects = projects;
        this.guard = guard;
        this.authorization = authorization;
        this.entityManager = entityManager;
    }

    /**
     * Counts are null when the caller cannot view the site's tasks. The budget fields are null without
     * VIEW_BUDGET; {@code unallocated} is what the phase has left for its tasks, null when it has no budget.
     */
    public record GoalData(UUID id, UUID projectId, String name, Instant startAt, Instant dueAt, String color, Long activityCount, Long doneCount,
                           boolean completed, BigDecimal allocatedBudget, BigDecimal allocatedToActivities,
                           BigDecimal unallocated) {}

    @Transactional
    public GoalData create(UUID userId, UUID organizationId, UUID projectId, String name, Instant startAt,
                           Instant dueAt, String color) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        Project project = projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_GOAL);
        Goal goal = new Goal(project, name, startAt, dueAt);
        goal.changeColor(blankAsNull(color));
        goals.save(goal);
        return toData(userId, organizationId, member, projectId, List.of(goal)).get(0);
    }

    @Transactional(readOnly = true)
    public List<GoalData> list(UUID userId, UUID organizationId, UUID projectId) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.VIEW_GOAL);
        return toData(userId, organizationId, member, projectId, goals.findByProjectIdOrderByDueAtAsc(projectId));
    }

    @Transactional(readOnly = true)
    public GoalData get(UUID userId, UUID organizationId, UUID projectId, UUID goalId) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        Goal goal = load(organizationId, projectId, goalId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.VIEW_GOAL);
        return toData(userId, organizationId, member, projectId, List.of(goal)).get(0);
    }

    /** Null arguments keep the stored value; an empty color goes back to the default. */
    @Transactional
    public GoalData update(UUID userId, UUID organizationId, UUID projectId, UUID goalId, String name,
                           Instant startAt, Instant dueAt, String color) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        Goal goal = load(organizationId, projectId, goalId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_GOAL);
        if (name != null) {
            goal.rename(name);
        }
        if (startAt != null || dueAt != null) {
            goal.changeDates(startAt != null ? startAt : goal.getStartAt(), dueAt != null ? dueAt : goal.getDueAt());
        }
        if (color != null) {
            goal.changeColor(blankAsNull(color));
        }
        return toData(userId, organizationId, member, projectId, List.of(goal)).get(0);
    }

    /**
     * Sets or, with null, clears the phase budget. Requires MANAGE_BUDGET. Locks the site, then the phase, the
     * same order every budget change takes, so concurrent changes cannot break the allocation cap.
     */
    @Transactional
    public GoalData assignBudget(UUID userId, UUID organizationId, UUID projectId, UUID goalId, BigDecimal amount) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        load(organizationId, projectId, goalId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_BUDGET);
        projects.lockForBudget(organizationId, projectId);
        Goal goal = lockForBudget(organizationId, projectId, goalId);
        BigDecimal current = goal.getAllocatedBudget() == null ? BigDecimal.ZERO : goal.getAllocatedBudget();
        goal.assignBudget(amount, projects.allocatedToPhases(projectId).subtract(current),
                activities.sumBudgetOfGoal(goalId));
        return toData(userId, organizationId, member, projectId, List.of(goal)).get(0);
    }

    /** Loads the phase with fresh state under a row lock; task budget changes inside it run one at a time. */
    public Goal lockForBudget(UUID organizationId, UUID projectId, UUID goalId) {
        Goal goal = load(organizationId, projectId, goalId);
        entityManager.refresh(goal, LockModeType.PESSIMISTIC_WRITE);
        return goal;
    }

    /** Moves the phase and every task inside it in one transaction. */
    @Transactional
    public GoalData shift(UUID userId, UUID organizationId, UUID projectId, UUID goalId, int days) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        Goal goal = load(organizationId, projectId, goalId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_GOAL);
        goal.shift(Duration.ofDays(days));
        return toData(userId, organizationId, member, projectId, List.of(goal)).get(0);
    }

    @Transactional
    public void delete(UUID userId, UUID organizationId, UUID projectId, UUID goalId) {
        guard.requireActive(userId, organizationId);
        Goal goal = load(organizationId, projectId, goalId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_GOAL);
        if (activities.existsByGoalId(goalId)) {
            throw new IllegalStateException("Move or delete this phase's tasks first");
        }
        goals.delete(goal);
    }

    static String blankAsNull(String color) {
        return color == null || color.isEmpty() ? null : color;
    }

    /** Resolves a phase inside the tenant and site. */
    public Goal load(UUID organizationId, UUID projectId, UUID goalId) {
        projects.load(organizationId, projectId);
        return goals.findByIdAndProjectId(goalId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Goal not found"));
    }

    private List<GoalData> toData(UUID userId, UUID organizationId, OrganizationMembership member, UUID projectId,
                                  List<Goal> list) {
        Map<UUID, long[]> counts = new HashMap<>();
        for (Object[] row : activities.countByGoal(projectId)) {
            counts.put((UUID) row[0], new long[] {((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
        }
        boolean visible = projects.canViewActivities(userId, organizationId, member.getId(), projectId);
        boolean budgetVisible = projects.canViewBudget(userId, organizationId);
        Map<UUID, BigDecimal> allocated = new HashMap<>();
        if (budgetVisible) {
            for (Object[] row : activities.sumBudgetByGoal(projectId)) {
                allocated.put((UUID) row[0], (BigDecimal) row[1]);
            }
        }
        return list.stream().map(goal -> {
            long[] c = counts.getOrDefault(goal.getId(), new long[] {0, 0});
            BigDecimal toActivities = allocated.getOrDefault(goal.getId(), BigDecimal.ZERO).setScale(2);
            return new GoalData(goal.getId(), projectId, goal.getName(), goal.getStartAt(), goal.getDueAt(), goal.getColor(),
                    visible ? c[0] : null, visible ? c[1] : null, c[0] > 0 && c[0] == c[1],
                    budgetVisible ? goal.getAllocatedBudget() : null, budgetVisible ? toActivities : null,
                    budgetVisible ? BudgetAllocation.unallocated(goal.getAllocatedBudget(), toActivities) : null);
        }).toList();
    }
}
