package org.andre.construx.modules.project.service;

import org.andre.construx.modules.identity.service.PersonDirectory;
import org.andre.construx.modules.identity.service.PersonDirectory.Person;
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

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Who works on a site. Tasks can only be assigned to people with access to the site. */
@Service
public class ProjectAccessService {
    private final ProjectAccessRepository accesses;
    private final ActivityRepository activities;
    private final OrganizationMembershipRepository memberships;
    private final PersonDirectory directory;
    private final ProjectService projects;
    private final MembershipGuard guard;
    private final DomainAuthorization authorization;

    public ProjectAccessService(ProjectAccessRepository accesses, ActivityRepository activities,
                                OrganizationMembershipRepository memberships, PersonDirectory directory,
                                ProjectService projects, MembershipGuard guard, DomainAuthorization authorization) {
        this.accesses = accesses;
        this.activities = activities;
        this.memberships = memberships;
        this.directory = directory;
        this.projects = projects;
        this.guard = guard;
        this.authorization = authorization;
    }

    /** No email: workers can read this list. */
    public record AccessData(UUID accessId, UUID membershipId, UUID userId, String fullName,
                             String roleCode, String roleName, URI avatarUrl) {}

    @Transactional(readOnly = true)
    public List<AccessData> list(UUID userId, UUID organizationId, UUID projectId) {
        guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.VIEW_PROJECT);
        return staff(projectId);
    }

    /** Active members with access to the site, with name and photo. No permission check: callers authorize. */
    @Transactional(readOnly = true)
    public List<AccessData> staff(UUID projectId) {
        List<ProjectAccess> list = accesses.findActiveByProject(projectId);
        Map<UUID, Person> people = directory.byUserId(list.stream()
                .map(a -> a.getOrganizationMembership().getUserAccount().getId()).toList());
        return list.stream().map(a -> toData(a, people)).toList();
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
        return toData(access, directory.byUserId(List.of(membership.getUserAccount().getId())));
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

    private static AccessData toData(ProjectAccess access, Map<UUID, Person> people) {
        OrganizationMembership membership = access.getOrganizationMembership();
        UUID memberUserId = membership.getUserAccount().getId();
        Person person = PersonDirectory.of(people, memberUserId);
        return new AccessData(access.getId(), membership.getId(), memberUserId, person.fullName(),
                membership.getRole().getCode(), membership.getRole().getName(), person.avatarUrl());
    }
}
