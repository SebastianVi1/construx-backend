package org.andre.construx.support;

import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.organization.model.Organization;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.OrganizationRole;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.modules.project.model.ProjectAccess;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.repository.ProjectRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** Direct fixture setup for states the API under test does not create itself. */
public final class Members {
    private final TransactionTemplate tx;
    private final OrganizationRepository organizations;
    private final OrganizationMembershipRepository memberships;
    private final ProjectRepository projects;
    private final ProjectAccessRepository accesses;

    public Members(PlatformTransactionManager txm, OrganizationRepository organizations,
                   OrganizationMembershipRepository memberships, ProjectRepository projects,
                   ProjectAccessRepository accesses) {
        this.tx = new TransactionTemplate(txm);
        this.organizations = organizations;
        this.memberships = memberships;
        this.projects = projects;
        this.accesses = accesses;
    }

    /** Adds the user to the organization with a seeded role code; returns the membership id. */
    public UUID add(UUID organizationId, UserAccount user, String roleCode) {
        return tx.execute(s -> {
            Organization org = organizations.findById(organizationId).orElseThrow();
            OrganizationRole role = org.getRoles().stream()
                    .filter(r -> r.getCode().equals(roleCode)).findFirst().orElseThrow();
            return memberships.save(new OrganizationMembership(org, user, role)).getId();
        });
    }

    /** Gives the membership access to the site; returns the access id. */
    public UUID grantSite(UUID projectId, UUID membershipId) {
        return tx.execute(s -> accesses.save(new ProjectAccess(projects.findById(projectId).orElseThrow(),
                memberships.findById(membershipId).orElseThrow())).getId());
    }

    public UUID ownerMembership(UUID organizationId, UserAccount owner) {
        return memberships.findByOrganizationIdAndUserAccountId(organizationId, owner.getId()).orElseThrow().getId();
    }
}
