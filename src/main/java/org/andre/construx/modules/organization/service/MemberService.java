package org.andre.construx.modules.organization.service;

import org.andre.construx.modules.identity.model.UserProfile;
import org.andre.construx.modules.identity.repository.UserProfileRepository;
import org.andre.construx.modules.organization.model.MembershipStatus;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.OrganizationRole;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.model.PermissionScope;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRoleRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.security.DomainAuthorization;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Who belongs to an organization and what the caller may do there. */
@Service
public class MemberService {
    private final OrganizationMembershipRepository memberships;
    private final OrganizationRoleRepository roles;
    private final UserProfileRepository profiles;
    private final MembershipGuard guard;
    private final DomainAuthorization authorization;

    public MemberService(OrganizationMembershipRepository memberships, OrganizationRoleRepository roles,
                         UserProfileRepository profiles, MembershipGuard guard, DomainAuthorization authorization) {
        this.memberships = memberships;
        this.roles = roles;
        this.profiles = profiles;
        this.guard = guard;
        this.authorization = authorization;
    }

    public record PermissionData(PermissionAction action, PermissionScope scope) {}

    /** Display data for gating the UI; the server still checks every request. */
    public record MeData(UUID membershipId, String roleCode, String roleName, List<PermissionData> permissions) {}

    public record RoleData(UUID id, String code, String name, boolean system) {}

    public record MemberData(UUID membershipId, UUID userId, String email, String fullName, String roleCode,
                             String roleName, MembershipStatus status) {}

    @Transactional(readOnly = true)
    public MeData me(UUID userId, UUID organizationId) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        List<PermissionData> permissions = member.getRole().getPermissions().stream()
                .map(p -> new PermissionData(p.getAction(), p.getScope()))
                .sorted(Comparator.comparing(PermissionData::action))
                .toList();
        return new MeData(member.getId(), member.getRole().getCode(), member.getRole().getName(), permissions);
    }

    @Transactional(readOnly = true)
    public List<RoleData> roles(UUID userId, UUID organizationId) {
        guard.requireActive(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_MEMBERS);
        return roles.findByOrganizationIdOrderByCode(organizationId).stream()
                .map(r -> new RoleData(r.getId(), r.getCode(), r.getName(), r.isSystemRole() || r.isSeeded()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MemberData> members(UUID userId, UUID organizationId, MembershipStatus status) {
        guard.requireActive(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_MEMBERS);
        List<OrganizationMembership> list = memberships.findByOrganizationWithUserAndRole(organizationId, status);
        Map<UUID, String> names = profiles.findAllById(list.stream().map(m -> m.getUserAccount().getId()).toList())
                .stream().collect(Collectors.toMap(UserProfile::getId, UserProfile::getFullName));
        return list.stream().map(m -> new MemberData(m.getId(), m.getUserAccount().getId(),
                m.getUserAccount().getEmail(), names.get(m.getUserAccount().getId()), m.getRole().getCode(),
                m.getRole().getName(), m.getStatus())).toList();
    }

    /**
     * Give another member a different non-owner role. The owner role cannot be granted or
     * replaced here, and nobody changes their own role.
     */
    @Transactional
    public MemberData changeRole(UUID userId, UUID organizationId, UUID membershipId, String roleCode) {
        OrganizationMembership caller = guard.requireActive(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_MEMBERS);
        OrganizationMembership member = memberships.findByIdAndOrganizationId(membershipId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
        if (member.getId().equals(caller.getId())) {
            throw new AccessDeniedException("You cannot change your own role");
        }
        OrganizationRole role = roles.findByOrganizationIdAndCode(organizationId, roleCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown role"));
        if (role.isSystemRole()) {
            throw new IllegalArgumentException("The owner role cannot be granted");
        }
        member.changeRole(role);
        String fullName = profiles.findById(member.getUserAccount().getId()).map(UserProfile::getFullName).orElse(null);
        return new MemberData(member.getId(), member.getUserAccount().getId(), member.getUserAccount().getEmail(),
                fullName, role.getCode(), role.getName(), member.getStatus());
    }
}
