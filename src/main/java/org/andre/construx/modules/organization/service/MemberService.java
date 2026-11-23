package org.andre.construx.modules.organization.service;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.access.RoleAuthority;
import org.andre.construx.modules.identity.service.PersonDirectory;
import org.andre.construx.modules.identity.service.PersonDirectory.Person;
import org.andre.construx.modules.organization.model.LocalizedText;
import org.andre.construx.modules.organization.model.MembershipStatus;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.OrganizationRole;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.model.PermissionScope;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRoleRepository;
import org.andre.construx.shared.audit.AuditService;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.function.Consumer;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Who belongs to an organization and what the caller may do there. */
@Service
public class MemberService {
    private final OrganizationMembershipRepository memberships;
    private final OrganizationRoleRepository roles;
    private final PersonDirectory directory;
    private final AuditService audit;

    public MemberService(OrganizationMembershipRepository memberships, OrganizationRoleRepository roles,
                         PersonDirectory directory, AuditService audit) {
        this.memberships = memberships;
        this.roles = roles;
        this.directory = directory;
        this.audit = audit;
    }

    public record PermissionData(PermissionAction action, PermissionScope scope) {}

    /** Display data for gating the UI; the server still checks every request. */
    public record MeData(UUID membershipId, String roleCode, String roleName, LocalizedText roleNames,
                         List<PermissionData> permissions) {}

    public record MemberData(UUID membershipId, UUID userId, String email, String fullName, String roleCode,
                             String roleName, LocalizedText roleNames, MembershipStatus status, URI avatarUrl) {}

    @Transactional(readOnly = true)
    public MeData me(OrgScope org) {
        OrganizationMembership member = org.membership();
        List<PermissionData> permissions = member.getRole().getPermissions().stream()
                .map(p -> new PermissionData(p.getAction(), p.getScope()))
                .sorted(Comparator.comparing(PermissionData::action))
                .toList();
        return new MeData(member.getId(), member.getRole().getCode(), member.getRole().getName(),
                RoleNames.of(member.getRole()), permissions);
    }

    /** Members with {@code status}; with a {@code roleCode}, only those holding that role (none for an unknown code). */
    @Transactional(readOnly = true)
    public List<MemberData> members(OrgScope org, MembershipStatus status, String roleCode) {
        org.require(PermissionAction.MANAGE_MEMBERS);
        List<OrganizationMembership> list = roleCode == null
                ? memberships.findByOrganizationWithUserAndRole(org.organizationId(), status)
                : memberships.findByOrganizationWithUserAndRole(org.organizationId(), status, roleCode);
        Map<UUID, Person> people = directory.byUserId(list.stream().map(m -> m.getUserAccount().getId()).toList());
        return list.stream().map(m -> toData(m, PersonDirectory.of(people, m.getUserAccount().getId()))).toList();
    }

    /**
     * Move another member to a different role, under the "never above your own role" rules (contract roles §3): not
     * yourself, never into or out of the Owner role, and both the member's current role (whatever their status) and
     * the new one must fit within the caller. The new role, then the membership, are locked before the check (in
     * the order a role delete takes them), so a parallel change or delete cannot leave the check acting on a stale
     * role.
     */
    @Transactional
    public MemberData changeRole(OrgScope org, UUID membershipId, String roleCode) {
        org.require(PermissionAction.MANAGE_MEMBERS);
        OrganizationRole role = roles.findForShare(org.organizationId(), roleCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown role"));
        OrganizationMembership member = memberships.findForUpdate(membershipId, org.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
        RoleAuthority.checkAssign(org, member, role);
        String before = member.getRole().getCode();
        member.changeRole(role);
        if (!before.equals(role.getCode())) {
            MembershipRoleAudit.record(audit, member.getId(), before, role.getCode(), org.membershipId());
        }
        return toData(member);
    }

    /** Suspend a member so they can no longer access the organization. */
    @Transactional
    public MemberData suspend(OrgScope org, UUID membershipId) {
        return changeStatus(org, membershipId, OrganizationMembership::suspend);
    }

    /** Re-activate a previously suspended member. */
    @Transactional
    public MemberData reactivate(OrgScope org, UUID membershipId) {
        return changeStatus(org, membershipId, OrganizationMembership::activate);
    }

    /** Permanently remove a member from the organization (sets their status to LEFT). */
    @Transactional
    public void removeMember(OrgScope org, UUID membershipId) {
        OrganizationMembership caller = org.membership();
        org.require(PermissionAction.MANAGE_MEMBERS);
        OrganizationMembership member = memberships.findByIdAndOrganizationId(membershipId, org.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
        if (member.getId().equals(caller.getId())) {
            throw new AccessDeniedException("You cannot remove yourself");
        }
        member.leave();
    }

    private MemberData changeStatus(OrgScope org, UUID membershipId,
                                    Consumer<OrganizationMembership> change) {
        OrganizationMembership caller = org.membership();
        org.require(PermissionAction.MANAGE_MEMBERS);
        OrganizationMembership member = memberships.findByIdAndOrganizationId(membershipId, org.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
        if (member.getId().equals(caller.getId())) {
            throw new AccessDeniedException("You cannot change your own membership status");
        }
        change.accept(member);
        return toData(member);
    }

    private MemberData toData(OrganizationMembership m) {
        return toData(m, directory.of(m.getUserAccount().getId()));
    }

    private static MemberData toData(OrganizationMembership m, Person person) {
        return new MemberData(m.getId(), m.getUserAccount().getId(), m.getUserAccount().getEmail(),
                person.fullName(), m.getRole().getCode(), m.getRole().getName(), RoleNames.of(m.getRole()),
                m.getStatus(), person.avatarUrl());
    }
}
