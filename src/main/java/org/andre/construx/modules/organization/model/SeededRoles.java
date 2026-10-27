package org.andre.construx.modules.organization.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Built-in roles every new organization starts with, besides the owner.
 * The definition lives in {@code seeded-roles.properties} so it can change without touching code.
 */
final class SeededRoles {
    private static final String RESOURCE = "/seeded-roles.properties";
    private static final Definitions DEFINITIONS = load();

    private SeededRoles() {
    }

    record Definition(String code, String name, Map<PermissionAction, PermissionScope> grants) {
    }

    record Definitions(List<Definition> roles) {
        Set<String> codes() {
            return roles.stream().map(Definition::code).collect(Collectors.toSet());
        }
    }

    static boolean isSeededCode(String code) {
        return DEFINITIONS.codes().contains(code);
    }

    static List<OrganizationRole> create(Organization organization) {
        return DEFINITIONS.roles().stream()
                .map(d -> OrganizationRole.seeded(organization, d.code(), d.name(), d.grants()))
                .toList();
    }

    static Definitions parse(Properties props) {
        String list = props.getProperty("roles", "").strip();
        if (list.isEmpty()) {
            throw new IllegalStateException("seeded-roles.properties must define `roles`");
        }
        List<Definition> roles = new ArrayList<>();
        for (String raw : list.split(",")) {
            String code = raw.strip();
            if (!code.matches("[A-Z][A-Z0-9_]{0,63}") || code.equals("OWNER")) {
                throw new IllegalStateException("Invalid seeded role code: " + code);
            }
            if (roles.stream().anyMatch(r -> r.code().equals(code))) {
                throw new IllegalStateException("Duplicate seeded role code: " + code);
            }
            String name = props.getProperty("role." + code + ".name", "").strip();
            if (name.isEmpty() || name.length() > 100) {
                throw new IllegalStateException("Seeded role " + code + " needs a name of 1 to 100 characters");
            }
            roles.add(new Definition(code, name, parseGrants(code, props.getProperty("role." + code + ".grants", ""))));
        }
        return new Definitions(List.copyOf(roles));
    }

    private static Map<PermissionAction, PermissionScope> parseGrants(String code, String value) {
        Map<PermissionAction, PermissionScope> grants = new EnumMap<>(PermissionAction.class);
        for (String pair : value.split(",")) {
            if (pair.isBlank()) {
                continue;
            }
            String[] parts = pair.strip().split(":");
            if (parts.length != 2) {
                throw new IllegalStateException("Seeded role " + code + ": bad grant `" + pair.strip() + "`");
            }
            try {
                PermissionAction action = PermissionAction.valueOf(parts[0].strip());
                if (grants.put(action, PermissionScope.valueOf(parts[1].strip())) != null) {
                    throw new IllegalStateException("Seeded role " + code + ": duplicate action " + action);
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Seeded role " + code + ": bad grant `" + pair.strip() + "`", e);
            }
        }
        return grants;
    }

    private static Definitions load() {
        try (InputStream in = SeededRoles.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " not found");
            }
            Properties props = new Properties();
            props.load(in);
            Definitions definitions = parse(props);
            // Fail at startup, not on first signup: every pair must pass the Permission rules.
            definitions.roles().forEach(d -> d.grants().forEach(Permission::validate));
            return definitions;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
