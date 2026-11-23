package org.andre.construx.modules.organization.service;

import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.model.PermissionScope;
import org.andre.construx.shared.exception.RuleViolationException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What a role write asks for (contract roles §5.3, §5.4): an optional name and description, and the full permission
 * set. {@code name} and {@code description} are null when the request left them out.
 */
public record RoleDraft(String name, String description, Map<PermissionAction, PermissionScope> permissions) {
    /** One (action, scope) pair as the client sent it, codes not yet checked. */
    public record Grant(String action, String scope) {}

    public RoleDraft {
        Map<PermissionAction, PermissionScope> copy = new EnumMap<>(PermissionAction.class);
        copy.putAll(permissions);
        permissions = Collections.unmodifiableMap(copy);
    }

    /**
     * Reads the client's pairs into a set. An unknown action or scope, a missing one, or an action sent twice is
     * {@code ROLE_SCOPE_INVALID} (§5.12). Scope and requirement rules are checked later, by the role itself.
     */
    public static RoleDraft of(String name, String description, List<Grant> grants) {
        Map<PermissionAction, PermissionScope> permissions = new EnumMap<>(PermissionAction.class);
        for (Grant grant : grants) {
            if (grant == null) {
                throw scopeInvalid("A permission needs an action and a scope");
            }
            PermissionAction action = parse(PermissionAction.class, grant.action(), "action");
            PermissionScope scope = parse(PermissionScope.class, grant.scope(), "scope");
            if (permissions.put(action, scope) != null) {
                throw scopeInvalid(action + " is listed more than once");
            }
        }
        return new RoleDraft(name, description, permissions);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String code, String what) {
        if (code == null) {
            throw scopeInvalid("A permission needs an action and a scope");
        }
        try {
            return Enum.valueOf(type, code);
        } catch (IllegalArgumentException e) {
            throw scopeInvalid("Unknown " + what + " " + code);
        }
    }

    private static RuleViolationException scopeInvalid(String message) {
        return new RuleViolationException("ROLE_SCOPE_INVALID", message);
    }
}
