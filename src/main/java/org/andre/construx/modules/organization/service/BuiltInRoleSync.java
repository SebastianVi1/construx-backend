package org.andre.construx.modules.organization.service;

import org.andre.construx.modules.organization.model.BuiltInRoles;
import org.andre.construx.modules.organization.model.Organization;
import org.andre.construx.modules.organization.model.OrganizationRole;
import org.andre.construx.modules.organization.model.RoleKind;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.modules.organization.repository.OrganizationRoleRepository;
import org.andre.construx.shared.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Applies {@code built-in-roles.yml} to every company on boot (contract roles §6.2, ADR 0010), in one idempotent
 * transaction. Only real changes are written and audited ({@code SYNC}, {@code ADOPT}, {@code CONVERT_TO_CUSTOM},
 * with a null actor), so a boot with no file change touches nothing. Each changed role's {@code version} moves by
 * one, so an editor holding the version from before the sync gets {@code ROLE_CHANGED}.
 */
@Service
public class BuiltInRoleSync {
    static final String ENTITY = "ORGANIZATION_ROLE";
    private static final Logger log = LoggerFactory.getLogger(BuiltInRoleSync.class);

    /** Roles created, synced (permissions or name changed, Owner included), adopted back and converted to custom. */
    public record SyncReport(int created, int synced, int adopted, int converted) {
        SyncReport plus(SyncReport other) {
            return new SyncReport(created + other.created, synced + other.synced, adopted + other.adopted,
                    converted + other.converted);
        }
    }

    private final OrganizationRepository organizations;
    private final OrganizationRoleRepository roles;
    private final AuditService audit;
    private final RoleVersions versions;

    public BuiltInRoleSync(OrganizationRepository organizations, OrganizationRoleRepository roles, AuditService audit,
                           RoleVersions versions) {
        this.organizations = organizations;
        this.roles = roles;
        this.audit = audit;
        this.versions = versions;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onReady() {
        SyncReport report = sync(BuiltInRoles.definitions());
        log.info("Built-in role sync: {}", report);
    }

    @Transactional
    public SyncReport sync(List<BuiltInRoles.Definition> definitions) {
        SyncReport total = new SyncReport(0, 0, 0, 0);
        for (Organization organization : organizations.findAll()) {
            total = total.plus(syncCompany(organization, definitions));
        }
        return total;
    }

    private SyncReport syncCompany(Organization organization, List<BuiltInRoles.Definition> definitions) {
        List<OrganizationRole> companyRoles = new ArrayList<>(roles.findByOrganizationId(organization.getId()));
        Map<String, OrganizationRole> byCode = companyRoles.stream()
                .collect(Collectors.toMap(OrganizationRole::getCode, r -> r));
        // Names as they stand in the database: a rename may not take a name another role still holds there, or the
        // flush could hit uk_role_org_name before the other role's rename.
        Map<OrganizationRole, String> storedNames = new HashMap<>();
        companyRoles.forEach(r -> storedNames.put(r, r.getName()));
        int created = 0;
        int synced = 0;
        int adopted = 0;
        int converted = 0;

        OrganizationRole owner = byCode.get(BuiltInRoles.OWNER_CODE);
        if (owner != null) {
            RoleAuditState before = RoleAuditState.of(owner);
            if (owner.holdEveryAction()) {
                versions.raise(owner);
                audit.record(ENTITY, owner.getId(), "SYNC", null, before, RoleAuditState.of(owner));
                synced++;
            }
        } else {
            log.warn("Built-in role sync: company {} has no Owner role", organization.getId());
        }

        for (BuiltInRoles.Definition definition : definitions) {
            OrganizationRole role = byCode.get(definition.code());
            if (role == null) {
                role = OrganizationRole.builtIn(organization, definition);
                if (!nameFree(companyRoles, storedNames, role, role.getName())) {
                    role.useStoredName(fallbackName(companyRoles, storedNames, role, definition));
                }
                role = roles.save(role);
                companyRoles.add(role);
                storedNames.put(role, role.getName());
                audit.record(ENTITY, role.getId(), "SYNC", null, null, RoleAuditState.of(role));
                created++;
                continue;
            }
            RoleAuditState before = RoleAuditState.of(role);
            boolean adopt = role.kind() == RoleKind.CUSTOM;
            if (adopt) {
                role.adoptAsBuiltIn();
            }
            boolean changed = role.syncFromFile(definition);
            changed |= followFileName(organization, companyRoles, storedNames, role, definition);
            if (adopt || changed) {
                versions.raise(role); // a client holding the version from before the sync must reload
            }
            if (adopt) {
                audit.record(ENTITY, role.getId(), "ADOPT", null, before, RoleAuditState.of(role));
                adopted++;
            } else if (changed) {
                audit.record(ENTITY, role.getId(), "SYNC", null, before, RoleAuditState.of(role));
                synced++;
            }
        }

        Set<String> fileCodes = definitions.stream().map(BuiltInRoles.Definition::code).collect(Collectors.toSet());
        for (OrganizationRole role : companyRoles) {
            if (role.kind() == RoleKind.BUILT_IN && !fileCodes.contains(role.getCode())) {
                RoleAuditState before = RoleAuditState.of(role);
                role.convertToCustom();
                versions.raise(role);
                audit.record(ENTITY, role.getId(), "CONVERT_TO_CUSTOM", null, before, RoleAuditState.of(role));
                converted++;
            }
        }
        return new SyncReport(created, synced, adopted, converted);
    }

    private boolean followFileName(Organization organization, List<OrganizationRole> companyRoles,
                                   Map<OrganizationRole, String> storedNames, OrganizationRole role,
                                   BuiltInRoles.Definition definition) {
        String fileName = definition.name().es();
        if (fileName.equals(role.getName())) {
            return false;
        }
        if (!nameFree(companyRoles, storedNames, role, fileName)) {
            log.warn("Built-in role sync: company {} role {} keeps its name `{}`; `{}` is taken by another role",
                    organization.getId(), role.getCode(), role.getName(), fileName);
            return false;
        }
        return role.followFileName(definition);
    }

    private String fallbackName(List<OrganizationRole> companyRoles, Map<OrganizationRole, String> storedNames,
                                OrganizationRole role, BuiltInRoles.Definition definition) {
        String withCode = definition.name().es() + " (" + definition.code() + ")";
        String name = withCode.length() <= OrganizationRole.MAX_NAME
                && nameFree(companyRoles, storedNames, role, withCode) ? withCode : definition.code();
        log.warn("Built-in role sync: company {} role {} stored as `{}`; `{}` is taken by another role",
                role.getOrganization().getId(), definition.code(), name, definition.name().es());
        return name;
    }

    /** Free when no other role holds it now or held it before this sync, ignoring case and outer spaces. */
    private static boolean nameFree(List<OrganizationRole> companyRoles, Map<OrganizationRole, String> storedNames,
                                    OrganizationRole role, String name) {
        String key = normalize(name);
        return companyRoles.stream().filter(other -> other != role).noneMatch(other ->
                normalize(other.getName()).equals(key)
                        || (storedNames.containsKey(other) && normalize(storedNames.get(other)).equals(key)));
    }

    private static String normalize(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }
}
