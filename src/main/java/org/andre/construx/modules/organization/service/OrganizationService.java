package org.andre.construx.modules.organization.service;

import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.organization.model.MembershipStatus;
import org.andre.construx.modules.organization.model.Organization;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.OrganizationStatus;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.security.DomainAuthorization;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class OrganizationService {
    private final UserAccountRepository users;
    private final OrganizationRepository organizations;
    private final OrganizationMembershipRepository memberships;
    private final DomainAuthorization authorization;
    private final MembershipGuard guard;

    public OrganizationService(UserAccountRepository users, OrganizationRepository organizations,
                               OrganizationMembershipRepository memberships, DomainAuthorization authorization,
                               MembershipGuard guard) {
        this.users = users;
        this.organizations = organizations;
        this.memberships = memberships;
        this.authorization = authorization;
        this.guard = guard;
    }

    /** {@code myRole*} is the caller's role in this organization. */
    public record OrganizationData(UUID id, String name, OrganizationStatus status, String myRoleCode,
                                   String myRoleName) {
        static OrganizationData from(OrganizationMembership membership) {
            Organization organization = membership.getOrganization();
            return new OrganizationData(organization.getId(), organization.getName(), organization.getStatus(),
                    membership.getRole().getCode(), membership.getRole().getName());
        }
    }

    @Transactional
    public OrganizationData create(UUID userId, String name) {
        UserAccount creator = users.findById(userId)
                .orElseThrow(() -> new BadCredentialsException("Account unavailable"));
        Organization organization = organizations.save(new Organization(name, creator));
        return OrganizationData.from(organization.getMemberships().iterator().next());
    }

    @Transactional(readOnly = true)
    public List<OrganizationData> listMine(UUID userId) {
        return memberships.findByUserWithOrganization(userId, MembershipStatus.ACTIVE).stream()
                .map(OrganizationData::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrganizationData get(UUID userId, UUID organizationId) {
        return OrganizationData.from(guard.requireActive(userId, organizationId));
    }

    @Transactional
    public OrganizationData rename(UUID userId, UUID organizationId, String name) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_ORGANIZATION);
        load(organizationId).rename(name);
        return OrganizationData.from(member);
    }

    private Organization load(UUID organizationId) {
        return organizations.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
    }
}
