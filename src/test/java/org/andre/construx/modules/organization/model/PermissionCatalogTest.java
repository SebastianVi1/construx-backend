package org.andre.construx.modules.organization.model;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PermissionCatalogTest {

    @Test
    void everyActionAndGroupHasEnglishAndSpanishNameAndDescription() {
        PermissionCatalog.CatalogData catalog = PermissionCatalog.get();
        assertEquals(List.of(PermissionGroup.values()),
                catalog.groups().stream().map(PermissionCatalog.Group::key).toList());
        assertEquals(List.of(PermissionAction.values()),
                catalog.actions().stream().map(PermissionCatalog.Action::action).toList());
        catalog.groups().forEach(g -> assertFilled(g.names(), g.key().name()));
        catalog.actions().forEach(a -> {
            assertFilled(a.names(), a.action().name());
            assertFilled(a.descriptions(), a.action().name());
        });
        PermissionCatalog.Action managePlan = catalog.actions().stream()
                .filter(a -> a.action() == PermissionAction.MANAGE_PLAN).findFirst().orElseThrow();
        assertEquals(new LocalizedText("Edit plans", "Editar planos"), managePlan.names());
        assertEquals(PermissionGroup.PLANS, managePlan.group());
        assertEquals(List.of(PermissionScope.PROJECT, PermissionScope.ORGANIZATION), managePlan.scopes());
    }

    @Test
    void aMissingTranslationFailsTheLoad() {
        Map<String, Object> yaml = new Yaml().load(PermissionCatalog.class.getResourceAsStream("/permission-catalog.yml"));
        @SuppressWarnings("unchecked")
        Map<String, Object> actions = (Map<String, Object>) yaml.get("actions");
        @SuppressWarnings("unchecked")
        Map<String, Object> viewPlan = (Map<String, Object>) actions.get("VIEW_PLAN");
        viewPlan.put("description", Map.of("en", "See site plans"));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> PermissionCatalog.parse(yaml));
        assertTrue(ex.getMessage().contains("VIEW_PLAN"), ex.getMessage());

        actions.remove("VIEW_PLAN");
        assertThrows(IllegalStateException.class, () -> PermissionCatalog.parse(yaml));
    }

    @Test
    void anUnknownActionOrGroupFailsTheLoad() {
        Map<String, Object> yaml = new Yaml().load(PermissionCatalog.class.getResourceAsStream("/permission-catalog.yml"));
        @SuppressWarnings("unchecked")
        Map<String, Object> groups = (Map<String, Object>) yaml.get("groups");
        groups.put("SPACESHIPS", Map.of("name", Map.of("en", "Spaceships", "es", "Naves")));
        assertThrows(IllegalStateException.class, () -> PermissionCatalog.parse(yaml));
    }

    private static void assertFilled(LocalizedText text, String what) {
        assertNotNull(text, what);
        assertFalse(text.en() == null || text.en().isBlank(), what + " en");
        assertFalse(text.es() == null || text.es().isBlank(), what + " es");
    }
}
