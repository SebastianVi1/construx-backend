package org.andre.construx.modules.organization.service;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.identity.service.PersonDirectory;
import org.andre.construx.modules.identity.service.PersonDirectory.Person;
import org.andre.construx.modules.organization.model.MembershipStatus;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.OrganizationRole;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.model.PermissionScope;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRoleRepository;
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

    public MemberService(OrganizationMembershipRepository memberships, OrganizationRoleRepository roles,
                         PersonDirectory directory) {
        this.memberships = memberships;
        this.roles = roles;
        this.directory = directory;
    }

    public record PermissionData(PermissionAction action, PermissionScope scope) {}

    /** Display data for gating the UI; the server still checks every request. */
    public record MeData(UUID membershipId, String roleCode, String roleName, List<PermissionData> permissions) {}

    public record RoleData(UUID id, String code, String name, boolean system) {}

    public record MemberData(UUID membershipId, UUID userId, String email, String fullName, String roleCode,
                             String roleName, MembershipStatus status, URI avatarUrl) {}

    @Transactional(readOnly = true)
    public MeData me(OrgScope org) {
        OrganizationMembership member = org.membership();
        List<PermissionData> permissions = member.getRole().getPermissions().stream()
                .map(p -> new PermissionData(p.getAction(), p.getScope()))
                .sorted(Comparator.comparing(PermissionData::action))
                .toList();
        return new MeData(member.getId(), member.getRole().getCode(), member.getRole().getName(), permissions);
    }

    @Transactional(readOnly = true)
    public List<RoleData> roles(OrgScope org) {
        org.require(PermissionAction.MANAGE_MEMBERS);
        return roles.findByOrganizationIdOrderByCode(org.organizationId()).stream()
                .map(r -> new RoleData(r.getId(), r.getCode(), r.getName(), r.isSystemRole() || r.isSeeded()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MemberData> members(OrgScope org, MembershipStatus status) {
        org.require(PermissionAction.MANAGE_MEMBERS);
        List<OrganizationMembership> list = memberships.findByOrganizationWithUserAndRole(org.organizationId(), status);
        Map<UUID, Person> people = directory.byUserId(list.stream().map(m -> m.getUserAccount().getId()).toList());
        return list.stream().map(m -> toData(m, PersonDirectory.of(people, m.getUserAccount().getId()))).toList();
    }

    /**
     * Give another member a different non-owner role. The owner role cannot be granted or
     * replaced here, and nobody changes their own role.
     */
    @Transactional
    public MemberData changeRole(OrgScope org, UUID membershipId, String roleCode) {
        OrganizationMembership caller = org.membership();
        org.require(PermissionAction.MANAGE_MEMBERS);
        OrganizationMembership member = memberships.findByIdAndOrganizationId(membershipId, org.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
        if (member.getId().equals(caller.getId())) {
            throw new AccessDeniedException("You cannot change your own role");
        }
        OrganizationRole role = roles.findByOrganizationIdAndCode(org.organizationId(), roleCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown role"));
        if (role.isSystemRole()) {
            throw new IllegalArgumentException("The owner role cannot be granted");
        }
        member.changeRole(role);
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
                person.fullName(), m.getRole().getCode(), m.getRole().getName(), m.getStatus(), person.avatarUrl());
    }
}
