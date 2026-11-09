package org.andre.construx.modules.project.service;

import org.andre.construx.modules.identity.service.PersonDirectory;
import org.andre.construx.modules.identity.service.PersonDirectory.Person;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.model.PermissionScope;
import org.andre.construx.modules.organization.service.MembershipGuard;
import org.andre.construx.modules.project.model.Activity;
import org.andre.construx.modules.project.model.ActivityStatus;
import org.andre.construx.modules.project.model.Goal;
import org.andre.construx.modules.project.model.TaskRole;
import org.andre.construx.modules.project.model.TeamMember;
import org.andre.construx.modules.project.repository.ActivityRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.security.DomainAuthorization;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tasks. Start, complete and view pass the caller to the permission check when the caller is in the task's
 * team, so a role with OWN scope (Worker) can only move tasks it works on; every other change passes no
 * assignee, which makes OWN scope deny.
 */
@Service
public class ActivityService {
    private final ActivityRepository activities;
    private final GoalService goals;
    private final ProjectService projects;
    private final ProjectAccessService accesses;
    private final PersonDirectory directory;
    private final MembershipGuard guard;
    private final DomainAuthorization authorization;

    public ActivityService(ActivityRepository activities, GoalService goals, ProjectService projects,
                           ProjectAccessService accesses, PersonDirectory directory, MembershipGuard guard,
                           DomainAuthorization authorization) {
        this.activities = activities;
        this.goals = goals;
        this.projects = projects;
        this.accesses = accesses;
        this.directory = directory;
        this.guard = guard;
        this.authorization = authorization;
    }

    public record AssigneeData(UUID accessId, UUID membershipId, String fullName, TaskRole taskRole, URI avatarUrl) {}

    /** A requested team place by site access id. */
    public record TeamInput(UUID accessId, TaskRole taskRole) {}

    /**
     * Site and phase names let cross-site lists (my tasks) label rows without extra calls. {@code allocatedBudget}
     * is null without VIEW_BUDGET.
     */
    public record ActivityData(UUID id, UUID goalId, String goalName, UUID projectId, String projectName,
                               String name, String description, Instant startAt, Instant dueAt, String color, ActivityStatus status, Instant completedAt,
                               List<AssigneeData> assignees, BigDecimal allocatedBudget) {}

    /** An unfinished task of a site, as other modules show it next to a person. */
    public record OpenTask(UUID id, UUID projectId, UUID goalId, String name, String goalName, ActivityStatus status,
                           TaskRole taskRole, Instant dueAt) {}

    /**
     * Unfinished tasks of the site per membership in their team, soonest due first; memberships without any are
     * absent. One query. No permission check: callers decide whose tasks the viewer may see.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<OpenTask>> openTasksOf(UUID projectId, Collection<UUID> membershipIds) {
        if (membershipIds.isEmpty()) return Map.of();
        Map<UUID, List<OpenTask>> byMembership = new LinkedHashMap<>();
        for (Object[] row : activities.findOpenTasksOf(projectId, membershipIds.stream().distinct().toList())) {
            byMembership.computeIfAbsent((UUID) row[0], k -> new ArrayList<>()).add(new OpenTask((UUID) row[1],
                    projectId, (UUID) row[2], (String) row[3], (String) row[4], (ActivityStatus) row[5],
                    (TaskRole) row[6], (Instant) row[7]));
        }
        return byMembership;
    }

    @Transactional
    public ActivityData create(UUID userId, UUID organizationId, UUID projectId, UUID goalId,
                               String name, Instant startAt, Instant dueAt, List<TeamInput> team, String color, String description) {
        guard.requireActive(userId, organizationId);
        Goal goal = goals.load(organizationId, projectId, goalId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_ACTIVITY);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.ASSIGN_ACTIVITY);
        Activity activity = new Activity(goal, name, resolve(projectId, team), startAt, dueAt);
        activity.changeColor(GoalService.blankAsNull(color));
        if (description != null) activity.describe(description);
        return toData(userId, organizationId, List.of(activities.save(activity))).get(0);
    }

    @Transactional(readOnly = true)
    public List<ActivityData> list(UUID userId, UUID organizationId, UUID projectId, UUID goalId,
                                   ActivityStatus status, UUID assigneeAccessId) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        goals.load(organizationId, projectId, goalId);
        PermissionScope scope = authorization.scopeOf(userId, organizationId, PermissionAction.VIEW_ACTIVITY)
                .orElseThrow(() -> new AccessDeniedException("Permission required"));
        if (scope != PermissionScope.OWN) {
            authorization.require(userId, organizationId, projectId, null, PermissionAction.VIEW_ACTIVITY);
        }
        return toData(userId, organizationId, activities.findByGoalWithTeam(goalId).stream()
                .filter(a -> scope != PermissionScope.OWN || a.isOnTeam(member.getId()))
                .filter(a -> status == null || a.getStatus() == status)
                .filter(a -> assigneeAccessId == null || a.getTeam().stream()
                        .anyMatch(t -> t.getAccess().getId().equals(assigneeAccessId)))
                .toList());
    }

    @Transactional(readOnly = true)
    public ActivityData get(UUID userId, UUID organizationId, UUID projectId, UUID goalId, UUID activityId) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        Activity activity = load(organizationId, projectId, goalId, activityId);
        authorization.require(userId, organizationId, projectId, teamMember(activity, member),
                PermissionAction.VIEW_ACTIVITY);
        return toData(userId, organizationId, List.of(activity)).get(0);
    }

    /** Null arguments keep the stored value; an empty color or description goes back to the default. Not for status or team. */
    @Transactional
    public ActivityData update(UUID userId, UUID organizationId, UUID projectId, UUID goalId, UUID activityId,
                               String name, Instant startAt, Instant dueAt, String color, String description) {
        guard.requireActive(userId, organizationId);
        Activity activity = load(organizationId, projectId, goalId, activityId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_ACTIVITY);
        if (name != null) {
            activity.rename(name);
        }
        if (startAt != null || dueAt != null) {
            activity.changeDates(startAt != null ? startAt : activity.getStartAt(),
                    dueAt != null ? dueAt : activity.getDueAt());
        }
        if (color != null) {
            activity.changeColor(GoalService.blankAsNull(color));
        }
        if (description != null) {
            activity.describe(description);
        }
        return toData(userId, organizationId, List.of(activity)).get(0);
    }

    /** Replaces the whole team. Requires ASSIGN_ACTIVITY; being the task's Lead is not enough. */
    @Transactional
    public ActivityData assignTeam(UUID userId, UUID organizationId, UUID projectId, UUID goalId, UUID activityId,
                                   List<TeamInput> team) {
        guard.requireActive(userId, organizationId);
        Activity activity = load(organizationId, projectId, goalId, activityId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.ASSIGN_ACTIVITY);
        // The team is still unloaded here, so it is read after the lock and sees the previous change.
        activities.lockById(activityId);
        activity.assignTeam(resolve(projectId, team));
        return toData(userId, organizationId, List.of(activity)).get(0);
    }

    @Transactional
    public ActivityData start(UUID userId, UUID organizationId, UUID projectId, UUID goalId, UUID activityId) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        Activity activity = load(organizationId, projectId, goalId, activityId);
        authorization.require(userId, organizationId, projectId, teamMember(activity, member),
                PermissionAction.MANAGE_ACTIVITY);
        activity.start();
        return toData(userId, organizationId, List.of(activity)).get(0);
    }

    @Transactional
    public ActivityData complete(UUID userId, UUID organizationId, UUID projectId, UUID goalId, UUID activityId) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        Activity activity = load(organizationId, projectId, goalId, activityId);
        authorization.require(userId, organizationId, projectId, teamMember(activity, member),
                PermissionAction.MANAGE_ACTIVITY);
        activity.complete(Instant.now());
        return toData(userId, organizationId, List.of(activity)).get(0);
    }

    /** Sets or, with null, clears the task budget. Requires MANAGE_BUDGET; the phase row lock serializes changes. */
    @Transactional
    public ActivityData assignBudget(UUID userId, UUID organizationId, UUID projectId, UUID goalId, UUID activityId,
                                     BigDecimal amount) {
        guard.requireActive(userId, organizationId);
        Activity activity = load(organizationId, projectId, goalId, activityId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_BUDGET);
        goals.lockForBudget(organizationId, projectId, goalId);
        activity.assignBudget(amount, activities.sumBudgetOfOtherActivities(goalId, activityId));
        return toData(userId, organizationId, List.of(activity)).get(0);
    }

    @Transactional
    public void delete(UUID userId, UUID organizationId, UUID projectId, UUID goalId, UUID activityId) {
        guard.requireActive(userId, organizationId);
        Activity activity = load(organizationId, projectId, goalId, activityId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_ACTIVITY);
        if (activities.hasExpenses(activityId)) {
            throw new IllegalStateException("Remove this task's expenses before deleting it");
        }
        activities.delete(activity);
    }

    /** Home screen for anyone with a task permission: their own tasks in this organization. */
    @Transactional(readOnly = true)
    public List<ActivityData> mine(UUID userId, UUID organizationId, ActivityStatus status) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        if (authorization.scopeOf(userId, organizationId, PermissionAction.VIEW_ACTIVITY).isEmpty()
                && authorization.scopeOf(userId, organizationId, PermissionAction.MANAGE_ACTIVITY).isEmpty()) {
            throw new AccessDeniedException("Permission required");
        }
        return toData(userId, organizationId, activities.findAssignedTo(organizationId, member.getId()).stream()
                .filter(a -> status == null || a.getStatus() == status)
                .toList());
    }

    /** Resolves a task inside the tenant, site and phase (404 otherwise), for the other modules. */
    public Activity load(UUID organizationId, UUID projectId, UUID goalId, UUID activityId) {
        goals.load(organizationId, projectId, goalId);
        return activities.findByIdAndGoalId(activityId, goalId)
                .orElseThrow(() -> new ResourceNotFoundException("Activity not found"));
    }

    /** The caller's membership when they are in the team, which is what OWN scope matches; null otherwise. */
    private static UUID teamMember(Activity activity, OrganizationMembership member) {
        return activity.isOnTeam(member.getId()) ? member.getId() : null;
    }

    /** Each access is looked up inside this site, so a foreign id is a 404. */
    private List<TeamMember> resolve(UUID projectId, List<TeamInput> team) {
        if (team == null) {
            return List.of();
        }
        return team.stream().map(t -> new TeamMember(accesses.load(projectId, t.accessId()), t.taskRole())).toList();
    }

    private List<ActivityData> toData(UUID userId, UUID organizationId, List<Activity> list) {
        boolean budgetVisible = projects.canViewBudget(userId, organizationId);
        Map<UUID, Person> people = directory.byUserId(list.stream()
                .flatMap(a -> a.getTeam().stream())
                .map(t -> t.getAccess().getOrganizationMembership().getUserAccount().getId())
                .toList());
        return list.stream().map(a -> new ActivityData(a.getId(), a.getGoal().getId(), a.getGoal().getName(),
                a.getGoal().getProject().getId(), a.getGoal().getProject().getName(), a.getName(),
                a.getDescription(),
                a.getStartAt(), a.getDueAt(), a.getColor(), a.getStatus(), a.getCompletedAt(), team(a, people),
                budgetVisible ? a.getAllocatedBudget() : null)).toList();
    }

    /** Most senior first, then by name, so every screen lists a team the same way. */
    private static List<AssigneeData> team(Activity activity, Map<UUID, Person> people) {
        return activity.getTeam().stream().map(t -> {
            OrganizationMembership membership = t.getAccess().getOrganizationMembership();
            Person person = PersonDirectory.of(people, membership.getUserAccount().getId());
            return new AssigneeData(t.getAccess().getId(), membership.getId(),
                    person.fullName(), t.getTaskRole(), person.avatarUrl());
        }).sorted(Comparator.comparing(AssigneeData::taskRole)
                .thenComparing(AssigneeData::fullName, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
    }
}
