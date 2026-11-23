package org.andre.construx.modules.organization.service;

import org.andre.construx.modules.organization.model.BuiltInRoles;
import org.andre.construx.modules.organization.model.PermissionCatalog;
import org.springframework.stereotype.Component;

/**
 * Loads {@code permission-catalog.yml} and {@code built-in-roles.yml} while the context starts, so an invalid file
 * stops the boot with its message instead of failing the first signup or catalog request.
 */
@Component
class RoleDefinitionsCheck {
    RoleDefinitionsCheck() {
        PermissionCatalog.get();
        BuiltInRoles.definitions();
    }
}
