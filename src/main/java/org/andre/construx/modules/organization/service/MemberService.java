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
import org.andre.construx.shared.storage.ObjectStorage;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.util.function.Consumer;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Who belongs to an organization and what the caller may do there. */
@Service
public class MemberService {
    private static final Duration AVATAR_VIEW_TTL = Duration.ofHours(1);

    private final OrganizationMembershipRepository memberships;
    private final OrganizationRoleRepository roles;
    private final UserProfileRepository profiles;
    private final MembershipGuard guard;
    private final DomainAuthorization authorization;
    private final ObjectStorage storage;

    public MemberService(OrganizationMembershipRepository memberships, OrganizationRoleRepository roles,
                         UserProfileRepository profiles, MembershipGuard guard, DomainAuthorization authorization,
                         ObjectStorage storage) {
        this.memberships = memberships;
        this.roles = roles;
        this.profiles = profiles;
        this.guard = guard;
        this.authorization = authorization;
        this.storage = storage;
    }

    public record PermissionData(PermissionAction action, PermissionScope scope) {}

    /** Display data for gating the UI; the server still checks every request. */
    public record MeData(UUID membershipId, String roleCode, String roleName, List<PermissionData> permissions) {}

    public record RoleData(UUID id, String code, String name, boolean system) {}

    public record MemberData(UUID membershipId, UUID userId, String email, String fullName, String roleCode,
                             String roleName, MembershipStatus status, URI avatarUrl) {}

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
        Map<UUID, UserProfile> profileMap = profiles.findAllById(list.stream().map(m -> m.getUserAccount().getId()).toList())
                .stream().collect(Collectors.toMap(UserProfile::getId, p -> p));
        return list.stream().map(m -> {
            UserProfile p = profileMap.get(m.getUserAccount().getId());
            String name = p != null ? p.getFullName() : null;
            URI avatarUrl = (p != null && p.getAvatarKey() != null)
                    ? storage.presignDownload(p.getAvatarKey(), AVATAR_VIEW_TTL) : null;
            return new MemberData(m.getId(), m.getUserAccount().getId(), m.getUserAccount().getEmail(),
                    name, m.getRole().getCode(), m.getRole().getName(), m.getStatus(), avatarUrl);
        }).toList();
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
        return toData(member);
    }

    /** Suspend a member so they can no longer access the organization. */
    @Transactional
    public MemberData suspend(UUID userId, UUID organizationId, UUID membershipId) {
        return changeStatus(userId, organizationId, membershipId, OrganizationMembership::suspend);
    }

    /** Re-activate a previously suspended member. */
    @Transactional
    public MemberData reactivate(UUID userId, UUID organizationId, UUID membershipId) {
        return changeStatus(userId, organizationId, membershipId, OrganizationMembership::activate);
    }

    /** Permanently remove a member from the organization (sets their status to LEFT). */
    @Transactional
    public void removeMember(UUID userId, UUID organizationId, UUID membershipId) {
        OrganizationMembership caller = guard.requireActive(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_MEMBERS);
        OrganizationMembership member = memberships.findByIdAndOrganizationId(membershipId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
        if (member.getId().equals(caller.getId())) {
            throw new AccessDeniedException("You cannot remove yourself");
        }
        member.leave();
    }

    private MemberData changeStatus(UUID userId, UUID organizationId, UUID membershipId,
                                    Consumer<OrganizationMembership> change) {
        OrganizationMembership caller = guard.requireActive(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_MEMBERS);
        OrganizationMembership member = memberships.findByIdAndOrganizationId(membershipId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
        if (member.getId().equals(caller.getId())) {
            throw new AccessDeniedException("You cannot change your own membership status");
        }
        change.accept(member);
        return toData(member);
    }

    private MemberData toData(OrganizationMembership m) {
        UserProfile profile = profiles.findById(m.getUserAccount().getId()).orElse(null);
        String fullName = profile != null ? profile.getFullName() : null;
        URI avatarUrl = (profile != null && profile.getAvatarKey() != null)
                ? storage.presignDownload(profile.getAvatarKey(), AVATAR_VIEW_TTL) : null;
        return new MemberData(m.getId(), m.getUserAccount().getId(), m.getUserAccount().getEmail(),
                fullName, m.getRole().getCode(), m.getRole().getName(), m.getStatus(), avatarUrl);
    }
}
