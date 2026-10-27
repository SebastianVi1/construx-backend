package org.andre.construx.modules.organization.model;

import org.andre.construx.modules.identity.model.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.andre.construx.modules.organization.model.PermissionAction.*;
import static org.andre.construx.modules.organization.model.PermissionScope.*;
import static org.junit.jupiter.api.Assertions.*;

class OrganizationRoleSeedingTest {
    private Organization organization;

    @BeforeEach
    void setUp() {
        organization = new Organization("Acme", new UserAccount("owner@acme.com", "hash"));
    }

    @Test
    void newOrganizationSeedsFiveRoles() {
        assertEquals(5, organization.getRoles().size());
        for (String code : new String[] {"OWNER", "ADMINISTRATOR", "MEMBER", "WORKER", "FINANCE"}) {
            assertNotNull(role(code), code);
        }
    }

    @Test
    void onlyOwnerIsSystemRoleAndTheRestAreSeeded() {
        assertTrue(role("OWNER").isSystemRole());
        for (String code : new String[] {"ADMINISTRATOR", "MEMBER", "WORKER", "FINANCE"}) {
            assertFalse(role(code).isSystemRole(), code);
            assertTrue(role(code).isSeeded(), code);
        }
    }

    @Test
    void administratorHasEverythingExceptOrganizationManagement() {
        Map<PermissionAction, PermissionScope> expected = new EnumMap<>(PermissionAction.class);
        for (PermissionAction action : PermissionAction.values()) {
            if (action != MANAGE_ORGANIZATION) expected.put(action, ORGANIZATION);
        }
        assertEquals(expected, grants("ADMINISTRATOR"));
    }

    @Test
    void memberIsOfficeStaff() {
        Map<PermissionAction, PermissionScope> expected = new EnumMap<>(PermissionAction.class);
        expected.put(VIEW_PROJECT, ORGANIZATION);
        expected.put(VIEW_GOAL, ORGANIZATION);
        expected.put(MANAGE_GOAL, ORGANIZATION);
        expected.put(VIEW_ACTIVITY, ORGANIZATION);
        expected.put(MANAGE_ACTIVITY, ORGANIZATION);
        expected.put(ASSIGN_ACTIVITY, ORGANIZATION);
        assertEquals(expected, grants("MEMBER"));
    }

    @Test
    void workerIsFieldStaffLimitedToAssignedSitesAndOwnTasks() {
        Map<PermissionAction, PermissionScope> expected = new EnumMap<>(PermissionAction.class);
        expected.put(VIEW_PROJECT, PROJECT);
        expected.put(VIEW_GOAL, PROJECT);
        expected.put(VIEW_ACTIVITY, PROJECT);
        expected.put(MANAGE_ACTIVITY, OWN);
        assertEquals(expected, grants("WORKER"));
    }

    @Test
    void financeOnlyViewsProjectsForNow() {
        assertEquals(Map.of(VIEW_PROJECT, ORGANIZATION), grants("FINANCE"));
    }

    @Test
    void seededRolesCannotBeRenamedRegrantedOrRevoked() {
        OrganizationRole worker = role("WORKER");
        assertThrows(IllegalStateException.class, () -> worker.rename("Crew"));
        assertThrows(IllegalStateException.class, () -> worker.grant(MANAGE_PROJECT, ORGANIZATION));
        assertThrows(IllegalStateException.class, () -> worker.revoke(VIEW_PROJECT));
    }

    @Test
    void seededCodesAreReservedForCustomRoles() {
        for (String code : new String[] {"ADMINISTRATOR", "MEMBER", "WORKER", "FINANCE"}) {
            assertThrows(IllegalArgumentException.class, () -> new OrganizationRole(organization, code, "Custom"), code);
        }
    }

    @Test
    void creatorHoldsOwnerRoleMembership() {
        assertEquals("OWNER", organization.getMemberships().iterator().next().getRole().getCode());
    }

    private OrganizationRole role(String code) {
        return organization.getRoles().stream().filter(r -> r.getCode().equals(code)).findFirst().orElse(null);
    }

    private Map<PermissionAction, PermissionScope> grants(String code) {
        Map<PermissionAction, PermissionScope> map = new EnumMap<>(PermissionAction.class);
        role(code).getPermissions().forEach(p -> map.put(p.getAction(), p.getScope()));
        return map;
    }
}
