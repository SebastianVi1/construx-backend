package org.andre.construx.modules.organization.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.andre.construx.modules.organization.model.PermissionAction.*;
import static org.andre.construx.modules.organization.model.PermissionScope.*;

/** Built-in roles every organization starts with, besides the owner. Matrix: .agents/contracts/v1-api.md section 8. */
final class SeededRoles {
    private SeededRoles() {
    }

    static List<OrganizationRole> create(Organization organization) {
        Map<PermissionAction, PermissionScope> administrator = new EnumMap<>(PermissionAction.class);
        for (PermissionAction action : PermissionAction.values()) {
            if (action != MANAGE_ORGANIZATION) {
                administrator.put(action, ORGANIZATION);
            }
        }
        return List.of(
                OrganizationRole.seeded(organization, "ADMINISTRATOR", "Administrador", administrator),
                OrganizationRole.seeded(organization, "MEMBER", "Miembro", Map.of(
                        VIEW_PROJECT, ORGANIZATION,
                        VIEW_GOAL, ORGANIZATION,
                        MANAGE_GOAL, ORGANIZATION,
                        VIEW_ACTIVITY, ORGANIZATION,
                        MANAGE_ACTIVITY, ORGANIZATION,
                        ASSIGN_ACTIVITY, ORGANIZATION)),
                OrganizationRole.seeded(organization, "WORKER", "Trabajador", Map.of(
                        VIEW_PROJECT, PROJECT,
                        VIEW_GOAL, PROJECT,
                        VIEW_ACTIVITY, PROJECT,
                        MANAGE_ACTIVITY, OWN)),
                OrganizationRole.seeded(organization, "FINANCE", "Finanzas", Map.of(VIEW_PROJECT, ORGANIZATION)));
    }
}
