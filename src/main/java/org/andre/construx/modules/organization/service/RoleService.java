package org.andre.construx.modules.organization.service;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.access.RoleAuthority;
import org.andre.construx.modules.organization.model.BuiltInRoles;
import org.andre.construx.modules.organization.model.InvitationStatus;
import org.andre.construx.modules.organization.model.LocalizedText;
import org.andre.construx.modules.organization.model.MembershipStatus;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.OrganizationRole;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.model.PermissionScope;
import org.andre.construx.modules.organization.model.RoleKind;
import org.andre.construx.modules.organization.repository.OrganizationInvitationRepository;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.modules.organization.repository.OrganizationRoleRepository;
import org.andre.construx.modules.organization.repository.RoleCount;
import org.andre.construx.shared.audit.AuditService;
import org.andre.construx.shared.exception.ConflictException;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.exception.RuleViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A company's Organization roles as the roles pages see them (contract roles §5.1, §5.2), and the writes on them:
 * create a Custom role (§5.3), edit a role (§5.4), restore a Built-in role (§5.5) and delete a Custom role (§5.6).
 * Every write locks the role's row and checks the caller's authority first ({@link RoleAuthority}); edits then check
 * the client's {@code version} and raise it by one ({@link RoleVersions}). Every write is audited (§7).
 */
@Service
public class RoleService {
    private static final List<String> FILE_ORDER =
            BuiltInRoles.definitions().stream().map(BuiltInRoles.Definition::code).toList();
    /** Owner, then Built-in roles in file order, then custom roles by name ({@link RoleKind} declares that order). */
    private static final Comparator<OrganizationRole> LIST_ORDER = Comparator
            .comparingInt((OrganizationRole role) -> role.kind().ordinal())
            .thenComparingInt(role -> role.kind() == RoleKind.BUILT_IN ? fileIndex(role.getCode()) : 0)
            .thenComparing(OrganizationRole::getName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(OrganizationRole::getCode);

    private static final String AUDIT_ENTITY = "ORGANIZATION_ROLE";
    /** At most this many Custom roles per company (§5.3). */
    public static final int CUSTOM_ROLE_LIMIT = 30;

    private final OrganizationRepository organizations;
    private final OrganizationRoleRepository roles;
    private final OrganizationMembershipRepository memberships;
    private final OrganizationInvitationRepository invitations;
    private final AuditService audit;
    private final RoleVersions versions;

    public RoleService(OrganizationRepository organizations, OrganizationRoleRepository roles,
                       OrganizationMembershipRepository memberships, OrganizationInvitationRepository invitations,
                       AuditService audit, RoleVersions versions) {
        this.organizations = organizations;
        this.roles = roles;
        this.memberships = memberships;
        this.invitations = invitations;
        this.audit = audit;
        this.versions = versions;
    }

    public record GrantData(PermissionAction action, PermissionScope scope) {}

    /**
     * One role for the caller. {@code memberCount} counts active and suspended members; {@code editable} and
     * {@code assignable} are {@link RoleAuthority}'s answers for the caller.
     */
    public record RoleData(UUID id, String code, RoleKind kind, String name, LocalizedText names,
                           LocalizedText description, boolean system, boolean customized, long memberCount,
                           long pendingInvitationCount, boolean editable, boolean assignable, long version) {}

    /** A role with its permissions in catalog order and, for a Built-in role, the file's defaults. */
    public record RoleDetailData(RoleData role, List<GrantData> permissions, List<GrantData> defaults) {}

    /** Owner, then Built-in roles in file order, then custom roles by name. */
    @Transactional(readOnly = true)
    public List<RoleData> list(OrgScope org) {
        requireRead(org);
        Counts counts = counts(org.organizationId());
        return roles.findWithPermissions(org.organizationId()).stream()
                .sorted(LIST_ORDER)
                .map(role -> toData(org, role, counts))
                .toList();
    }

    /** One role by code; 404 when the company has none with that code. */
    @Transactional(readOnly = true)
    public RoleDetailData get(OrgScope org, String code) {
        requireRead(org);
        return detail(org, find(org, code));
    }

    /**
     * Creates a Custom role with a server-generated code (§5.3). The set must fit within the caller; the name must
     * be valid and free in the company, and the company may hold at most {@link #CUSTOM_ROLE_LIMIT} Custom roles.
     */
    @Transactional
    public RoleDetailData create(OrgScope org, RoleDraft draft) {
        RoleAuthority.checkCreate(org, draft.permissions());
        List<OrganizationRole> existing = lockCompanyRoles(org);
        OrganizationRole role = OrganizationRole.custom(org.membership().getOrganization(), draft.name(),
                draft.description(), draft.permissions());
        if (existing.stream().filter(r -> r.kind() == RoleKind.CUSTOM).count() >= CUSTOM_ROLE_LIMIT) {
            throw new ConflictException("ROLE_LIMIT_REACHED",
                    "A company can have at most " + CUSTOM_ROLE_LIMIT + " custom roles");
        }
        requireFreeName(existing, role);
        roles.save(role);
        roles.flush();
        audit.record(AUDIT_ENTITY, role.getId(), "CREATE", org.membershipId(), null, RoleAuditState.of(role));
        return detail(org, role);
    }

    /**
     * Replaces a role's whole permission set (§5.4). A Built-in role takes no name or description
     * ({@code ROLE_NAME_FIXED}) and becomes customized, or uncustomized again when the set equals its defaults. A
     * Custom role is also renamed and described when the request carries them (see {@link #applyText}).
     */
    @Transactional
    public RoleDetailData update(OrgScope org, String code, RoleDraft draft, long version) {
        OrganizationRole role = findForUpdate(org, code);
        RoleAuthority.checkEdit(org, role, draft.permissions());
        requireVersion(role, version);
        if (role.kind() == RoleKind.BUILT_IN && (draft.name() != null || draft.description() != null)) {
            throw new RuleViolationException("ROLE_NAME_FIXED",
                    "A Built-in role's name and description come from the definition file");
        }
        RoleAuditState before = RoleAuditState.of(role);
        if (role.kind() == RoleKind.CUSTOM) {
            applyText(org, role, draft);
        }
        role.replacePermissions(draft.permissions());
        return written(org, role, "UPDATE", before);
    }

    /** Sets a Built-in role back to the definition file's permissions and clears {@code customized} (§5.5). */
    @Transactional
    public RoleDetailData restore(OrgScope org, String code, long version) {
        OrganizationRole role = findForUpdate(org, code);
        Optional<BuiltInRoles.Definition> definition = role.kind() == RoleKind.BUILT_IN
                ? BuiltInRoles.find(role.getCode()) : Optional.empty();
        RoleAuthority.checkEdit(org, role, definition.map(BuiltInRoles.Definition::grants).orElse(Map.of()));
        if (definition.isEmpty()) {
            throw new RuleViolationException("ROLE_NOT_BUILT_IN", "Only a Built-in role has defaults to restore");
        }
        requireVersion(role, version);
        RoleAuditState before = RoleAuditState.of(role);
        role.restoreDefaults(definition.get());
        return written(org, role, "RESTORE", before);
    }

    /**
     * Deletes a Custom role (§5.6). While any membership (any status) or invitation (any status) references it, a
     * {@code replacementCode} is required ({@code ROLE_REPLACEMENT_REQUIRED} with the counts); they all move to the
     * replacement, each moved membership audited as a role change, then the role is deleted, in one transaction.
     * The role and the moved memberships are locked, so a parallel assignment into or out of the role waits.
     */
    @Transactional
    public void delete(OrgScope org, String code, String replacementCode) {
        OrganizationRole role = findForUpdate(org, code);
        RoleAuthority.checkEdit(org, role, Map.of());
        if (role.kind() != RoleKind.CUSTOM) {
            throw new RuleViolationException("ROLE_BUILT_IN_UNDELETABLE", "A Built-in role cannot be deleted");
        }
        OrganizationRole replacement = replacementCode == null || replacementCode.isBlank()
                ? null : replacement(org, role, replacementCode.strip());
        List<OrganizationMembership> held = memberships.findForUpdateByRoleId(role.getId());
        long invitationCount = invitations.countByRoleId(role.getId());
        if (replacement == null && (!held.isEmpty() || invitationCount > 0)) {
            throw replacementRequired(role, invitationCount);
        }
        for (OrganizationMembership membership : held) {
            membership.changeRole(replacement);
            MembershipRoleAudit.record(audit, membership.getId(), role.getCode(), replacement.getCode(),
                    org.membershipId());
        }
        if (invitationCount > 0) {
            invitations.reassignRole(role, replacement);
        }
        audit.record(AUDIT_ENTITY, role.getId(), "DELETE", org.membershipId(), RoleAuditState.of(role), null);
        roles.delete(role);
        roles.flush();
    }

    /** Another role of the company, not the Owner role, that the caller may assign ({@code ROLE_ABOVE_CALLER}). */
    private OrganizationRole replacement(OrgScope org, OrganizationRole deleted, String code) {
        OrganizationRole replacement = roles.findByOrganizationIdAndCode(org.organizationId(), code)
                .filter(r -> !r.getId().equals(deleted.getId()) && r.kind() != RoleKind.OWNER)
                .orElseThrow(() -> new RuleViolationException("ROLE_REPLACEMENT_INVALID",
                        "The replacement must be another role of the company, not the Owner role"));
        RoleAuthority.checkInvite(org, replacement);
        return replacement;
    }

    private ConflictException replacementRequired(OrganizationRole role, long invitationCount) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("memberCount", memberships.countByRoleIdAndStatusIn(role.getId(),
                EnumSet.of(MembershipStatus.ACTIVE, MembershipStatus.SUSPENDED)));
        counts.put("formerMemberCount", memberships.countByRoleIdAndStatusIn(role.getId(),
                EnumSet.of(MembershipStatus.LEFT)));
        counts.put("pendingInvitationCount", invitations.countByRoleIdAndStatus(role.getId(),
                InvitationStatus.PENDING));
        counts.put("invitationCount", invitationCount);
        return new ConflictException("ROLE_REPLACEMENT_REQUIRED",
                "Members, former members or invitations still use this role; choose a replacement", counts);
    }

    private OrganizationRole find(OrgScope org, String code) {
        return roles.findByOrganizationIdAndCode(org.organizationId(), code)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found"));
    }

    /**
     * Locks the company, then reads its roles: parallel creates and renames run one after the other, so none can
     * pass the Custom role limit or take a name another one is taking.
     */
    private List<OrganizationRole> lockCompanyRoles(OrgScope org) {
        organizations.findForUpdate(org.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        return roles.findByOrganizationId(org.organizationId());
    }

    private OrganizationRole findForUpdate(OrgScope org, String code) {
        return roles.findForUpdate(org.organizationId(), code)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found"));
    }

    private RoleDetailData detail(OrgScope org, OrganizationRole role) {
        List<GrantData> defaults = role.kind() == RoleKind.BUILT_IN
                ? BuiltInRoles.find(role.getCode()).map(d -> grants(d.grants())).orElse(null)
                : null;
        return new RoleDetailData(toData(org, role, counts(org.organizationId())), grants(role.grants()), defaults);
    }

    /**
     * The client edited the role as it was at {@code version}; anything else means someone saved in between. The
     * role's row is locked, so no other write can commit between this check and this one's.
     */
    private static void requireVersion(OrganizationRole role, long version) {
        if (role.getVersion() != version) {
            throw roleChanged();
        }
    }

    /** Writes the change and raises the version by one, then audits {@code before} and the result. */
    private RoleDetailData written(OrgScope org, OrganizationRole role, String action, RoleAuditState before) {
        versions.raise(role);
        audit.record(AUDIT_ENTITY, role.getId(), action, org.membershipId(), before, RoleAuditState.of(role));
        return detail(org, role);
    }

    /**
     * A Custom role's name and description travel together: a request with a {@code name} renames the role and sets
     * the description to what it carries (null or blank clears it); a request with only a {@code description} sets
     * that; a request with neither leaves both as they are.
     */
    private void applyText(OrgScope org, OrganizationRole role, RoleDraft draft) {
        if (draft.name() == null && draft.description() == null) {
            return;
        }
        // Read the other roles before renaming, so the query's flush never writes a clashing name.
        List<OrganizationRole> existing = lockCompanyRoles(org);
        if (draft.name() != null) {
            role.renameCustom(draft.name());
            requireFreeName(existing, role);
        }
        role.describe(draft.description());
    }

    /**
     * A Custom role's name is unique in the company and is no Owner or Built-in name in EN or ES, ignoring case
     * and surrounding spaces ({@code ROLE_NAME_TAKEN}).
     */
    private static void requireFreeName(List<OrganizationRole> existing, OrganizationRole role) {
        String name = role.getName().strip();
        boolean taken = BuiltInRoles.isBuiltInName(name) || existing.stream()
                .filter(other -> other != role && !other.getId().equals(role.getId()))
                .anyMatch(other -> other.getName().strip().equalsIgnoreCase(name));
        if (taken) {
            throw new ConflictException("ROLE_NAME_TAKEN", "Another role already uses this name");
        }
    }

    private static ConflictException roleChanged() {
        return new ConflictException("ROLE_CHANGED", "Someone else saved this role; reload it and try again");
    }

    private static void requireRead(OrgScope org) {
        if (!org.can(PermissionAction.MANAGE_ROLES, () -> false, null)
                && !org.can(PermissionAction.MANAGE_MEMBERS, () -> false, null)) {
            throw new AccessDeniedException("Permission required");
        }
    }

    /** A Built-in role whose code is no longer in the file sorts after the others (the sync converts it). */
    private static int fileIndex(String code) {
        int index = FILE_ORDER.indexOf(code);
        return index < 0 ? FILE_ORDER.size() : index;
    }

    private record Counts(Map<UUID, Long> members, Map<UUID, Long> pendingInvitations) {
        long membersOf(UUID roleId) {
            return members.getOrDefault(roleId, 0L);
        }

        long pendingInvitationsOf(UUID roleId) {
            return pendingInvitations.getOrDefault(roleId, 0L);
        }
    }

    private Counts counts(UUID organizationId) {
        return new Counts(
                byRole(memberships.countByRole(organizationId,
                        EnumSet.of(MembershipStatus.ACTIVE, MembershipStatus.SUSPENDED))),
                byRole(invitations.countByRole(organizationId, InvitationStatus.PENDING)));
    }

    private static Map<UUID, Long> byRole(List<RoleCount> counts) {
        return counts.stream().collect(Collectors.toMap(RoleCount::getRoleId, RoleCount::getCount));
    }

    private static RoleData toData(OrgScope org, OrganizationRole role, Counts counts) {
        RoleKind kind = role.kind();
        return new RoleData(role.getId(), role.getCode(), kind, role.getName(), RoleNames.of(role),
                RoleNames.descriptionOf(role), kind != RoleKind.CUSTOM, role.isCustomized(),
                counts.membersOf(role.getId()), counts.pendingInvitationsOf(role.getId()),
                RoleAuthority.editable(org, role), RoleAuthority.assignable(org, role), role.getVersion());
    }

    /** Catalog order is the enum's declaration order. */
    private static List<GrantData> grants(Map<PermissionAction, PermissionScope> grants) {
        return grants.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(grant -> new GrantData(grant.getKey(), grant.getValue()))
                .toList();
    }
}
