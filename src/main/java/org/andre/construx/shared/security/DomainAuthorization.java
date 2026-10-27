package org.andre.construx.shared.security;

import org.andre.construx.modules.organization.model.*;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.repository.ProjectRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** Evaluate current membership permissions, never stale authorities from a JWT. */
@Service
public class DomainAuthorization {
    private final OrganizationMembershipRepository memberships;
    private final ProjectRepository projects;
    private final ProjectAccessRepository accesses;

    public DomainAuthorization(OrganizationMembershipRepository memberships, ProjectRepository projects,
            ProjectAccessRepository accesses) {
        this.memberships = memberships;
        this.projects = projects;
        this.accesses = accesses;
    }

    /** Deny when the user, organization, project, or assignment does not satisfy the permission scope. */
    @Transactional(readOnly = true)
    public void require(UUID userId, UUID organizationId, UUID projectId,
            UUID assigneeMembershipId, PermissionAction action) {
        OrganizationMembership member = activeMember(userId, organizationId);
        if (projectId != null && projects.findById(projectId)
                .filter(p -> p.getOrganization().getId().equals(organizationId)).isEmpty())
            throw new AccessDeniedException("Project unavailable");
        Permission permission = member.getRole().getPermissions().stream()
                .filter(p -> p.getAction() == action).findFirst()
                .orElseThrow(() -> new AccessDeniedException("Permission required"));
        boolean allowed = switch (permission.getScope()) {
            case ORGANIZATION -> true;
            case PROJECT -> projectId != null && accesses.existsByProjectIdAndOrganizationMembershipId(projectId, member.getId());
            case OWN -> assigneeMembershipId != null && assigneeMembershipId.equals(member.getId());
        };
        if (!allowed) throw new AccessDeniedException("Permission scope denied");
    }

    /**
     * Scope the caller holds for an action in an organization, empty if the role lacks it.
     * Lists use this to filter rows (ORGANIZATION: all, PROJECT: assigned sites, OWN: own rows).
     */
    @Transactional(readOnly = true)
    public Optional<PermissionScope> scopeOf(UUID userId, UUID organizationId, PermissionAction action) {
        return activeMember(userId, organizationId).getRole().getPermissions().stream()
                .filter(p -> p.getAction() == action)
                .map(Permission::getScope)
                .findFirst();
    }

    private OrganizationMembership activeMember(UUID userId, UUID organizationId) {
        return memberships.findByOrganizationIdAndUserAccountId(organizationId, userId)
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .orElseThrow(() -> new AccessDeniedException("Membership required"));
    }
}
