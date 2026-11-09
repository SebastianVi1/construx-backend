package org.andre.construx.modules.organization.access;

import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.model.PermissionScope;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** Builds organization scopes for service unit tests without repositories. */
public final class OrgScopeFixtures {
    private OrgScopeFixtures() {}

    /** Every action at ORGANIZATION scope. */
    public static Map<PermissionAction, PermissionScope> allActions() {
        Map<PermissionAction, PermissionScope> grants = new EnumMap<>(PermissionAction.class);
        for (PermissionAction action : PermissionAction.values()) {
            grants.put(action, PermissionScope.ORGANIZATION);
        }
        return grants;
    }

    public static OrgScope org(UUID organizationId, OrganizationMembership membership,
                               Map<PermissionAction, PermissionScope> grants) {
        return org(UUID.randomUUID(), organizationId, membership, grants);
    }

    public static OrgScope org(UUID userId, UUID organizationId, OrganizationMembership membership,
                               Map<PermissionAction, PermissionScope> grants) {
        return new OrgScope(userId, organizationId, membership, grants);
    }

    /** A scope whose role holds every action at ORGANIZATION scope. */
    public static OrgScope fullAccess(UUID organizationId, OrganizationMembership membership) {
        return org(organizationId, membership, allActions());
    }
}
