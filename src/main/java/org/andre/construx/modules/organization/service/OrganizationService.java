package org.andre.construx.modules.organization.service;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.service.AccountService;
import org.andre.construx.modules.organization.model.MembershipStatus;
import org.andre.construx.modules.organization.model.Organization;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.OrganizationStatus;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class OrganizationService {
    private final AccountService accounts;
    private final OrganizationRepository organizations;
    private final OrganizationMembershipRepository memberships;

    public OrganizationService(AccountService accounts, OrganizationRepository organizations,
                               OrganizationMembershipRepository memberships) {
        this.accounts = accounts;
        this.organizations = organizations;
        this.memberships = memberships;
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
        UserAccount creator = accounts.account(userId);
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
    public OrganizationData get(OrgScope org) {
        return OrganizationData.from(org.membership());
    }

    @Transactional
    public OrganizationData rename(OrgScope org, String name) {
        OrganizationMembership member = org.membership();
        org.require(PermissionAction.MANAGE_ORGANIZATION);
        load(org.organizationId()).rename(name);
        return OrganizationData.from(member);
    }

    @Transactional
    public void delete(OrgScope org) {
        org.require(PermissionAction.MANAGE_ORGANIZATION);
        load(org.organizationId()).deactivate();
    }

    private Organization load(UUID organizationId) {
        return organizations.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
    }
}
