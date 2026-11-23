package org.andre.construx.modules.organization.model;

import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.shared.exception.RuleViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import static org.andre.construx.modules.organization.model.PermissionAction.*;
import static org.andre.construx.modules.organization.model.PermissionScope.*;
import static org.junit.jupiter.api.Assertions.*;

class OrganizationRoleEditingTest {
    private Organization organization;

    @BeforeEach
    void setUp() {
        organization = new Organization("Acme", new UserAccount("owner@acme.com", "hash"));
    }

    @Test
    void customRoleGetsGeneratedCodeAndValidatesNameAndSet() {
        OrganizationRole role = OrganizationRole.custom(organization, "  Foreman ", " Runs a crew ",
                grants(VIEW_PROJECT, PROJECT, VIEW_PLAN, PROJECT));

        assertTrue(role.getCode().matches("CUSTOM_[A-Z2-7]{10}"), role.getCode());
        assertNotEquals(role.getCode(), OrganizationRole.custom(organization, "Other", null, Map.of()).getCode());
        assertEquals(RoleKind.CUSTOM, role.kind());
        assertEquals("Foreman", role.getName());
        assertEquals("Runs a crew", role.getDescription());
        assertEquals(grants(VIEW_PROJECT, PROJECT, VIEW_PLAN, PROJECT), role.grants());
        assertFalse(role.isCustomized());

        assertCode("ROLE_NAME_INVALID", () -> OrganizationRole.custom(organization, " ", null, Map.of()));
        assertCode("ROLE_NAME_INVALID", () -> OrganizationRole.custom(organization, "x".repeat(101), null, Map.of()));
        assertCode("ROLE_NAME_INVALID", () -> OrganizationRole.custom(organization, "Ok", "x".repeat(281), Map.of()));
        assertCode("ROLE_DEPENDENCY_MISSING", () -> OrganizationRole.custom(organization, "Ok", null,
                grants(MANAGE_PLAN, PROJECT, VIEW_PROJECT, PROJECT)));
        assertCode("ROLE_SCOPE_INVALID", () -> OrganizationRole.custom(organization, "Ok", null,
                grants(VIEW_PRICES, PROJECT)));
    }

    @Test
    void blankDescriptionIsStoredAsNone() {
        assertNull(OrganizationRole.custom(organization, "Foreman", "  ", Map.of()).getDescription());
    }

    @Test
    void ownerCannotBeChanged() {
        OrganizationRole owner = role("OWNER");
        assertEquals(RoleKind.OWNER, owner.kind());
        assertCode("ROLE_OWNER_LOCKED", () -> owner.replacePermissions(Map.of()));
        assertCode("ROLE_OWNER_LOCKED", () -> owner.restoreDefaults());
        assertCode("ROLE_OWNER_LOCKED", () -> owner.renameCustom("Boss"));
        assertCode("ROLE_OWNER_LOCKED", () -> owner.describe("Boss"));
        assertCode("ROLE_OWNER_LOCKED", owner::convertToCustom);
        assertCode("ROLE_OWNER_LOCKED", owner::adoptAsBuiltIn);
        assertEquals(16, owner.grants().size());
    }

    @Test
    void editingBuiltInMarksCustomizedAndBackToDefaultsClearsIt() {
        OrganizationRole worker = role("WORKER");
        Map<PermissionAction, PermissionScope> defaults = BuiltInRoles.find("WORKER").orElseThrow().grants();
        assertEquals(RoleKind.BUILT_IN, worker.kind());
        assertFalse(worker.isCustomized());

        Map<PermissionAction, PermissionScope> withPrices = new EnumMap<>(defaults);
        withPrices.put(VIEW_PRICES, ORGANIZATION);
        worker.replacePermissions(withPrices);
        assertTrue(worker.isCustomized());
        assertEquals(withPrices, worker.grants());

        worker.replacePermissions(defaults);
        assertFalse(worker.isCustomized());
        assertEquals(defaults, worker.grants());

        assertCode("ROLE_DEPENDENCY_MISSING", () -> worker.replacePermissions(grants(MANAGE_PRICES, ORGANIZATION)));
        assertEquals(defaults, worker.grants());
    }

    @Test
    void customRoleEditsNeverMarkItCustomized() {
        OrganizationRole role = OrganizationRole.custom(organization, "Foreman", null, Map.of());
        role.replacePermissions(grants(VIEW_PRICES, ORGANIZATION));
        assertFalse(role.isCustomized());
        assertEquals(grants(VIEW_PRICES, ORGANIZATION), role.grants());
    }

    @Test
    void builtInNameIsFixed() {
        OrganizationRole worker = role("WORKER");
        assertCode("ROLE_NAME_FIXED", () -> worker.renameCustom("Crew"));
        assertCode("ROLE_NAME_FIXED", () -> worker.describe("Crew"));
        assertCode("ROLE_NAME_FIXED", worker::requireTextEditable);

        OrganizationRole custom = OrganizationRole.custom(organization, "Foreman", null, Map.of());
        custom.renameCustom(" Crew lead ");
        custom.describe("Leads");
        assertEquals("Crew lead", custom.getName());
        assertEquals("Leads", custom.getDescription());
        assertCode("ROLE_NAME_INVALID", () -> custom.renameCustom(""));
        assertCode("ROLE_NAME_INVALID", () -> custom.describe("x".repeat(281)));
        assertDoesNotThrow(custom::requireTextEditable);
    }

    @Test
    void onlyACustomRoleCanBeDeleted() {
        assertCode("ROLE_BUILT_IN_UNDELETABLE", role("WORKER")::requireDeletable);
        assertDoesNotThrow(OrganizationRole.custom(organization, "Foreman", null, Map.of())::requireDeletable);
    }

    @Test
    void restoreDefaultsResetsPermissionsAndCustomized() {
        OrganizationRole worker = role("WORKER");
        BuiltInRoles.Definition definition = BuiltInRoles.find("WORKER").orElseThrow();
        worker.replacePermissions(Map.of());
        assertTrue(worker.isCustomized());

        worker.restoreDefaults();
        assertFalse(worker.isCustomized());
        assertEquals(definition.grants(), worker.grants());

        OrganizationRole custom = OrganizationRole.custom(organization, "Foreman", null, Map.of());
        assertCode("ROLE_NOT_BUILT_IN", () -> custom.restoreDefaults());
    }

    @Test
    void newBuiltInRoleKnowsEveryAction() {
        assertEquals(EnumSet.allOf(PermissionAction.class), role("WORKER").knownActions());
    }

    @Test
    void syncOverwritesUncustomizedRole() {
        OrganizationRole worker = role("WORKER");
        BuiltInRoles.Definition changed = definition("WORKER", grants(VIEW_PROJECT, ORGANIZATION, VIEW_PRICES, ORGANIZATION));

        assertTrue(worker.syncFromFile(changed));
        assertEquals(changed.grants(), worker.grants());
        assertFalse(worker.isCustomized());
        assertFalse(worker.syncFromFile(changed), "a second sync changes nothing");
    }

    @Test
    void syncAddsOnlyUnknownActionsToCustomizedRole() {
        OrganizationRole worker = role("WORKER");
        Map<PermissionAction, PermissionScope> chosen = new EnumMap<>(BuiltInRoles.find("WORKER").orElseThrow().grants());
        chosen.put(MANAGE_PLAN, PROJECT);
        worker.replacePermissions(chosen);
        worker.forgetKnownAction(VIEW_PRICES);

        Map<PermissionAction, PermissionScope> file = new EnumMap<>(chosen);
        file.remove(MANAGE_PLAN);
        file.remove(VIEW_PLAN);
        file.put(VIEW_PRICES, ORGANIZATION);
        file.put(VIEW_BUDGET, ORGANIZATION);

        assertTrue(worker.syncFromFile(definition("WORKER", file)));
        Map<PermissionAction, PermissionScope> expected = new EnumMap<>(chosen);
        expected.put(VIEW_PRICES, ORGANIZATION);
        assertEquals(expected, worker.grants(), "VIEW_PRICES added, VIEW_PLAN kept, VIEW_BUDGET was already known");
        assertTrue(worker.isCustomized());
        assertEquals(EnumSet.allOf(PermissionAction.class), worker.knownActions());
        assertFalse(worker.syncFromFile(definition("WORKER", file)), "a second sync changes nothing");
    }

    @Test
    void customizedRoleSkipsNewDefaultWhoseRequirementWasRemoved() {
        OrganizationRole member = role("MEMBER");
        Map<PermissionAction, PermissionScope> chosen = new EnumMap<>(member.grants());
        chosen.remove(MANAGE_PLAN);
        chosen.remove(VIEW_PLAN);
        chosen.remove(VIEW_PRICES);
        member.replacePermissions(chosen);
        member.forgetKnownAction(MANAGE_PLAN);
        member.forgetKnownAction(VIEW_PRICES);
        member.forgetKnownAction(MANAGE_PRICES);

        Map<PermissionAction, PermissionScope> file = new EnumMap<>(BuiltInRoles.find("MEMBER").orElseThrow().grants());
        file.put(MANAGE_PRICES, ORGANIZATION);

        assertDoesNotThrow(() -> member.syncFromFile(definition("MEMBER", file)));
        Map<PermissionAction, PermissionScope> expected = new EnumMap<>(chosen);
        expected.put(VIEW_PRICES, ORGANIZATION);
        expected.put(MANAGE_PRICES, ORGANIZATION);
        assertEquals(expected, member.grants(), "MANAGE_PLAN skipped (VIEW_PLAN was removed); new prices chain added");
        assertDoesNotThrow(() -> PermissionRules.checkSet(member.grants()));
        assertEquals(EnumSet.allOf(PermissionAction.class), member.knownActions());
    }

    @Test
    void ownerHoldsEveryActionAndOnlyOwnerCanBeAskedTo() {
        OrganizationRole owner = role("OWNER");
        assertFalse(owner.holdEveryAction(), "a new Owner role already holds everything");
        assertEquals(16, owner.grants().size());
        assertThrows(IllegalStateException.class, role("WORKER")::holdEveryAction);
    }

    @Test
    void convertAndAdoptFlipKindAndKeepPermissions() {
        OrganizationRole resident = role("RESIDENT");
        Map<PermissionAction, PermissionScope> before = resident.grants();

        resident.convertToCustom();
        assertEquals(RoleKind.CUSTOM, resident.kind());
        assertEquals("RESIDENT", resident.getCode());
        assertFalse(resident.isCustomized());
        assertNull(resident.getDescription());
        assertEquals(before, resident.grants());
        assertThrows(IllegalStateException.class, resident::convertToCustom);

        resident.describe("Kept around");
        resident.adoptAsBuiltIn();
        assertEquals(RoleKind.BUILT_IN, resident.kind());
        assertTrue(resident.isCustomized());
        assertNull(resident.getDescription());
        assertEquals(before, resident.grants());
        assertEquals(EnumSet.allOf(PermissionAction.class), resident.knownActions());
        assertThrows(IllegalStateException.class, resident::adoptAsBuiltIn);
    }

    @Test
    void storedNameFollowsTheFile() {
        OrganizationRole worker = role("WORKER");
        assertFalse(worker.followFileName(BuiltInRoles.find("WORKER").orElseThrow()));
        assertTrue(worker.followFileName(definition("WORKER", Map.of())));
        assertEquals("Obrero", worker.getName());
    }

    private OrganizationRole role(String code) {
        return organization.getRoles().stream().filter(r -> r.getCode().equals(code)).findFirst().orElseThrow();
    }

    private static BuiltInRoles.Definition definition(String code, Map<PermissionAction, PermissionScope> grants) {
        return new BuiltInRoles.Definition(code, new LocalizedText("Laborer", "Obrero"),
                new LocalizedText("Field staff", "Personal de campo"), grants);
    }

    private static Map<PermissionAction, PermissionScope> grants(Object... pairs) {
        Map<PermissionAction, PermissionScope> map = new EnumMap<>(PermissionAction.class);
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((PermissionAction) pairs[i], (PermissionScope) pairs[i + 1]);
        }
        return map;
    }

    private static void assertCode(String code, Executable action) {
        RuleViolationException e = assertThrows(RuleViolationException.class, action);
        assertEquals(code, e.getCode());
    }
}
