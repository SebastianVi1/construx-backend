package org.andre.construx.modules.organization.service;

import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.organization.model.MembershipStatus;
import org.andre.construx.modules.organization.model.Organization;
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

    public record OrganizationData(UUID id, String name, OrganizationStatus status) {
        static OrganizationData from(Organization organization) {
            return new OrganizationData(organization.getId(), organization.getName(), organization.getStatus());
        }
    }

    @Transactional
    public OrganizationData create(UUID userId, String name) {
        UserAccount creator = users.findById(userId)
                .orElseThrow(() -> new BadCredentialsException("Account unavailable"));
        return OrganizationData.from(organizations.save(new Organization(name, creator)));
    }

    @Transactional(readOnly = true)
    public List<OrganizationData> listMine(UUID userId) {
        return memberships.findByUserWithOrganization(userId, MembershipStatus.ACTIVE).stream()
                .map(membership -> OrganizationData.from(membership.getOrganization()))
                .toList();
    }

    @Transactional(readOnly = true)
    public OrganizationData get(UUID userId, UUID organizationId) {
        guard.requireActive(userId, organizationId);
        return OrganizationData.from(load(organizationId));
    }

    @Transactional
    public OrganizationData rename(UUID userId, UUID organizationId, String name) {
        guard.requireActive(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_ORGANIZATION);
        Organization organization = load(organizationId);
        organization.rename(name);
        return OrganizationData.from(organization);
    }

    private Organization load(UUID organizationId) {
        return organizations.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
    }
}
