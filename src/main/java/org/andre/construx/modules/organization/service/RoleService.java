package org.andre.construx.modules.organization.service;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.access.RoleAuthority;
import org.andre.construx.modules.organization.model.BuiltInRoles;
import org.andre.construx.modules.organization.model.InvitationStatus;
import org.andre.construx.modules.organization.model.LocalizedText;
import org.andre.construx.modules.organization.model.MembershipStatus;
import org.andre.construx.modules.organization.model.OrganizationRole;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.model.PermissionScope;
import org.andre.construx.modules.organization.model.RoleKind;
import org.andre.construx.modules.organization.repository.OrganizationInvitationRepository;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRoleRepository;
import org.andre.construx.modules.organization.repository.RoleCount;
import org.andre.construx.shared.audit.AuditService;
import org.andre.construx.shared.exception.ConflictException;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.exception.RuleViolationException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A company's Organization roles as the roles pages see them (contract roles §5.1, §5.2), and the writes that change
 * a role's permissions (§5.4, §5.5). Every write checks the caller's authority first ({@link RoleAuthority}), then the
 * client's {@code version}, raises the version even when only permission rows change, and is audited (§7).
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

    private final OrganizationRoleRepository roles;
    private final OrganizationMembershipRepository memberships;
    private final OrganizationInvitationRepository invitations;
    private final AuditService audit;
    private final EntityManager entityManager;

    public RoleService(OrganizationRoleRepository roles, OrganizationMembershipRepository memberships,
                       OrganizationInvitationRepository invitations, AuditService audit, EntityManager entityManager) {
        this.roles = roles;
        this.memberships = memberships;
        this.invitations = invitations;
        this.audit = audit;
        this.entityManager = entityManager;
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
     * Replaces a role's whole permission set (§5.4). A Built-in role takes no name or description
     * ({@code ROLE_NAME_FIXED}) and becomes customized, or uncustomized again when the set equals its defaults.
     */
    @Transactional
    public RoleDetailData update(OrgScope org, String code, RoleDraft draft, long version) {
        OrganizationRole role = find(org, code);
        RoleAuthority.checkEdit(org, role, draft.permissions());
        requireVersion(role, version);
        if (role.kind() == RoleKind.BUILT_IN && (draft.name() != null || draft.description() != null)) {
            throw new RuleViolationException("ROLE_NAME_FIXED",
                    "A Built-in role's name and description come from the definition file");
        }
        if (role.kind() == RoleKind.CUSTOM) {
            requireSameText(role, draft);
        }
        RoleAuditState before = RoleAuditState.of(role);
        raiseVersion(role);
        role.replacePermissions(draft.permissions());
        return written(org, role, "UPDATE", before);
    }

    /** Sets a Built-in role back to the definition file's permissions and clears {@code customized} (§5.5). */
    @Transactional
    public RoleDetailData restore(OrgScope org, String code, long version) {
        OrganizationRole role = find(org, code);
        Optional<BuiltInRoles.Definition> definition = role.kind() == RoleKind.BUILT_IN
                ? BuiltInRoles.find(role.getCode()) : Optional.empty();
        RoleAuthority.checkEdit(org, role, definition.map(BuiltInRoles.Definition::grants).orElse(Map.of()));
        if (definition.isEmpty()) {
            throw new RuleViolationException("ROLE_NOT_BUILT_IN", "Only a Built-in role has defaults to restore");
        }
        requireVersion(role, version);
        RoleAuditState before = RoleAuditState.of(role);
        raiseVersion(role);
        role.restoreDefaults(definition.get());
        return written(org, role, "RESTORE", before);
    }

    private OrganizationRole find(OrgScope org, String code) {
        return roles.findByOrganizationIdAndCode(org.organizationId(), code)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found"));
    }

    private RoleDetailData detail(OrgScope org, OrganizationRole role) {
        List<GrantData> defaults = role.kind() == RoleKind.BUILT_IN
                ? BuiltInRoles.find(role.getCode()).map(d -> grants(d.grants())).orElse(null)
                : null;
        return new RoleDetailData(toData(org, role, counts(org.organizationId())), grants(role.grants()), defaults);
    }

    /** The client edited the role as it was at {@code version}; anything else means someone saved in between. */
    private static void requireVersion(OrganizationRole role, long version) {
        if (role.getVersion() != version) {
            throw roleChanged();
        }
    }

    /**
     * Raises the role's version now, so every write moves it even when only permission rows change (they are not
     * part of the role's row). The guarded update also catches a write that committed after this one read the role.
     */
    private void raiseVersion(OrganizationRole role) {
        try {
            entityManager.lock(role, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        } catch (OptimisticLockException e) {
            throw roleChanged();
        }
    }

    /** Flushes so the returned version is the stored one, then audits {@code before} and the result. */
    private RoleDetailData written(OrgScope org, OrganizationRole role, String action, RoleAuditState before) {
        roles.flush();
        audit.record(AUDIT_ENTITY, role.getId(), action, org.membershipId(), before, RoleAuditState.of(role));
        return detail(org, role);
    }

    /** Renaming a Custom role is not part of this endpoint yet: its name and description may only be resent as is. */
    private static void requireSameText(OrganizationRole role, RoleDraft draft) {
        boolean sameName = draft.name() == null || draft.name().strip().equals(role.getName());
        boolean sameDescription = draft.description() == null
                || Objects.equals(blankToNull(draft.description()), role.getDescription());
        if (!sameName || !sameDescription) {
            throw new IllegalArgumentException("A Custom role's name and description cannot be changed here yet");
        }
    }

    private static String blankToNull(String text) {
        return text.isBlank() ? null : text.strip();
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
