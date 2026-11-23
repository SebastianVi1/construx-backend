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
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** A company's Organization roles as the roles pages see them (contract roles §5.1, §5.2). */
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

    private final OrganizationRoleRepository roles;
    private final OrganizationMembershipRepository memberships;
    private final OrganizationInvitationRepository invitations;

    public RoleService(OrganizationRoleRepository roles, OrganizationMembershipRepository memberships,
                       OrganizationInvitationRepository invitations) {
        this.roles = roles;
        this.memberships = memberships;
        this.invitations = invitations;
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
        OrganizationRole role = roles.findByOrganizationIdAndCode(org.organizationId(), code)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found"));
        List<GrantData> defaults = role.kind() == RoleKind.BUILT_IN
                ? BuiltInRoles.find(role.getCode()).map(d -> grants(d.grants())).orElse(null)
                : null;
        return new RoleDetailData(toData(org, role, counts(org.organizationId())), grants(role.grants()), defaults);
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
