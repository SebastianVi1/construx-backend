package org.andre.construx.modules.project.service;

import org.andre.construx.modules.identity.service.PersonDirectory;
import org.andre.construx.modules.identity.service.PersonDirectory.Person;
import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.model.PermissionScope;
import org.andre.construx.modules.project.access.SiteScope;
import org.andre.construx.modules.project.model.Activity;
import org.andre.construx.modules.project.model.ActivityStatus;
import org.andre.construx.modules.project.model.Goal;
import org.andre.construx.modules.project.model.TaskRole;
import org.andre.construx.modules.project.model.TeamMember;
import org.andre.construx.modules.project.repository.ActivityRepository;
import org.andre.construx.modules.project.repository.GoalRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
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
    private final GoalRepository goalRepository;
    private final GoalService goals;
    private final ProjectAccessService accesses;
    private final PersonDirectory directory;

    public ActivityService(ActivityRepository activities, GoalRepository goalRepository, GoalService goals,
                           ProjectAccessService accesses, PersonDirectory directory) {
        this.activities = activities;
        this.goalRepository = goalRepository;
        this.goals = goals;
        this.accesses = accesses;
        this.directory = directory;
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

    /** A phase with its tasks, for printed summaries. */
    public record PhaseOverview(String name, Instant startAt, Instant dueAt, List<TaskOverview> tasks) {}

    /** {@code leadName} is null when the task has no Lead or the Lead has no profile name. */
    public record TaskOverview(String name, ActivityStatus status, UUID leadMembershipId, String leadName,
                               Instant startAt, Instant dueAt) {}

    /**
     * Every phase of the site, soonest due first, with its tasks and their Lead. No permission check: callers
     * authorize.
     */
    @Transactional(readOnly = true)
    public List<PhaseOverview> overviewOf(UUID projectId) {
        Map<Goal, List<Activity>> byGoal = new LinkedHashMap<>();
        for (Goal goal : goalRepository.findByProjectIdOrderByDueAtAsc(projectId)) {
            byGoal.put(goal, activities.findByGoalWithTeam(goal.getId()));
        }
        Map<UUID, Person> people = directory.byUserId(byGoal.values().stream().flatMap(List::stream)
                .flatMap(a -> a.getTeam().stream())
                .map(t -> t.getAccess().getOrganizationMembership().getUserAccount().getId())
                .toList());
        return byGoal.entrySet().stream().map(e -> new PhaseOverview(e.getKey().getName(), e.getKey().getStartAt(),
                e.getKey().getDueAt(), e.getValue().stream().map(a -> {
                    OrganizationMembership lead = a.getTeam().stream()
                            .filter(t -> t.getTaskRole() == TaskRole.LEAD).findFirst()
                            .map(t -> t.getAccess().getOrganizationMembership()).orElse(null);
                    return new TaskOverview(a.getName(), a.getStatus(), lead == null ? null : lead.getId(),
                            lead == null ? null : PersonDirectory.of(people, lead.getUserAccount().getId()).fullName(),
                            a.getStartAt(), a.getDueAt());
                }).toList())).toList();
    }

    @Transactional
    public ActivityData create(SiteScope site, UUID goalId, String name, Instant startAt, Instant dueAt,
                               List<TeamInput> team, String color, String description) {
        Goal goal = goals.load(site, goalId);
        site.require(PermissionAction.MANAGE_ACTIVITY);
        site.require(PermissionAction.ASSIGN_ACTIVITY);
        Activity activity = new Activity(goal, name, resolve(site.siteId(), team), startAt, dueAt);
        activity.changeColor(GoalService.blankAsNull(color));
        if (description != null) activity.describe(description);
        return toData(site.org(), List.of(activities.save(activity))).get(0);
    }

    @Transactional(readOnly = true)
    public List<ActivityData> list(SiteScope site, UUID goalId, ActivityStatus status, UUID assigneeAccessId) {
        goals.load(site, goalId);
        PermissionScope scope = site.scopeOf(PermissionAction.VIEW_ACTIVITY)
                .orElseThrow(() -> new AccessDeniedException("Permission required"));
        if (scope != PermissionScope.OWN) {
            site.require(PermissionAction.VIEW_ACTIVITY);
        }
        UUID membershipId = site.membershipId();
        return toData(site.org(), activities.findByGoalWithTeam(goalId).stream()
                .filter(a -> scope != PermissionScope.OWN || a.isOnTeam(membershipId))
                .filter(a -> status == null || a.getStatus() == status)
                .filter(a -> assigneeAccessId == null || a.getTeam().stream()
                        .anyMatch(t -> t.getAccess().getId().equals(assigneeAccessId)))
                .toList());
    }

    @Transactional(readOnly = true)
    public ActivityData get(SiteScope site, UUID goalId, UUID activityId) {
        Activity activity = load(site, goalId, activityId);
        site.require(PermissionAction.VIEW_ACTIVITY, teamMember(activity, site.membership()));
        return toData(site.org(), List.of(activity)).get(0);
    }

    /** Null arguments keep the stored value; an empty color or description goes back to the default. Not for status or team. */
    @Transactional
    public ActivityData update(SiteScope site, UUID goalId, UUID activityId,
                               String name, Instant startAt, Instant dueAt, String color, String description) {
        Activity activity = load(site, goalId, activityId);
        site.require(PermissionAction.MANAGE_ACTIVITY);
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
        return toData(site.org(), List.of(activity)).get(0);
    }

    /** Replaces the whole team. Requires ASSIGN_ACTIVITY; being the task's Lead is not enough. */
    @Transactional
    public ActivityData assignTeam(SiteScope site, UUID goalId, UUID activityId, List<TeamInput> team) {
        Activity activity = load(site, goalId, activityId);
        site.require(PermissionAction.ASSIGN_ACTIVITY);
        // The team is still unloaded here, so it is read after the lock and sees the previous change.
        activities.lockById(activityId);
        activity.assignTeam(resolve(site.siteId(), team));
        return toData(site.org(), List.of(activity)).get(0);
    }

    @Transactional
    public ActivityData start(SiteScope site, UUID goalId, UUID activityId) {
        Activity activity = load(site, goalId, activityId);
        site.require(PermissionAction.MANAGE_ACTIVITY, teamMember(activity, site.membership()));
        activity.start();
        return toData(site.org(), List.of(activity)).get(0);
    }

    @Transactional
    public ActivityData complete(SiteScope site, UUID goalId, UUID activityId) {
        Activity activity = load(site, goalId, activityId);
        site.require(PermissionAction.MANAGE_ACTIVITY, teamMember(activity, site.membership()));
        activity.complete(Instant.now());
        return toData(site.org(), List.of(activity)).get(0);
    }

    /** Sets or, with null, clears the task budget. Requires MANAGE_BUDGET; the phase row lock serializes changes. */
    @Transactional
    public ActivityData assignBudget(SiteScope site, UUID goalId, UUID activityId, BigDecimal amount) {
        Activity activity = load(site, goalId, activityId);
        site.require(PermissionAction.MANAGE_BUDGET);
        goals.lockForBudget(site, goalId);
        activity.assignBudget(amount, activities.sumBudgetOfOtherActivities(goalId, activityId));
        return toData(site.org(), List.of(activity)).get(0);
    }

    @Transactional
    public void delete(SiteScope site, UUID goalId, UUID activityId) {
        Activity activity = load(site, goalId, activityId);
        site.require(PermissionAction.MANAGE_ACTIVITY);
        if (activities.hasExpenses(activityId)) {
            throw new IllegalStateException("Remove this task's expenses before deleting it");
        }
        activities.delete(activity);
    }

    /** Home screen for anyone with a task permission: their own tasks in this organization. */
    @Transactional(readOnly = true)
    public List<ActivityData> mine(OrgScope org, ActivityStatus status) {
        if (org.scopeOf(PermissionAction.VIEW_ACTIVITY).isEmpty()
                && org.scopeOf(PermissionAction.MANAGE_ACTIVITY).isEmpty()) {
            throw new AccessDeniedException("Permission required");
        }
        return toData(org, activities.findAssignedTo(org.organizationId(), org.membershipId()).stream()
                .filter(a -> status == null || a.getStatus() == status)
                .toList());
    }

    /** Resolves a task inside the scope's site and phase (404 otherwise), for the other modules. */
    public Activity load(SiteScope site, UUID goalId, UUID activityId) {
        goals.load(site, goalId);
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

    private List<ActivityData> toData(OrgScope org, List<Activity> list) {
        boolean budgetVisible = ProjectService.canViewBudget(org);
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
