package org.andre.construx.modules.project.service;

import org.andre.construx.modules.identity.model.UserProfile;
import org.andre.construx.modules.identity.repository.UserProfileRepository;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.service.MembershipGuard;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.project.model.ProjectAccess;
import org.andre.construx.modules.project.repository.ActivityRepository;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.security.DomainAuthorization;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Who works on a site. Tasks can only be assigned to people with access to the site. */
@Service
public class ProjectAccessService {
    private final ProjectAccessRepository accesses;
    private final ActivityRepository activities;
    private final OrganizationMembershipRepository memberships;
    private final UserProfileRepository profiles;
    private final ProjectService projects;
    private final MembershipGuard guard;
    private final DomainAuthorization authorization;

    public ProjectAccessService(ProjectAccessRepository accesses, ActivityRepository activities,
                                OrganizationMembershipRepository memberships, UserProfileRepository profiles,
                                ProjectService projects, MembershipGuard guard, DomainAuthorization authorization) {
        this.accesses = accesses;
        this.activities = activities;
        this.memberships = memberships;
        this.profiles = profiles;
        this.projects = projects;
        this.guard = guard;
        this.authorization = authorization;
    }

    /** No email: workers can read this list. */
    public record AccessData(UUID accessId, UUID membershipId, UUID userId, String fullName,
                             String roleCode, String roleName) {}

    @Transactional(readOnly = true)
    public List<AccessData> list(UUID userId, UUID organizationId, UUID projectId) {
        guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.VIEW_PROJECT);
        List<ProjectAccess> list = accesses.findActiveByProject(projectId);
        Map<UUID, String> names = profiles.findAllById(list.stream()
                        .map(a -> a.getOrganizationMembership().getUserAccount().getId()).toList()).stream()
                .collect(Collectors.toMap(UserProfile::getId, UserProfile::getFullName));
        return list.stream().map(a -> toData(a, names)).toList();
    }

    @Transactional
    public AccessData add(UUID userId, UUID organizationId, UUID projectId, UUID membershipId) {
        guard.requireActive(userId, organizationId);
        Project project = projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_PROJECT);
        OrganizationMembership membership = memberships.findByIdAndOrganizationId(membershipId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
        if (accesses.existsByProjectIdAndOrganizationMembershipId(projectId, membershipId)) {
            throw new DataIntegrityViolationException("Member already has access to this site");
        }
        ProjectAccess access = accesses.save(new ProjectAccess(project, membership));
        UUID memberUserId = membership.getUserAccount().getId();
        Map<UUID, String> names = profiles.findById(memberUserId)
                .map(p -> Map.of(memberUserId, p.getFullName())).orElse(Map.of());
        return toData(access, names);
    }

    @Transactional
    public void remove(UUID userId, UUID organizationId, UUID projectId, UUID accessId) {
        guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_PROJECT);
        ProjectAccess access = accesses.findByIdAndProjectId(accessId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Site access not found"));
        if (activities.existsInAnyTeam(accessId)) {
            throw new IllegalStateException("Take this person off their task teams before removing them from the site");
        }
        accesses.delete(access);
    }

    /** Resolves a site access inside the tenant and site, for task assignment. */
    public ProjectAccess load(UUID projectId, UUID accessId) {
        return accesses.findByIdAndProjectId(accessId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Site access not found"));
    }

    private static AccessData toData(ProjectAccess access, Map<UUID, String> names) {
        OrganizationMembership membership = access.getOrganizationMembership();
        UUID memberUserId = membership.getUserAccount().getId();
        return new AccessData(access.getId(), membership.getId(), memberUserId, names.get(memberUserId),
                membership.getRole().getCode(), membership.getRole().getName());
    }
}
