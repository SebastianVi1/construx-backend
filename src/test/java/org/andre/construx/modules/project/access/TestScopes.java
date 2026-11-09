package org.andre.construx.modules.project.access;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.access.OrgScopeFixtures;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.model.PermissionScope;
import org.andre.construx.modules.project.model.Project;

import java.util.Map;
import java.util.UUID;

/** Builds Site scopes for service unit tests without repositories. */
public final class TestScopes {
    private TestScopes() {}

    /** A Site scope whose role holds every action at ORGANIZATION scope. */
    public static SiteScope fullAccess(UUID organizationId, Project site, OrganizationMembership membership) {
        return site(organizationId, site, membership, OrgScopeFixtures.allActions(), true);
    }

    public static SiteScope site(UUID organizationId, Project site, OrganizationMembership membership,
                                 Map<PermissionAction, PermissionScope> grants, boolean hasSiteAccess) {
        return site(OrgScopeFixtures.org(organizationId, membership, grants), site, hasSiteAccess);
    }

    public static SiteScope site(OrgScope org, Project site, boolean hasSiteAccess) {
        return new SiteScope(org, site, () -> hasSiteAccess);
    }
}
