package org.andre.construx.modules.organization.access;

import org.andre.construx.modules.organization.model.MembershipStatus;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Builds the {@link OrgScope} of a request: the caller's active membership in the organization. A missing or
 * inactive membership is a 404, the same as a missing organization, so identifiers cannot be enumerated.
 */
@Component
public class OrgScopes {
    private final OrganizationMembershipRepository memberships;

    public OrgScopes(OrganizationMembershipRepository memberships) {
        this.memberships = memberships;
    }

    /** The caller's active membership in the organization; 404 when there is none. */
    @Transactional(readOnly = true)
    public OrgScope org(UUID userId, UUID organizationId) {
        OrganizationMembership membership = memberships.findByOrganizationIdAndUserAccountId(organizationId, userId)
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        return new OrgScope(userId, organizationId, membership);
    }
}
