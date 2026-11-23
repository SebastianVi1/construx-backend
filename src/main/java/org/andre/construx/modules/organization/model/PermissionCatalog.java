package org.andre.construx.modules.organization.model;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The permission catalog served to the dashboard (contract roles §5.10): the structure from {@link PermissionAction}
 * plus the EN/ES labels from {@code permission-catalog.yml}. Loaded once; a missing or unknown label fails the load,
 * and therefore the boot.
 */
public final class PermissionCatalog {
    private static final String RESOURCE = "/permission-catalog.yml";
    private static final CatalogData CATALOG = load();

    private PermissionCatalog() {
    }

    public record Group(PermissionGroup key, LocalizedText names) {}

    public record Action(PermissionAction action, PermissionGroup group, LocalizedText names,
                         LocalizedText descriptions, List<PermissionScope> scopes,
                         List<PermissionAction.Requirement> requires) {}

    public record CatalogData(List<Group> groups, List<Action> actions) {}

    public static CatalogData get() {
        return CATALOG;
    }

    /** Builds the catalog from the parsed YAML; throws {@link IllegalStateException} naming what is wrong. */
    public static CatalogData parse(Map<String, Object> yaml) {
        if (yaml == null) {
            throw new IllegalStateException("permission-catalog.yml is empty");
        }
        Map<String, Object> groups = section(yaml, "groups");
        Map<String, Object> actions = section(yaml, "actions");
        checkKeys("group", groups.keySet(), PermissionGroup.values());
        checkKeys("action", actions.keySet(), PermissionAction.values());

        List<Group> groupList = new ArrayList<>();
        for (PermissionGroup group : PermissionGroup.values()) {
            Map<String, Object> entry = map(groups.get(group.name()), "group " + group);
            groupList.add(new Group(group, text(entry.get("name"), "group " + group + " name")));
        }
        List<Action> actionList = new ArrayList<>();
        for (PermissionAction action : PermissionAction.values()) {
            Map<String, Object> entry = map(actions.get(action.name()), "action " + action);
            actionList.add(new Action(action, action.group(),
                    text(entry.get("name"), "action " + action + " name"),
                    text(entry.get("description"), "action " + action + " description"),
                    List.copyOf(action.scopes()), action.requires()));
        }
        return new CatalogData(List.copyOf(groupList), List.copyOf(actionList));
    }

    /**
     * Reads {@code { en: …, es: … }}, both 1 to {@code max} characters after trimming. Shared with the Built-in role
     * definition file.
     */
    static LocalizedText text(Object value, String what, int max) {
        Map<String, Object> map = map(value, what);
        return new LocalizedText(single(map.get("en"), what + " (en)", max), single(map.get("es"), what + " (es)", max));
    }

    private static LocalizedText text(Object value, String what) {
        return text(value, what, 500);
    }

    private static String single(Object value, String what, int max) {
        if (!(value instanceof String s) || s.isBlank() || s.strip().length() > max) {
            throw new IllegalStateException("Missing or invalid text for " + what + ": 1 to " + max + " characters");
        }
        return s.strip();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value, String what) {
        if (!(value instanceof Map<?, ?> m)) {
            throw new IllegalStateException("Missing or invalid entry for " + what);
        }
        return (Map<String, Object>) m;
    }

    /** Reads YAML text with duplicate keys refused; a syntax error becomes an {@link IllegalStateException}. */
    static Map<String, Object> readYaml(String text, String file) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try {
            return new Yaml(new SafeConstructor(options)).load(text);
        } catch (YAMLException | ClassCastException e) {
            throw new IllegalStateException(file + " is not valid: " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> section(Map<String, Object> yaml, String key) {
        return map(yaml.get(key), "`" + key + "` in permission-catalog.yml");
    }

    private static void checkKeys(String what, Set<String> keys, Enum<?>[] known) {
        Set<String> unknown = new HashSet<>(keys);
        for (Enum<?> value : known) {
            unknown.remove(value.name());
        }
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("permission-catalog.yml names unknown " + what + "(s): " + unknown);
        }
    }

    private static CatalogData load() {
        try (InputStream in = PermissionCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " not found");
            }
            return parse(readYaml(new String(in.readAllBytes(), StandardCharsets.UTF_8), "permission-catalog.yml"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
