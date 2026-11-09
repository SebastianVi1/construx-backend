package org.andre.construx.shared.security;

import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.model.PermissionScope;
import org.andre.construx.modules.project.model.Project;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** Builds scopes for service unit tests without repositories. */
public final class TestScopes {
    private TestScopes() {}

    /** A Site scope whose role holds every action at ORGANIZATION scope. */
    public static SiteScope fullAccess(UUID organizationId, Project site, OrganizationMembership membership) {
        Map<PermissionAction, PermissionScope> grants = new EnumMap<>(PermissionAction.class);
        for (PermissionAction action : PermissionAction.values()) {
            grants.put(action, PermissionScope.ORGANIZATION);
        }
        return site(organizationId, site, membership, grants, true);
    }

    public static SiteScope site(UUID organizationId, Project site, OrganizationMembership membership,
                                 Map<PermissionAction, PermissionScope> grants, boolean hasSiteAccess) {
        OrgScope org = new OrgScope(UUID.randomUUID(), organizationId, membership, grants);
        return new SiteScope(org, site, () -> hasSiteAccess);
    }
}
