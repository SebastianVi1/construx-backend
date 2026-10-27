package org.andre.construx.modules.project.service;

import org.andre.construx.modules.identity.model.UserProfile;
import org.andre.construx.modules.identity.repository.UserProfileRepository;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.model.PermissionScope;
import org.andre.construx.modules.organization.service.MembershipGuard;
import org.andre.construx.modules.project.model.Activity;
import org.andre.construx.modules.project.model.ActivityStatus;
import org.andre.construx.modules.project.model.Goal;
import org.andre.construx.modules.project.model.ProjectAccess;
import org.andre.construx.modules.project.repository.ActivityRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.security.DomainAuthorization;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tasks. Start and complete pass the assignee to the permission check, so a role with
 * MANAGE_ACTIVITY at OWN scope (Worker) can only move its own tasks; every other change passes
 * no assignee, which makes OWN scope deny.
 */
@Service
public class ActivityService {
    private final ActivityRepository activities;
    private final GoalService goals;
    private final ProjectAccessService accesses;
    private final UserProfileRepository profiles;
    private final MembershipGuard guard;
    private final DomainAuthorization authorization;

    public ActivityService(ActivityRepository activities, GoalService goals, ProjectAccessService accesses,
                           UserProfileRepository profiles, MembershipGuard guard, DomainAuthorization authorization) {
        this.activities = activities;
        this.goals = goals;
        this.accesses = accesses;
        this.profiles = profiles;
        this.guard = guard;
        this.authorization = authorization;
    }

    public record AssigneeData(UUID accessId, UUID membershipId, String fullName) {}

    /** Site and phase names let cross-site lists (my tasks) label rows without extra calls. */
    public record ActivityData(UUID id, UUID goalId, String goalName, UUID projectId, String projectName,
                               String name, Instant dueAt, ActivityStatus status, Instant completedAt,
                               AssigneeData assignee) {}

    @Transactional
    public ActivityData create(UUID userId, UUID organizationId, UUID projectId, UUID goalId,
                               String name, Instant dueAt, UUID assigneeAccessId) {
        guard.requireActive(userId, organizationId);
        Goal goal = goals.load(organizationId, projectId, goalId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_ACTIVITY);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.ASSIGN_ACTIVITY);
        ProjectAccess assignee = accesses.load(projectId, assigneeAccessId);
        return toData(List.of(activities.save(new Activity(goal, name, assignee, goal.getStartAt(), dueAt)))).get(0);
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
        return toData(activities.findByGoalWithAssignee(goalId).stream()
                .filter(a -> scope != PermissionScope.OWN || assigneeMembership(a).equals(member.getId()))
                .filter(a -> status == null || a.getStatus() == status)
                .filter(a -> assigneeAccessId == null || a.getAssigneeAccess().getId().equals(assigneeAccessId))
                .toList());
    }

    @Transactional(readOnly = true)
    public ActivityData get(UUID userId, UUID organizationId, UUID projectId, UUID goalId, UUID activityId) {
        guard.requireActive(userId, organizationId);
        Activity activity = load(organizationId, projectId, goalId, activityId);
        authorization.require(userId, organizationId, projectId, assigneeMembership(activity),
                PermissionAction.VIEW_ACTIVITY);
        return toData(List.of(activity)).get(0);
    }

    /** Null arguments keep the stored value. Not for status or assignee. */
    @Transactional
    public ActivityData update(UUID userId, UUID organizationId, UUID projectId, UUID goalId, UUID activityId,
                               String name, Instant dueAt) {
        guard.requireActive(userId, organizationId);
        Activity activity = load(organizationId, projectId, goalId, activityId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_ACTIVITY);
        if (name != null) {
            activity.rename(name);
        }
        if (dueAt != null) {
            activity.reschedule(dueAt);
        }
        return toData(List.of(activity)).get(0);
    }

    @Transactional
    public ActivityData reassign(UUID userId, UUID organizationId, UUID projectId, UUID goalId, UUID activityId,
                                 UUID assigneeAccessId) {
        guard.requireActive(userId, organizationId);
        Activity activity = load(organizationId, projectId, goalId, activityId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.ASSIGN_ACTIVITY);
        activity.assignTo(accesses.load(projectId, assigneeAccessId));
        return toData(List.of(activity)).get(0);
    }

    @Transactional
    public ActivityData start(UUID userId, UUID organizationId, UUID projectId, UUID goalId, UUID activityId) {
        guard.requireActive(userId, organizationId);
        Activity activity = load(organizationId, projectId, goalId, activityId);
        authorization.require(userId, organizationId, projectId, assigneeMembership(activity),
                PermissionAction.MANAGE_ACTIVITY);
        activity.start();
        return toData(List.of(activity)).get(0);
    }

    @Transactional
    public ActivityData complete(UUID userId, UUID organizationId, UUID projectId, UUID goalId, UUID activityId) {
        guard.requireActive(userId, organizationId);
        Activity activity = load(organizationId, projectId, goalId, activityId);
        authorization.require(userId, organizationId, projectId, assigneeMembership(activity),
                PermissionAction.MANAGE_ACTIVITY);
        activity.complete(Instant.now());
        return toData(List.of(activity)).get(0);
    }

    @Transactional
    public void delete(UUID userId, UUID organizationId, UUID projectId, UUID goalId, UUID activityId) {
        guard.requireActive(userId, organizationId);
        Activity activity = load(organizationId, projectId, goalId, activityId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_ACTIVITY);
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
        return toData(activities.findAssignedTo(organizationId, member.getId()).stream()
                .filter(a -> status == null || a.getStatus() == status)
                .toList());
    }

    private Activity load(UUID organizationId, UUID projectId, UUID goalId, UUID activityId) {
        goals.load(organizationId, projectId, goalId);
        return activities.findByIdAndGoalId(activityId, goalId)
                .orElseThrow(() -> new ResourceNotFoundException("Activity not found"));
    }

    private static UUID assigneeMembership(Activity activity) {
        return activity.getAssigneeAccess().getOrganizationMembership().getId();
    }

    private List<ActivityData> toData(List<Activity> list) {
        Map<UUID, String> names = profiles.findAllById(list.stream()
                        .map(a -> a.getAssigneeAccess().getOrganizationMembership().getUserAccount().getId())
                        .distinct().toList()).stream()
                .collect(Collectors.toMap(UserProfile::getId, UserProfile::getFullName));
        return list.stream().map(a -> {
            ProjectAccess access = a.getAssigneeAccess();
            OrganizationMembership membership = access.getOrganizationMembership();
            return new ActivityData(a.getId(), a.getGoal().getId(), a.getGoal().getName(),
                    a.getGoal().getProject().getId(), a.getGoal().getProject().getName(), a.getName(),
                    a.getDueAt(), a.getStatus(), a.getCompletedAt(), new AssigneeData(access.getId(),
                    membership.getId(), names.get(membership.getUserAccount().getId())));
        }).toList();
    }
}
