package org.andre.construx.modules.organization.model;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.andre.construx.modules.organization.model.PermissionAction.*;
import static org.andre.construx.modules.organization.model.PermissionScope.*;
import static org.junit.jupiter.api.Assertions.*;

class BuiltInRolesDefinitionTest {
    private static final String OWNER = """
            owner:
              name: { en: Owner, es: Propietario }
              description: { en: Holds everything., es: Tiene todo. }
            """;

    @Test
    void parsesTheShippedFileWithSevenRolesInOrder() {
        assertEquals(List.of("ADMINISTRATOR", "MEMBER", "WORKER", "FINANCE", "RESIDENT", "SITE_MANAGER",
                        "SUPERINTENDENT"),
                BuiltInRoles.definitions().stream().map(BuiltInRoles.Definition::code).toList());

        BuiltInRoles.Definition worker = BuiltInRoles.find("WORKER").orElseThrow();
        assertEquals(new LocalizedText("Worker", "Trabajador"), worker.name());
        assertFalse(worker.description().en().isBlank());
        assertFalse(worker.description().es().isBlank());
        Map<PermissionAction, PermissionScope> expected = new EnumMap<>(PermissionAction.class);
        expected.put(VIEW_PROJECT, PROJECT);
        expected.put(VIEW_GOAL, PROJECT);
        expected.put(VIEW_ACTIVITY, PROJECT);
        expected.put(MANAGE_ACTIVITY, OWN);
        expected.put(VIEW_PLAN, PROJECT);
        assertEquals(expected, worker.grants());

        assertEquals(new LocalizedText("Site Manager", "Jefe de Obra"),
                BuiltInRoles.find("SITE_MANAGER").orElseThrow().name());
        assertTrue(BuiltInRoles.find("OWNER").isEmpty());
        assertTrue(BuiltInRoles.isBuiltInCode("FINANCE"));
        assertFalse(BuiltInRoles.isBuiltInCode("OWNER"));
    }

    @Test
    void ownerHasNamesFromTheFileAndEveryAction() {
        BuiltInRoles.Definition owner = BuiltInRoles.owner();
        assertEquals("OWNER", owner.code());
        assertEquals(new LocalizedText("Owner", "Propietario"), owner.name());
        assertEquals(16, owner.grants().size());
        assertTrue(owner.grants().values().stream().allMatch(s -> s == ORGANIZATION));
    }

    @Test
    void parsesNamesDescriptionsAndGrants() {
        var defs = BuiltInRoles.parse(yaml(OWNER + """
                roles:
                  - code: SURVEYOR
                    name: { en: Surveyor, es: Topógrafo }
                    description: { en: Measures., es: Mide. }
                    grants:
                      VIEW_PROJECT: PROJECT
                      VIEW_ACTIVITY: OWN
                """));
        var role = defs.roles().get(0);
        assertEquals("SURVEYOR", role.code());
        assertEquals(new LocalizedText("Surveyor", "Topógrafo"), role.name());
        assertEquals(Map.of(VIEW_PROJECT, PROJECT, VIEW_ACTIVITY, OWN), role.grants());
    }

    @Test
    void rejectsOwnerCodeCustomPrefixDuplicatesBadNamesAndInvalidGrants() {
        assertRejected(roles(entry("OWNER", "A", "B", "VIEW_PROJECT: PROJECT")));
        assertRejected(roles(entry("CUSTOM_X", "A", "B", "VIEW_PROJECT: PROJECT")));
        assertRejected(roles(entry("lower", "A", "B", "VIEW_PROJECT: PROJECT")));
        assertRejected(roles(entry("A", "A", "B", ""), entry("A", "C", "D", "")));
        assertRejected(roles(entry("A", "Same", "B", ""), entry("C", "same", "D", "")));
        assertRejected(roles(entry("A", "Uno", "Same", ""), entry("C", "Two", "SAME", "")));
        assertRejected(roles(entry("A", "x".repeat(101), "B", "")));
        assertRejected(roles(entry("A", " ", "B", "")));
        assertRejected(roles(entry("A", "Owner", "B", "")));
        assertRejected(roles(entry("A", "A", "B", "MANAGE_PLAN: PROJECT\n      VIEW_PROJECT: PROJECT")));
        assertRejected(roles(entry("A", "A", "B", "VIEW_PRICES: PROJECT")));
        assertRejected(roles(entry("A", "A", "B", "FLY: ORGANIZATION")));
        assertRejected(roles(entry("A", "A", "B", "VIEW_PROJECT: GALAXY")));
        assertRejected(roles(entry("A", "A", "B", "VIEW_PROJECT: PROJECT\n      VIEW_PROJECT: ORGANIZATION")));
        // Missing translation of a description.
        assertRejected(roles(entry("A", "A", "B", "").replace("es: Desc. }", "}")));
        // Missing owner names.
        assertRejected("roles: []\n");
        // A valid file passes, so the cases above fail for their own reason.
        assertDoesNotThrow(() -> BuiltInRoles.parse(BuiltInRoles.readYaml(
                roles(entry("A", "A", "B", "VIEW_PROJECT: PROJECT"), entry("C", "C", "D", "")))));
    }

    @Test
    void builtInNameMatchIsCaseAndLanguageInsensitive() {
        assertTrue(BuiltInRoles.isBuiltInName("worker"));
        assertTrue(BuiltInRoles.isBuiltInName(" TRABAJADOR "));
        assertTrue(BuiltInRoles.isBuiltInName("propietario"));
        assertTrue(BuiltInRoles.isBuiltInName("Site manager"));
        assertFalse(BuiltInRoles.isBuiltInName("Foreman with prices"));
        assertFalse(BuiltInRoles.isBuiltInName(null));
    }

    private static String roles(String... entries) {
        return OWNER + "roles:\n" + String.join("", entries);
    }

    private static String entry(String code, String en, String es, String grants) {
        return "  - code: " + code + "\n"
                + "    name: { en: \"" + en + "\", es: \"" + es + "\" }\n"
                + "    description: { en: Desc., es: Desc. }\n"
                + "    grants:" + (grants.isEmpty() ? " {}" : "\n      " + grants) + "\n";
    }

    private static void assertRejected(String text) {
        assertThrows(IllegalStateException.class, () -> BuiltInRoles.parse(BuiltInRoles.readYaml(text)), text);
    }

    private static Map<String, Object> yaml(String text) {
        return new Yaml().load(text);
    }
}
