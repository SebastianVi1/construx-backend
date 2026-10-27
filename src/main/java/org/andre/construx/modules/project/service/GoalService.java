package org.andre.construx.modules.project.service;

import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.MembershipGuard;
import org.andre.construx.modules.project.model.Goal;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.project.repository.ActivityRepository;
import org.andre.construx.modules.project.repository.GoalRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.security.DomainAuthorization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public GoalService(GoalRepository goals, ActivityRepository activities, ProjectService projects,
                       MembershipGuard guard, DomainAuthorization authorization) {
        this.goals = goals;
        this.activities = activities;
        this.projects = projects;
        this.guard = guard;
        this.authorization = authorization;
    }

    /** Counts are null when the caller cannot view the site's tasks. */
    public record GoalData(UUID id, UUID projectId, String name, Instant dueAt, Long activityCount, Long doneCount,
                           boolean completed) {}

    @Transactional
    public GoalData create(UUID userId, UUID organizationId, UUID projectId, String name, Instant dueAt) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        Project project = projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_GOAL);
        Goal goal = goals.save(new Goal(project, name, dueAt));
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

    /** Null arguments keep the stored value. */
    @Transactional
    public GoalData update(UUID userId, UUID organizationId, UUID projectId, UUID goalId, String name, Instant dueAt) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        Goal goal = load(organizationId, projectId, goalId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_GOAL);
        if (name != null) {
            goal.rename(name);
        }
        if (dueAt != null) {
            goal.changeDueAt(dueAt);
        }
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
        return list.stream().map(goal -> {
            long[] c = counts.getOrDefault(goal.getId(), new long[] {0, 0});
            return new GoalData(goal.getId(), projectId, goal.getName(), goal.getDueAt(),
                    visible ? c[0] : null, visible ? c[1] : null, c[0] > 0 && c[0] == c[1]);
        }).toList();
    }
}
