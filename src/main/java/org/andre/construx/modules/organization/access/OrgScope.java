package org.andre.construx.modules.organization.access;

import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.Permission;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.model.PermissionScope;
import org.springframework.security.access.AccessDeniedException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * An authenticated caller inside one organization, with an active membership already verified (404 otherwise).
 * Built by {@link OrgScopes}; controllers receive it as a {@code OrgScope} parameter.
 */
public final class OrgScope {
    private final UUID userId;
    private final UUID organizationId;
    private final OrganizationMembership membership;
    private final Map<PermissionAction, PermissionScope> grants;

    OrgScope(UUID userId, UUID organizationId, OrganizationMembership membership) {
        this(userId, organizationId, membership, grantsOf(membership));
    }

    OrgScope(UUID userId, UUID organizationId, OrganizationMembership membership,
             Map<PermissionAction, PermissionScope> grants) {
        this.userId = userId;
        this.organizationId = organizationId;
        this.membership = membership;
        Map<PermissionAction, PermissionScope> copy = new EnumMap<>(PermissionAction.class);
        copy.putAll(grants);
        this.grants = Collections.unmodifiableMap(copy);
    }

    private static Map<PermissionAction, PermissionScope> grantsOf(OrganizationMembership membership) {
        Map<PermissionAction, PermissionScope> map = new EnumMap<>(PermissionAction.class);
        for (Permission permission : membership.getRole().getPermissions()) {
            map.put(permission.getAction(), permission.getScope());
        }
        return map;
    }

    public UUID userId() {
        return userId;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public OrganizationMembership membership() {
        return membership;
    }

    public UUID membershipId() {
        return membership.getId();
    }

    /** Scope the caller holds for an action, empty if the role lacks it. Lists use it to filter rows. */
    public Optional<PermissionScope> scopeOf(PermissionAction action) {
        return Optional.ofNullable(grants.get(action));
    }

    /**
     * The one grant rule, shared with {@code SiteScope}: ORGANIZATION scope always passes, PROJECT scope needs
     * Site access ({@code siteAccess} is only asked then), OWN scope needs {@code ownerMembershipId} to be the caller.
     */
    public boolean can(PermissionAction action, BooleanSupplier siteAccess, UUID ownerMembershipId) {
        return scopeOf(action).map(scope -> switch (scope) {
            case ORGANIZATION -> true;
            case PROJECT -> siteAccess.getAsBoolean();
            case OWN -> ownerMembershipId != null && ownerMembershipId.equals(membershipId());
        }).orElse(false);
    }

    /** 403 unless {@link #can(PermissionAction, BooleanSupplier, UUID)} holds. */
    public void require(PermissionAction action, BooleanSupplier siteAccess, UUID ownerMembershipId) {
        if (!can(action, siteAccess, ownerMembershipId)) {
            throw new AccessDeniedException(scopeOf(action).isEmpty() ? "Permission required" : "Permission scope denied");
        }
    }

    /** Organization-wide operations: no Site and no owner, so only ORGANIZATION scope passes; 403 otherwise. */
    public void require(PermissionAction action) {
        require(action, () -> false, null);
    }
}
