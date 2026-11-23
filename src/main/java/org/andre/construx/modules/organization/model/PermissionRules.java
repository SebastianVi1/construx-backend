package org.andre.construx.modules.organization.model;

import org.andre.construx.shared.exception.RuleViolationException;

import java.util.Map;

/** The rules every permission set must pass (contract roles §4): allowed scopes and requirements. */
public final class PermissionRules {
    private PermissionRules() {
    }

    /** Scope order: {@code OWN < PROJECT < ORGANIZATION}. */
    public static boolean atLeast(PermissionScope held, PermissionScope wanted) {
        return held.ordinal() >= wanted.ordinal();
    }

    /** Throws {@code ROLE_SCOPE_INVALID} when the catalog does not allow the action at that scope. */
    public static void checkScope(PermissionAction action, PermissionScope scope) {
        if (action == null || scope == null) {
            throw new RuleViolationException("ROLE_SCOPE_INVALID", "Action and scope are required");
        }
        if (!action.scopes().contains(scope)) {
            throw new RuleViolationException("ROLE_SCOPE_INVALID",
                    action + " cannot be granted at " + scope + " scope; allowed: " + action.scopes());
        }
    }

    /**
     * Checks a whole set: every scope allowed, every requirement held wide enough. A map holds each action once,
     * so duplicates are the caller's to reject when it builds the map. The empty set is valid.
     */
    public static void checkSet(Map<PermissionAction, PermissionScope> grants) {
        grants.forEach(PermissionRules::checkScope);
        for (Map.Entry<PermissionAction, PermissionScope> grant : grants.entrySet()) {
            for (PermissionAction.Requirement requirement : grant.getKey().requires()) {
                PermissionScope held = grants.get(requirement.action());
                if (held == null || !atLeast(held, requirement.minScope().floorFor(grant.getValue()))) {
                    throw new RoleDependencyException(grant.getKey(), requirement.action());
                }
            }
        }
    }
}
