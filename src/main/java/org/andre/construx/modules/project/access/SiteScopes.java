package org.andre.construx.modules.project.access;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.access.OrgScopes;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.repository.ProjectRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Builds the {@link SiteScope} of a request. This and {@link OrgScopes} are the only places that turn a caller and
 * path identifiers into tenant access: a missing or inactive membership and a Site outside the organization are
 * both 404, before any permission is checked (403).
 */
@Component
public class SiteScopes {
    private final OrgScopes orgs;
    private final ProjectRepository projects;
    private final ProjectAccessRepository accesses;

    public SiteScopes(OrgScopes orgs, ProjectRepository projects, ProjectAccessRepository accesses) {
        this.orgs = orgs;
        this.projects = projects;
        this.accesses = accesses;
    }

    /** The caller's membership plus a Site of that organization; 404 when either is missing. */
    @Transactional(readOnly = true)
    public SiteScope site(UUID userId, UUID organizationId, UUID siteId) {
        return site(orgs.org(userId, organizationId), siteId);
    }

    /** A Site of the scope's organization; 404 when it is not one. */
    @Transactional(readOnly = true)
    public SiteScope site(OrgScope org, UUID siteId) {
        Project site = projects.findByIdAndOrganizationId(siteId, org.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        UUID membershipId = org.membershipId();
        return new SiteScope(org, site, () -> accesses.existsByProjectIdAndOrganizationMembershipId(siteId, membershipId));
    }
}
