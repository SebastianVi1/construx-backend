package org.andre.construx.modules.organization.model;

import org.andre.construx.shared.exception.RuleViolationException;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.andre.construx.modules.organization.model.PermissionAction.*;
import static org.andre.construx.modules.organization.model.PermissionScope.*;
import static org.junit.jupiter.api.Assertions.*;

class PermissionRulesTest {

    /** Contract §4.1, written out independently of the code. */
    private static final Map<PermissionAction, Set<PermissionScope>> ALLOWED = new EnumMap<>(PermissionAction.class);

    static {
        for (PermissionAction a : new PermissionAction[] {MANAGE_ORGANIZATION, MANAGE_ROLES, MANAGE_MEMBERS,
                VIEW_PRICES, MANAGE_PRICES, VIEW_BUDGET, MANAGE_BUDGET}) {
            ALLOWED.put(a, EnumSet.of(ORGANIZATION));
        }
        for (PermissionAction a : new PermissionAction[] {VIEW_PROJECT, MANAGE_PROJECT, VIEW_GOAL, MANAGE_GOAL,
                ASSIGN_ACTIVITY, VIEW_PLAN, MANAGE_PLAN}) {
            ALLOWED.put(a, EnumSet.of(PROJECT, ORGANIZATION));
        }
        ALLOWED.put(VIEW_ACTIVITY, EnumSet.of(OWN, PROJECT, ORGANIZATION));
        ALLOWED.put(MANAGE_ACTIVITY, EnumSet.of(OWN, PROJECT, ORGANIZATION));
    }

    @Test
    void scopesMatchTheOldValidateRules() {
        assertEquals(16, ALLOWED.size());
        for (PermissionAction action : PermissionAction.values()) {
            assertEquals(ALLOWED.get(action), action.scopes(), action.name());
            for (PermissionScope scope : PermissionScope.values()) {
                if (ALLOWED.get(action).contains(scope)) {
                    assertDoesNotThrow(() -> PermissionRules.checkScope(action, scope), action + ":" + scope);
                } else {
                    RuleViolationException ex = assertThrows(RuleViolationException.class,
                            () -> PermissionRules.checkScope(action, scope), action + ":" + scope);
                    assertEquals("ROLE_SCOPE_INVALID", ex.getCode());
                }
            }
        }
        // Existing callers still get an IllegalArgumentException.
        assertThrows(IllegalArgumentException.class, () -> Permission.validate(VIEW_PRICES, PROJECT));
    }

    @Test
    void manageNeedsViewAtSameOrWiderScope() {
        RoleDependencyException ex = assertThrows(RoleDependencyException.class,
                () -> PermissionRules.checkSet(set(MANAGE_PLAN, PROJECT, VIEW_PROJECT, PROJECT)));
        assertEquals("ROLE_DEPENDENCY_MISSING", ex.getCode());
        assertEquals(MANAGE_PLAN, ex.action());
        assertEquals(VIEW_PLAN, ex.requires());

        assertDoesNotThrow(() -> PermissionRules.checkSet(
                set(MANAGE_PLAN, PROJECT, VIEW_PROJECT, PROJECT, VIEW_PLAN, PROJECT)));
        RoleDependencyException wider = assertThrows(RoleDependencyException.class, () -> PermissionRules.checkSet(
                set(MANAGE_PLAN, ORGANIZATION, VIEW_PROJECT, ORGANIZATION, VIEW_PLAN, PROJECT)));
        assertEquals(VIEW_PLAN, wider.requires());
    }

    @Test
    void siteActionsNeedViewProjectAtLeastProject() {
        RoleDependencyException ex = assertThrows(RoleDependencyException.class,
                () -> PermissionRules.checkSet(set(VIEW_ACTIVITY, OWN, MANAGE_ACTIVITY, OWN)));
        assertEquals(VIEW_PROJECT, ex.requires());
        assertDoesNotThrow(() -> PermissionRules.checkSet(
                set(VIEW_ACTIVITY, OWN, MANAGE_ACTIVITY, OWN, VIEW_PROJECT, PROJECT)));
    }

    @Test
    void pricesAndBudgetsNeedTheirViewAction() {
        assertEquals(VIEW_PRICES, assertThrows(RoleDependencyException.class,
                () -> PermissionRules.checkSet(set(MANAGE_PRICES, ORGANIZATION))).requires());
        assertEquals(VIEW_BUDGET, assertThrows(RoleDependencyException.class,
                () -> PermissionRules.checkSet(set(MANAGE_BUDGET, ORGANIZATION))).requires());
    }

    @Test
    void scopeOutsideTheCatalogFailsTheSet() {
        RuleViolationException ex = assertThrows(RuleViolationException.class,
                () -> PermissionRules.checkSet(set(VIEW_PRICES, PROJECT)));
        assertEquals("ROLE_SCOPE_INVALID", ex.getCode());
    }

    @Test
    void emptySetIsValid() {
        assertDoesNotThrow(() -> PermissionRules.checkSet(Map.of()));
    }

    @Test
    void scopeOrderIsOwnThenProjectThenOrganization() {
        assertTrue(PermissionRules.atLeast(ORGANIZATION, PROJECT));
        assertTrue(PermissionRules.atLeast(PROJECT, PROJECT));
        assertTrue(PermissionRules.atLeast(PROJECT, OWN));
        assertFalse(PermissionRules.atLeast(OWN, PROJECT));
        assertFalse(PermissionRules.atLeast(PROJECT, ORGANIZATION));
    }

    @Test
    void everyCurrentBuiltInGrantSetPasses() {
        Map<String, Map<PermissionAction, PermissionScope>> roles = new LinkedHashMap<>();
        roles.put("ADMINISTRATOR", set(MANAGE_ROLES, ORGANIZATION, MANAGE_MEMBERS, ORGANIZATION,
                VIEW_PROJECT, ORGANIZATION, MANAGE_PROJECT, ORGANIZATION, VIEW_GOAL, ORGANIZATION,
                MANAGE_GOAL, ORGANIZATION, VIEW_ACTIVITY, ORGANIZATION, MANAGE_ACTIVITY, ORGANIZATION,
                ASSIGN_ACTIVITY, ORGANIZATION, VIEW_PRICES, ORGANIZATION, MANAGE_PRICES, ORGANIZATION,
                VIEW_BUDGET, ORGANIZATION, VIEW_PLAN, ORGANIZATION, MANAGE_PLAN, ORGANIZATION));
        roles.put("MEMBER", set(VIEW_PROJECT, ORGANIZATION, VIEW_GOAL, ORGANIZATION, MANAGE_GOAL, ORGANIZATION,
                VIEW_ACTIVITY, ORGANIZATION, MANAGE_ACTIVITY, ORGANIZATION, ASSIGN_ACTIVITY, ORGANIZATION,
                VIEW_PRICES, ORGANIZATION, VIEW_PLAN, PROJECT, MANAGE_PLAN, PROJECT));
        roles.put("WORKER", set(VIEW_PROJECT, PROJECT, VIEW_GOAL, PROJECT, VIEW_ACTIVITY, PROJECT,
                MANAGE_ACTIVITY, OWN, VIEW_PLAN, PROJECT));
        roles.put("FINANCE", set(VIEW_PROJECT, ORGANIZATION, VIEW_GOAL, ORGANIZATION, VIEW_ACTIVITY, ORGANIZATION,
                VIEW_PRICES, ORGANIZATION, MANAGE_PRICES, ORGANIZATION, VIEW_BUDGET, ORGANIZATION,
                MANAGE_BUDGET, ORGANIZATION));
        roles.put("RESIDENT", set(VIEW_PROJECT, PROJECT, VIEW_GOAL, PROJECT, MANAGE_GOAL, PROJECT,
                VIEW_ACTIVITY, PROJECT, MANAGE_ACTIVITY, PROJECT, ASSIGN_ACTIVITY, PROJECT,
                VIEW_PLAN, PROJECT, MANAGE_PLAN, PROJECT));
        roles.put("SITE_MANAGER", set(VIEW_PROJECT, PROJECT, MANAGE_PROJECT, PROJECT, VIEW_GOAL, PROJECT,
                MANAGE_GOAL, PROJECT, VIEW_ACTIVITY, PROJECT, MANAGE_ACTIVITY, PROJECT, ASSIGN_ACTIVITY, PROJECT,
                VIEW_PLAN, PROJECT, MANAGE_PLAN, PROJECT));
        roles.put("SUPERINTENDENT", set(VIEW_PROJECT, ORGANIZATION, VIEW_GOAL, ORGANIZATION,
                MANAGE_GOAL, ORGANIZATION, VIEW_ACTIVITY, ORGANIZATION, MANAGE_ACTIVITY, ORGANIZATION,
                ASSIGN_ACTIVITY, ORGANIZATION, VIEW_PLAN, ORGANIZATION, MANAGE_PLAN, ORGANIZATION,
                VIEW_PRICES, ORGANIZATION, VIEW_BUDGET, ORGANIZATION));
        Map<PermissionAction, PermissionScope> owner = new EnumMap<>(PermissionAction.class);
        for (PermissionAction action : PermissionAction.values()) owner.put(action, ORGANIZATION);
        roles.put("OWNER", owner);

        roles.forEach((code, grants) -> assertDoesNotThrow(() -> PermissionRules.checkSet(grants), code));
    }

    private static Map<PermissionAction, PermissionScope> set(Object... pairs) {
        Map<PermissionAction, PermissionScope> map = new EnumMap<>(PermissionAction.class);
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((PermissionAction) pairs[i], (PermissionScope) pairs[i + 1]);
        }
        return map;
    }
}
