package org.andre.construx.modules.organization.model;

import org.andre.construx.shared.exception.RuleViolationException;

import java.util.Map;

/** {@code ROLE_DEPENDENCY_MISSING}: a granted action lacks one it requires. The problem body names both. */
public class RoleDependencyException extends RuleViolationException {
    private final PermissionAction action;
    private final PermissionAction requires;

    public RoleDependencyException(PermissionAction action, PermissionAction requires) {
        super("ROLE_DEPENDENCY_MISSING", action + " requires " + requires,
                Map.of("action", action.name(), "requires", requires.name()));
        this.action = action;
        this.requires = requires;
    }

    public PermissionAction action() {
        return action;
    }

    public PermissionAction requires() {
        return requires;
    }
}
