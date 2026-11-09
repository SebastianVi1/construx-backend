package org.andre.construx.shared.security;

import org.andre.construx.modules.organization.model.MembershipStatus;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.repository.ProjectRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Builds the {@link OrgScope} and {@link SiteScope} of a request. This is the only place that turns a caller and
 * path identifiers into tenant access: a missing or inactive membership and a Site outside the organization are
 * both 404, before any permission is checked (403).
 */
@Component
public class AccessScopes {
    private final OrganizationMembershipRepository memberships;
    private final ProjectRepository projects;
    private final ProjectAccessRepository accesses;

    public AccessScopes(OrganizationMembershipRepository memberships, ProjectRepository projects,
                        ProjectAccessRepository accesses) {
        this.memberships = memberships;
        this.projects = projects;
        this.accesses = accesses;
    }

    /** The caller's active membership in the organization; 404 when there is none. */
    @Transactional(readOnly = true)
    public OrgScope org(UUID userId, UUID organizationId) {
        return new OrgScope(userId, organizationId, activeMember(userId, organizationId));
    }

    /** The caller's membership plus a Site of that organization; 404 when either is missing. */
    @Transactional(readOnly = true)
    public SiteScope site(UUID userId, UUID organizationId, UUID siteId) {
        OrgScope org = org(userId, organizationId);
        Project site = projects.findByIdAndOrganizationId(siteId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        UUID membershipId = org.membership().getId();
        return new SiteScope(org, site, () -> accesses.existsByProjectIdAndOrganizationMembershipId(siteId, membershipId));
    }

    private OrganizationMembership activeMember(UUID userId, UUID organizationId) {
        return memberships.findByOrganizationIdAndUserAccountId(organizationId, userId)
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
    }
}
