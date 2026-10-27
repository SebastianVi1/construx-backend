package org.andre.construx.modules.organization.model;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class SeededRolesDefinitionTest {
    private static Properties props(String... lines) throws Exception {
        Properties p = new Properties();
        p.load(new java.io.StringReader(String.join("\n", lines)));
        return p;
    }

    @Test
    void parsesNamesAndGrantsFromTheFile() throws Exception {
        var defs = SeededRoles.parse(props("roles=SURVEYOR", "role.SURVEYOR.name=Topografo",
                "role.SURVEYOR.grants=VIEW_PROJECT:PROJECT, MANAGE_ACTIVITY:OWN"));
        var role = defs.roles().get(0);
        assertEquals("SURVEYOR", role.code());
        assertEquals("Topografo", role.name());
        assertEquals(PermissionScope.PROJECT, role.grants().get(PermissionAction.VIEW_PROJECT));
        assertEquals(PermissionScope.OWN, role.grants().get(PermissionAction.MANAGE_ACTIVITY));
    }

    @Test
    void rejectsMissingRolesName_ownerCode_unknownAction_badScopeAndDuplicates() {
        assertThrows(IllegalStateException.class, () -> SeededRoles.parse(props("")));
        assertThrows(IllegalStateException.class, () -> SeededRoles.parse(props("roles=A")));
        assertThrows(IllegalStateException.class, () -> SeededRoles.parse(props("roles=OWNER", "role.OWNER.name=x")));
        assertThrows(IllegalStateException.class, () -> SeededRoles.parse(props("roles=A", "role.A.name=x", "role.A.grants=FLY:ORGANIZATION")));
        assertThrows(IllegalStateException.class, () -> SeededRoles.parse(props("roles=A", "role.A.name=x", "role.A.grants=VIEW_PROJECT:GALAXY")));
        assertThrows(IllegalStateException.class, () -> SeededRoles.parse(props("roles=A", "role.A.name=x", "role.A.grants=VIEW_PROJECT")));
        assertThrows(IllegalStateException.class, () -> SeededRoles.parse(props("roles=A", "role.A.name=x",
                "role.A.grants=VIEW_PROJECT:PROJECT,VIEW_PROJECT:ORGANIZATION")));
        assertThrows(IllegalStateException.class, () -> SeededRoles.parse(props("roles=A,A", "role.A.name=x")));
    }

    @Test
    void permissionRulesApplyToTheFile() {
        assertThrows(IllegalArgumentException.class,
                () -> Permission.validate(PermissionAction.MANAGE_ROLES, PermissionScope.PROJECT));
    }

    @Test
    void materialPricesRequireOrganizationScope() {
        for (PermissionAction action : new PermissionAction[] {PermissionAction.VIEW_PRICES, PermissionAction.MANAGE_PRICES}) {
            assertThrows(IllegalArgumentException.class, () -> Permission.validate(action, PermissionScope.PROJECT));
            assertThrows(IllegalArgumentException.class, () -> Permission.validate(action, PermissionScope.OWN));
            assertDoesNotThrow(() -> Permission.validate(action, PermissionScope.ORGANIZATION));
        }
    }
}
