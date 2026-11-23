package org.andre.construx.modules.organization.model;

import org.andre.construx.shared.exception.RuleViolationException;
import org.andre.construx.shared.validation.DomainValidation;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A company's role: the Owner role, a Built-in role from {@code built-in-roles.yml}, or a Custom role the company
 * made (contract roles §2, ADR 0010). In the database a Built-in role is {@code seeded} and the Owner role is
 * {@code systemRole}.
 */
@Entity
@Table(name = "organization_role", uniqueConstraints = {
        @UniqueConstraint(name = "uk_role_org_code", columnNames = {"organization_id", "code"}),
        @UniqueConstraint(name = "uk_role_org_name", columnNames = {"organization_id", "name"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrganizationRole {
    public static final int MAX_NAME = 100;
    public static final int MAX_DESCRIPTION = 280;
    private static final Logger log = LoggerFactory.getLogger(OrganizationRole.class);
    private static final String CODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int CODE_LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @NotBlank @Size(max = 64)
    @Column(nullable = false, length = 64)
    private String code;

    /** Custom roles: the name the company chose. Owner and Built-in roles: the ES name, for database readability. */
    @NotBlank @Size(max = MAX_NAME)
    @Column(nullable = false, length = MAX_NAME)
    private String name;

    /** Custom roles only; Built-in descriptions come from the definition file. */
    @Size(max = MAX_DESCRIPTION)
    @Column(length = MAX_DESCRIPTION)
    private String description;

    @Column(nullable = false)
    private boolean systemRole;

    @Column(nullable = false)
    private boolean seeded;

    /** A Built-in role whose permissions the company changed; the sync then keeps its choices. */
    @ColumnDefault("false")
    @Column(nullable = false)
    private boolean customized;

    @Version
    @ColumnDefault("0")
    @Column(nullable = false)
    private long version;

    @OneToMany(mappedBy = "role", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<Permission> permissions = new HashSet<>();

    /** The catalog actions this Built-in role has been reconciled against (§6.2); unused by other roles. */
    @ElementCollection
    @CollectionTable(name = "organization_role_known_action", joinColumns = @JoinColumn(name = "role_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 40)
    private Set<PermissionAction> knownActions = new HashSet<>();

    public static OrganizationRole owner(Organization organization) {
        OrganizationRole role = new OrganizationRole();
        role.organization = DomainValidation.required(organization, "Organization is required");
        role.code = BuiltInRoles.OWNER_CODE;
        role.name = BuiltInRoles.owner().name().es();
        role.systemRole = true;
        role.setGrants(everyAction());
        return role;
    }

    /** A Built-in role with the file's defaults, uncustomized and reconciled against every catalog action. */
    public static OrganizationRole builtIn(Organization organization, BuiltInRoles.Definition definition) {
        OrganizationRole role = new OrganizationRole();
        role.organization = DomainValidation.required(organization, "Organization is required");
        role.code = definition.code();
        role.name = definition.name().es();
        role.seeded = true;
        role.setGrants(definition.grants());
        role.markEveryActionKnown();
        return role;
    }

    /**
     * A Custom role with a generated permanent code ({@code CUSTOM_} plus 10 base-32 characters). Name uniqueness
     * within the company and against Built-in names is checked by the caller, which can see the other roles.
     */
    public static OrganizationRole custom(Organization organization, String name, String description,
                                          Map<PermissionAction, PermissionScope> grants) {
        OrganizationRole role = new OrganizationRole();
        role.organization = DomainValidation.required(organization, "Organization is required");
        role.code = generateCode();
        role.name = validName(name);
        role.description = validDescription(description);
        PermissionRules.checkSet(grants);
        role.setGrants(grants);
        return role;
    }

    public RoleKind kind() {
        if (systemRole) {
            return RoleKind.OWNER;
        }
        return seeded ? RoleKind.BUILT_IN : RoleKind.CUSTOM;
    }

    public Map<PermissionAction, PermissionScope> grants() {
        Map<PermissionAction, PermissionScope> grants = new EnumMap<>(PermissionAction.class);
        permissions.forEach(p -> grants.put(p.getAction(), p.getScope()));
        return grants;
    }

    public Set<PermissionAction> knownActions() {
        Set<PermissionAction> known = EnumSet.noneOf(PermissionAction.class);
        known.addAll(knownActions);
        return known;
    }

    /**
     * Replaces the permission set after checking it (§4). A Built-in role becomes customized, or uncustomized again
     * when the result equals the file's defaults.
     */
    public void replacePermissions(Map<PermissionAction, PermissionScope> grants) {
        requireNotOwner();
        PermissionRules.checkSet(grants);
        setGrants(grants);
        if (seeded) {
            customized = BuiltInRoles.find(code).map(d -> !d.grants().equals(grants())).orElse(true);
            markEveryActionKnown();
        }
    }

    public void restoreDefaults(BuiltInRoles.Definition definition) {
        requireNotOwner();
        if (!seeded) {
            throw new RuleViolationException("ROLE_NOT_BUILT_IN", "Only a Built-in role has defaults to restore");
        }
        setGrants(definition.grants());
        customized = false;
        markEveryActionKnown();
    }

    public void renameCustom(String name) {
        requireCustomText();
        this.name = validName(name);
    }

    public void describe(String description) {
        requireCustomText();
        this.description = validDescription(description);
    }

    /** Startup sync, Owner role: holds every catalog action at {@code ORGANIZATION}. Returns whether that changed. */
    public boolean holdEveryAction() {
        if (!systemRole) {
            throw new IllegalStateException("Only the Owner role holds every action");
        }
        return setGrants(everyAction());
    }

    /**
     * Startup sync of a Built-in role's permissions (§6.2 step 2). An uncustomized role takes the file's defaults
     * exactly. A customized role keeps its choices and gains only the defaults for actions it has never been
     * reconciled against, leaving out (with a warning) any that would break a requirement the company removed.
     * Either way every catalog action is then known. Returns whether the permissions changed.
     */
    public boolean syncFromFile(BuiltInRoles.Definition definition) {
        if (!seeded || !code.equals(definition.code())) {
            throw new IllegalStateException("Role " + code + " cannot follow definition " + definition.code());
        }
        boolean changed = setGrants(customized ? withNewDefaults(definition) : definition.grants());
        markEveryActionKnown();
        return changed;
    }

    /** Startup sync: the stored name becomes the file's ES name. Returns whether it changed. */
    public boolean followFileName(BuiltInRoles.Definition definition) {
        if (!seeded) {
            throw new IllegalStateException("Only a Built-in role follows the file's name");
        }
        String fileName = definition.name().es();
        if (fileName.equals(name)) {
            return false;
        }
        this.name = fileName;
        return true;
    }

    /** Startup sync: a Custom role whose code is back in the file becomes a customized Built-in role again. */
    public void adoptAsBuiltIn() {
        requireNotOwner();
        if (seeded) {
            throw new IllegalStateException("Role " + code + " is already Built-in");
        }
        seeded = true;
        customized = true;
        description = null;
        markEveryActionKnown();
    }

    /** Startup sync: a Built-in role removed from the file becomes Custom, keeping members, permissions and name. */
    public void convertToCustom() {
        requireNotOwner();
        if (!seeded) {
            throw new IllegalStateException("Role " + code + " is not Built-in");
        }
        seeded = false;
        customized = false;
        description = null;
        knownActions.clear();
    }

    public Set<Permission> getPermissions() {
        return Collections.unmodifiableSet(permissions);
    }

    /** For tests: the role behaves as if reconciled before {@code action} joined the catalog. */
    void forgetKnownAction(PermissionAction action) {
        knownActions.remove(action);
    }

    private Map<PermissionAction, PermissionScope> withNewDefaults(BuiltInRoles.Definition definition) {
        Map<PermissionAction, PermissionScope> result = grants();
        Set<PermissionAction> added = EnumSet.noneOf(PermissionAction.class);
        definition.grants().forEach((action, scope) -> {
            if (!knownActions.contains(action) && !result.containsKey(action)) {
                result.put(action, scope);
                added.add(action);
            }
        });
        // Drop new defaults whose requirements are missing; repeat, since dropping one may break another.
        boolean dropped = true;
        while (dropped) {
            dropped = false;
            for (PermissionAction action : List.copyOf(added)) {
                PermissionAction missing = missingRequirement(result, action);
                if (missing != null) {
                    result.remove(action);
                    added.remove(action);
                    dropped = true;
                    log.warn("Built-in role sync: company {} role {}: new default {} skipped, it requires {} "
                            + "which the company does not grant wide enough", organization.getId(), code, action, missing);
                }
            }
        }
        return result;
    }

    private static PermissionAction missingRequirement(Map<PermissionAction, PermissionScope> grants,
                                                       PermissionAction action) {
        for (PermissionAction.Requirement requirement : action.requires()) {
            PermissionScope held = grants.get(requirement.action());
            if (held == null || !PermissionRules.atLeast(held, requirement.minScope().floorFor(grants.get(action)))) {
                return requirement.action();
            }
        }
        return null;
    }

    /** Applies the set in place (scope changes update rows, so no action is deleted and re-added in one flush). */
    private boolean setGrants(Map<PermissionAction, PermissionScope> target) {
        boolean changed = false;
        Set<PermissionAction> kept = EnumSet.noneOf(PermissionAction.class);
        Iterator<Permission> iterator = permissions.iterator();
        while (iterator.hasNext()) {
            Permission permission = iterator.next();
            PermissionScope scope = target.get(permission.getAction());
            if (scope == null) {
                iterator.remove();
                changed = true;
            } else {
                if (scope != permission.getScope()) {
                    permission.changeScope(scope);
                    changed = true;
                }
                kept.add(permission.getAction());
            }
        }
        for (Map.Entry<PermissionAction, PermissionScope> grant : target.entrySet()) {
            if (!kept.contains(grant.getKey())) {
                permissions.add(new Permission(this, grant.getKey(), grant.getValue()));
                changed = true;
            }
        }
        return changed;
    }

    private void markEveryActionKnown() {
        for (PermissionAction action : PermissionAction.values()) {
            if (!knownActions.contains(action)) {
                knownActions.add(action);
            }
        }
    }

    private void requireNotOwner() {
        if (systemRole) {
            throw new RuleViolationException("ROLE_OWNER_LOCKED", "The Owner role cannot be changed");
        }
    }

    private void requireCustomText() {
        requireNotOwner();
        if (seeded) {
            throw new RuleViolationException("ROLE_NAME_FIXED",
                    "A Built-in role's name and description come from the definition file");
        }
    }

    private static Map<PermissionAction, PermissionScope> everyAction() {
        Map<PermissionAction, PermissionScope> grants = new EnumMap<>(PermissionAction.class);
        for (PermissionAction action : PermissionAction.values()) {
            grants.put(action, PermissionScope.ORGANIZATION);
        }
        return grants;
    }

    private static String validName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > MAX_NAME) {
            throw new RuleViolationException("ROLE_NAME_INVALID", "Role name must have 1 to " + MAX_NAME + " characters");
        }
        return name.strip();
    }

    private static String validDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        if (description.strip().length() > MAX_DESCRIPTION) {
            throw new RuleViolationException("ROLE_NAME_INVALID",
                    "Role description must have at most " + MAX_DESCRIPTION + " characters");
        }
        return description.strip();
    }

    private static String generateCode() {
        StringBuilder code = new StringBuilder(BuiltInRoles.CUSTOM_PREFIX);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }
}
