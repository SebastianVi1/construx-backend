package org.andre.construx.modules.project.access;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.model.PermissionScope;
import org.andre.construx.modules.project.model.Project;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * An authenticated caller inside one Site: active membership and the Site (within the organization) are already
 * loaded, so anything missing was a 404. {@link #require} only adds the 403 permission check.
 * Built by {@link SiteScopes}; controllers receive it as a {@code SiteScope} parameter.
 */
public final class SiteScope {
    private final OrgScope org;
    private final Project site;
    private final BooleanSupplier siteAccessQuery;
    private Boolean hasSiteAccess;

    SiteScope(OrgScope org, Project site, BooleanSupplier siteAccessQuery) {
        this.org = org;
        this.site = site;
        this.siteAccessQuery = siteAccessQuery;
    }

    public OrgScope org() {
        return org;
    }

    public UUID userId() {
        return org.userId();
    }

    public UUID organizationId() {
        return org.organizationId();
    }

    public OrganizationMembership membership() {
        return org.membership();
    }

    public UUID membershipId() {
        return org.membershipId();
    }

    public Project site() {
        return site;
    }

    public UUID siteId() {
        return site.getId();
    }

    /** Scope the caller holds for an action, empty if the role lacks it. */
    public Optional<PermissionScope> scopeOf(PermissionAction action) {
        return org.scopeOf(action);
    }

    /** True when the caller is assigned to this Site ({@code ProjectAccess}); queried at most once. */
    public boolean hasSiteAccess() {
        if (hasSiteAccess == null) {
            hasSiteAccess = siteAccessQuery.getAsBoolean();
        }
        return hasSiteAccess;
    }

    /** 403 unless the role grants the action here: ORGANIZATION scope, or PROJECT scope with Site access. */
    public void require(PermissionAction action) {
        require(action, null);
    }

    /**
     * Same as {@link #require(PermissionAction)}, but OWN scope also passes when {@code ownerMembershipId} is the
     * caller's membership (e.g. the assignee of a task).
     */
    public void require(PermissionAction action, UUID ownerMembershipId) {
        if (!can(action, ownerMembershipId)) {
            throw new AccessDeniedException(scopeOf(action).isEmpty() ? "Permission required" : "Permission scope denied");
        }
    }

    public boolean can(PermissionAction action) {
        return can(action, null);
    }

    public boolean can(PermissionAction action, UUID ownerMembershipId) {
        return scopeOf(action).map(scope -> switch (scope) {
            case ORGANIZATION -> true;
            case PROJECT -> hasSiteAccess();
            case OWN -> ownerMembershipId != null && ownerMembershipId.equals(membershipId());
        }).orElse(false);
    }
}
