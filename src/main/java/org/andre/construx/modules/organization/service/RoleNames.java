package org.andre.construx.modules.organization.service;

import org.andre.construx.modules.organization.model.BuiltInRoles;
import org.andre.construx.modules.organization.model.LocalizedText;
import org.andre.construx.modules.organization.model.OrganizationRole;

/**
 * Display names of a role in both languages (contract roles §5.11). Owner and Built-in roles take theirs from
 * {@code built-in-roles.yml}; a custom role shows its stored name in both languages. Every role summary the API
 * returns uses this, so members who cannot read the roles list still see the right name in their language.
 */
public final class RoleNames {
    private RoleNames() {
    }

    public static LocalizedText of(OrganizationRole role) {
        if (role.isSystemRole()) {
            return BuiltInRoles.owner().name();
        }
        if (role.isSeeded()) {
            return BuiltInRoles.find(role.getCode())
                    .map(BuiltInRoles.Definition::name)
                    .orElseGet(() -> LocalizedText.same(role.getName()));
        }
        return LocalizedText.same(role.getName());
    }

    /**
     * The description per language: from the file for Owner and Built-in roles, the stored description in both
     * languages for a custom role (both null when it has none).
     */
    public static LocalizedText descriptionOf(OrganizationRole role) {
        if (role.isSystemRole()) {
            return BuiltInRoles.owner().description();
        }
        if (role.isSeeded()) {
            return BuiltInRoles.find(role.getCode())
                    .map(BuiltInRoles.Definition::description)
                    .orElseGet(() -> LocalizedText.same(role.getDescription()));
        }
        return LocalizedText.same(role.getDescription());
    }
}
