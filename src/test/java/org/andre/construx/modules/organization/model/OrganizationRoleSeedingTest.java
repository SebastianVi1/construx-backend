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
    void newOrganizationSeedsAllRoles() {
        assertEquals(8, organization.getRoles().size());
        for (String code : new String[] {"OWNER", "ADMINISTRATOR", "MEMBER", "WORKER", "FINANCE",
                "RESIDENT", "SITE_MANAGER", "SUPERINTENDENT"}) {
            assertNotNull(role(code), code);
        }
    }

    @Test
    void onlyOwnerIsSystemRoleAndTheRestAreSeeded() {
        assertTrue(role("OWNER").isSystemRole());
        for (String code : new String[] {"ADMINISTRATOR", "MEMBER", "WORKER", "FINANCE",
                "RESIDENT", "SITE_MANAGER", "SUPERINTENDENT"}) {
            assertFalse(role(code).isSystemRole(), code);
            assertTrue(role(code).isSeeded(), code);
        }
    }

    @Test
    void administratorHasEverythingExceptOrganizationManagementAndChangingBudgets() {
        Map<PermissionAction, PermissionScope> expected = new EnumMap<>(PermissionAction.class);
        for (PermissionAction action : PermissionAction.values()) {
            if (action != MANAGE_ORGANIZATION && action != MANAGE_BUDGET) expected.put(action, ORGANIZATION);
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
        expected.put(VIEW_PRICES, ORGANIZATION);
        expected.put(VIEW_PLAN, PROJECT);
        expected.put(MANAGE_PLAN, PROJECT);
        assertEquals(expected, grants("MEMBER"));
    }

    @Test
    void workerIsFieldStaffLimitedToAssignedSitesAndOwnTasks() {
        Map<PermissionAction, PermissionScope> expected = new EnumMap<>(PermissionAction.class);
        expected.put(VIEW_PROJECT, PROJECT);
        expected.put(VIEW_GOAL, PROJECT);
        expected.put(VIEW_ACTIVITY, PROJECT);
        expected.put(MANAGE_ACTIVITY, OWN);
        expected.put(VIEW_PLAN, PROJECT);
        assertEquals(expected, grants("WORKER"));
    }

    @Test
    void financeViewsSitesPhasesAndTasksAndManagesMaterialPricesAndBudgets() {
        assertEquals(Map.of(VIEW_PROJECT, ORGANIZATION, VIEW_GOAL, ORGANIZATION, VIEW_ACTIVITY, ORGANIZATION,
                        VIEW_PRICES, ORGANIZATION, MANAGE_PRICES, ORGANIZATION,
                        VIEW_BUDGET, ORGANIZATION, MANAGE_BUDGET, ORGANIZATION),
                grants("FINANCE"));
    }

    @Test
    void ownerHoldsEveryActionIncludingMaterialPrices() {
        Map<PermissionAction, PermissionScope> expected = new EnumMap<>(PermissionAction.class);
        for (PermissionAction action : PermissionAction.values()) expected.put(action, ORGANIZATION);
        assertEquals(expected, grants("OWNER"));
        assertEquals(ORGANIZATION, grants("OWNER").get(MANAGE_PRICES));
    }

    @Test
    void seededRolesCannotBeRenamedRegrantedOrRevoked() {
        OrganizationRole worker = role("WORKER");
        assertThrows(IllegalStateException.class, () -> worker.rename("Crew"));
        assertThrows(IllegalStateException.class, () -> worker.grant(MANAGE_PROJECT, ORGANIZATION));
        assertThrows(IllegalStateException.class, () -> worker.revoke(VIEW_PROJECT));
    }

    @Test
    void residentManagesSiteLevelWorkAndPlans() {
        Map<PermissionAction, PermissionScope> expected = new EnumMap<>(PermissionAction.class);
        expected.put(VIEW_PROJECT, PROJECT);
        expected.put(VIEW_GOAL, PROJECT);
        expected.put(MANAGE_GOAL, PROJECT);
        expected.put(VIEW_ACTIVITY, PROJECT);
        expected.put(MANAGE_ACTIVITY, PROJECT);
        expected.put(ASSIGN_ACTIVITY, PROJECT);
        expected.put(VIEW_PLAN, PROJECT);
        expected.put(MANAGE_PLAN, PROJECT);
        assertEquals(expected, grants("RESIDENT"));
    }

    @Test
    void siteManagerHasFullSiteScopeIncludingManageProject() {
        Map<PermissionAction, PermissionScope> expected = new EnumMap<>(PermissionAction.class);
        expected.put(VIEW_PROJECT, PROJECT);
        expected.put(MANAGE_PROJECT, PROJECT);
        expected.put(VIEW_GOAL, PROJECT);
        expected.put(MANAGE_GOAL, PROJECT);
        expected.put(VIEW_ACTIVITY, PROJECT);
        expected.put(MANAGE_ACTIVITY, PROJECT);
        expected.put(ASSIGN_ACTIVITY, PROJECT);
        expected.put(VIEW_PLAN, PROJECT);
        expected.put(MANAGE_PLAN, PROJECT);
        assertEquals(expected, grants("SITE_MANAGER"));
    }

    @Test
    void superintendentOverseesAllSitesWithPricesAndBudgetVisibility() {
        Map<PermissionAction, PermissionScope> expected = new EnumMap<>(PermissionAction.class);
        expected.put(VIEW_PROJECT, ORGANIZATION);
        expected.put(VIEW_GOAL, ORGANIZATION);
        expected.put(MANAGE_GOAL, ORGANIZATION);
        expected.put(VIEW_ACTIVITY, ORGANIZATION);
        expected.put(MANAGE_ACTIVITY, ORGANIZATION);
        expected.put(ASSIGN_ACTIVITY, ORGANIZATION);
        expected.put(VIEW_PLAN, ORGANIZATION);
        expected.put(MANAGE_PLAN, ORGANIZATION);
        expected.put(VIEW_PRICES, ORGANIZATION);
        expected.put(VIEW_BUDGET, ORGANIZATION);
        assertEquals(expected, grants("SUPERINTENDENT"));
    }

    @Test
    void seededCodesAreReservedForCustomRoles() {
        for (String code : new String[] {"ADMINISTRATOR", "MEMBER", "WORKER", "FINANCE",
                "RESIDENT", "SITE_MANAGER", "SUPERINTENDENT"}) {
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
