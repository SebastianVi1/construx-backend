package org.andre.construx.modules.organization.service;

import org.andre.construx.modules.organization.model.OrganizationRole;

import java.util.Map;
import java.util.TreeMap;

/** A role as the audit log records it (contract roles §7): {@code { code, name, description, permissions }}. */
public record RoleAuditState(String code, String name, String description, Map<String, String> permissions) {
    public static RoleAuditState of(OrganizationRole role) {
        Map<String, String> permissions = new TreeMap<>();
        role.grants().forEach((action, scope) -> permissions.put(action.name(), scope.name()));
        return new RoleAuditState(role.getCode(), role.getName(), role.getDescription(), permissions);
    }
}
