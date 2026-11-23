package org.andre.construx.modules.organization.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The Built-in roles every company has besides Owner, and the Owner role's names, as defined in
 * {@code built-in-roles.yml} (contract roles §6.1, ADR 0010). Loaded once; an invalid file fails the load, and
 * therefore the boot.
 */
public final class BuiltInRoles {
    public static final String OWNER_CODE = "OWNER";
    public static final String CUSTOM_PREFIX = "CUSTOM_";
    private static final String RESOURCE = "/built-in-roles.yml";
    private static final int MAX_NAME = 100;
    private static final int MAX_DESCRIPTION = 280;
    private static final Definitions DEFINITIONS = load();

    private BuiltInRoles() {
    }

    /** One role of the file. {@code grants} is the default permission set; the Owner's holds every action. */
    public record Definition(String code, LocalizedText name, LocalizedText description,
                             Map<PermissionAction, PermissionScope> grants) {
        public Definition {
            Map<PermissionAction, PermissionScope> copy = new EnumMap<>(PermissionAction.class);
            copy.putAll(grants);
            grants = Collections.unmodifiableMap(copy);
        }
    }

    public record Definitions(Definition owner, List<Definition> roles) {}

    /** The Built-in roles in file order, without the Owner role. */
    public static List<Definition> definitions() {
        return DEFINITIONS.roles();
    }

    public static Optional<Definition> find(String code) {
        return DEFINITIONS.roles().stream().filter(d -> d.code().equals(code)).findFirst();
    }

    public static boolean isBuiltInCode(String code) {
        return find(code).isPresent();
    }

    public static Definition owner() {
        return DEFINITIONS.owner();
    }

    /** Whether {@code name} equals any Built-in or Owner name, in either language, ignoring case and outer spaces. */
    public static boolean isBuiltInName(String name) {
        if (name == null) {
            return false;
        }
        String key = OrganizationRole.nameKey(name);
        return allNames(DEFINITIONS).stream().anyMatch(key::equals);
    }

    /** Reads YAML text with duplicate keys refused; a YAML syntax error becomes an {@link IllegalStateException}. */
    public static Map<String, Object> readYaml(String text) {
        return PermissionCatalog.readYaml(text, "built-in-roles.yml");
    }

    /** Builds and checks the definitions; throws {@link IllegalStateException} naming what is wrong. */
    public static Definitions parse(Map<String, Object> yaml) {
        if (yaml == null) {
            throw new IllegalStateException("built-in-roles.yml is empty");
        }
        Map<String, Object> ownerEntry = PermissionCatalog.map(yaml.get("owner"), "`owner` in built-in-roles.yml");
        Map<PermissionAction, PermissionScope> everything = new EnumMap<>(PermissionAction.class);
        for (PermissionAction action : PermissionAction.values()) {
            everything.put(action, PermissionScope.ORGANIZATION);
        }
        Definition owner = new Definition(OWNER_CODE, name(ownerEntry, OWNER_CODE), description(ownerEntry, OWNER_CODE),
                everything);

        if (!(yaml.get("roles") instanceof List<?> list)) {
            throw new IllegalStateException("built-in-roles.yml must have a `roles` list");
        }
        List<Definition> roles = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (Object item : list) {
            Map<String, Object> entry = PermissionCatalog.map(item, "a role in built-in-roles.yml");
            String code = code(entry.get("code"));
            if (!codes.add(code)) {
                throw new IllegalStateException("Duplicate Built-in role code: " + code);
            }
            roles.add(new Definition(code, name(entry, code), description(entry, code), grants(code, entry.get("grants"))));
        }
        Definitions definitions = new Definitions(owner, List.copyOf(roles));
        checkUniqueNames(definitions);
        return definitions;
    }

    private static String code(Object value) {
        if (!(value instanceof String code) || !code.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalStateException("Invalid Built-in role code: " + value
                    + " (use [A-Z][A-Z0-9_], at most 64 characters)");
        }
        if (OWNER_CODE.equals(code) || code.startsWith(CUSTOM_PREFIX)) {
            throw new IllegalStateException("Reserved Built-in role code: " + code
                    + " (OWNER and codes starting with " + CUSTOM_PREFIX + " are not allowed)");
        }
        return code;
    }

    private static LocalizedText name(Map<String, Object> entry, String code) {
        return PermissionCatalog.text(entry.get("name"), "Built-in role " + code + " name", MAX_NAME);
    }

    private static LocalizedText description(Map<String, Object> entry, String code) {
        return PermissionCatalog.text(entry.get("description"), "Built-in role " + code + " description",
                MAX_DESCRIPTION);
    }

    private static Map<PermissionAction, PermissionScope> grants(String code, Object value) {
        Map<PermissionAction, PermissionScope> grants = new EnumMap<>(PermissionAction.class);
        if (value == null) {
            return grants;
        }
        PermissionCatalog.map(value, "Built-in role " + code + " grants").forEach((action, scope) -> {
            try {
                grants.put(PermissionAction.valueOf(String.valueOf(action)), PermissionScope.valueOf(String.valueOf(scope)));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Built-in role " + code + ": unknown grant `" + action + ": " + scope + "`");
            }
        });
        try {
            PermissionRules.checkSet(grants);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Built-in role " + code + ": " + e.getMessage(), e);
        }
        return grants;
    }

    /** No name, in either language, may be shared by two roles (Owner included); one role may use a name twice. */
    private static void checkUniqueNames(Definitions definitions) {
        Map<String, String> owners = new HashMap<>();
        List<Definition> all = new ArrayList<>(definitions.roles());
        all.add(0, definitions.owner());
        for (Definition definition : all) {
            for (String name : new HashSet<>(List.of(OrganizationRole.nameKey(definition.name().en()),
                    OrganizationRole.nameKey(definition.name().es())))) {
                String previous = owners.putIfAbsent(name, definition.code());
                if (previous != null) {
                    throw new IllegalStateException("Built-in roles " + previous + " and " + definition.code()
                            + " share the name `" + name + "`");
                }
            }
        }
    }

    private static Set<String> allNames(Definitions definitions) {
        Set<String> names = new HashSet<>();
        names.add(OrganizationRole.nameKey(definitions.owner().name().en()));
        names.add(OrganizationRole.nameKey(definitions.owner().name().es()));
        definitions.roles().forEach(d -> {
            names.add(OrganizationRole.nameKey(d.name().en()));
            names.add(OrganizationRole.nameKey(d.name().es()));
        });
        return names;
    }


    private static Definitions load() {
        try (InputStream in = BuiltInRoles.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " not found");
            }
            return parse(readYaml(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
