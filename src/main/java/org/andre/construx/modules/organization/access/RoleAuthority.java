package org.andre.construx.modules.organization.access;

import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.OrganizationRole;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.model.PermissionRules;
import org.andre.construx.modules.organization.model.PermissionScope;
import org.andre.construx.shared.exception.ForbiddenRuleException;
import org.andre.construx.shared.exception.RuleViolationException;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;
import java.util.Optional;

/**
 * "Never above your own role" (contract roles §3). A role or permission set <em>fits within</em> the caller when the
 * caller's role holds each of its actions at the same or a wider scope ({@code OWN < PROJECT < ORGANIZATION}); the
 * Owner role fits within no one but Owner. Each check applies the §3 rules in order: the permission
 * ({@code FORBIDDEN}), then {@code ROLE_OWNER_LOCKED} (400), {@code ROLE_OWN} (403) and {@code ROLE_ABOVE_CALLER}
 * (403). The {@code editable} and {@code assignable} flags of the roles list are the same rules, answered without
 * throwing.
 */
public final class RoleAuthority {
    private RoleAuthority() {
    }

    /** Whether the caller's role holds every grant of {@code set} at the same or a wider scope. */
    public static boolean fitsWithin(OrgScope caller, Map<PermissionAction, PermissionScope> set) {
        return set.entrySet().stream().allMatch(grant -> caller.scopeOf(grant.getKey())
                .map(held -> PermissionRules.atLeast(held, grant.getValue()))
                .orElse(false));
    }

    /** Creating a custom role: {@code MANAGE_ROLES}, and the new set fits within the caller. */
    public static void checkCreate(OrgScope caller, Map<PermissionAction, PermissionScope> newSet) {
        caller.require(PermissionAction.MANAGE_ROLES);
        if (!fitsWithin(caller, newSet)) {
            throw aboveCaller();
        }
    }

    /**
     * Editing a role (also restore, with the defaults, and delete, with the empty set): {@code MANAGE_ROLES}, not
     * the Owner role, not the caller's own role, and both the role as it stands and {@code newSet} fit.
     */
    public static void checkEdit(OrgScope caller, OrganizationRole role, Map<PermissionAction, PermissionScope> newSet) {
        editRefusal(caller, role).ifPresent(refusal -> {
            throw refusal;
        });
        if (!fitsWithin(caller, newSet)) {
            throw aboveCaller();
        }
    }

    /**
     * Moving {@code target} to role {@code to}: {@code MANAGE_MEMBERS}, not the caller, the Owner role neither the
     * target's current role nor {@code to}, and both roles fit (the current one whatever the member's status).
     */
    public static void checkAssign(OrgScope caller, OrganizationMembership target, OrganizationRole to) {
        caller.require(PermissionAction.MANAGE_MEMBERS);
        if (sameMembership(target, caller.membership())) {
            throw new ForbiddenRuleException("ROLE_OWN", "You cannot change your own role");
        }
        if (target.getRole().isSystemRole()) {
            throw ownerLocked();
        }
        assignRefusal(caller, to).ifPresent(refusal -> {
            throw refusal;
        });
        if (!fits(caller, target.getRole())) {
            throw aboveCaller();
        }
    }

    /** Inviting someone into {@code role}: {@code MANAGE_MEMBERS}, not the Owner role, and the role fits. */
    public static void checkInvite(OrgScope caller, OrganizationRole role) {
        assignRefusal(caller, role).ifPresent(refusal -> {
            throw refusal;
        });
    }

    /** The roles list's {@code editable}: whether the caller may edit this role's permissions at all. */
    public static boolean editable(OrgScope caller, OrganizationRole role) {
        return editRefusal(caller, role).isEmpty();
    }

    /** The roles list's {@code assignable}: whether the caller may assign or invite someone into this role. */
    public static boolean assignable(OrgScope caller, OrganizationRole role) {
        return assignRefusal(caller, role).isEmpty();
    }

    private static Optional<RuntimeException> editRefusal(OrgScope caller, OrganizationRole role) {
        if (!caller.can(PermissionAction.MANAGE_ROLES)) {
            return Optional.of(new AccessDeniedException("Permission required"));
        }
        if (role.isSystemRole()) {
            return Optional.of(ownerLocked());
        }
        if (sameRole(role, caller.membership().getRole())) {
            return Optional.of(new ForbiddenRuleException("ROLE_OWN", "You cannot change your own role"));
        }
        if (!fits(caller, role)) {
            return Optional.of(aboveCaller());
        }
        return Optional.empty();
    }

    private static Optional<RuntimeException> assignRefusal(OrgScope caller, OrganizationRole role) {
        if (!caller.can(PermissionAction.MANAGE_MEMBERS)) {
            return Optional.of(new AccessDeniedException("Permission required"));
        }
        if (role.isSystemRole()) {
            return Optional.of(ownerLocked());
        }
        if (!fits(caller, role)) {
            return Optional.of(aboveCaller());
        }
        return Optional.empty();
    }

    /** A role fits within the caller; the Owner role only within the Owner. */
    private static boolean fits(OrgScope caller, OrganizationRole role) {
        if (role.isSystemRole()) {
            return caller.membership().getRole().isSystemRole();
        }
        return fitsWithin(caller, role.grants());
    }

    private static boolean sameRole(OrganizationRole a, OrganizationRole b) {
        return a == b || (a.getId() != null && a.getId().equals(b.getId()));
    }

    private static boolean sameMembership(OrganizationMembership a, OrganizationMembership b) {
        return a == b || (a.getId() != null && a.getId().equals(b.getId()));
    }

    private static RuleViolationException ownerLocked() {
        return new RuleViolationException("ROLE_OWNER_LOCKED", "The Owner role cannot be changed or assigned");
    }

    private static ForbiddenRuleException aboveCaller() {
        return new ForbiddenRuleException("ROLE_ABOVE_CALLER", "This role holds permissions your own role does not");
    }
}
