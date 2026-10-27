package org.andre.construx.modules.organization.service;

import org.andre.construx.modules.organization.model.MembershipStatus;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * First step of every organization-scoped request. Non-members get the same 404 as a missing
 * organization, so identifiers cannot be enumerated.
 */
@Component
public class MembershipGuard {
    private final OrganizationMembershipRepository memberships;

    public MembershipGuard(OrganizationMembershipRepository memberships) {
        this.memberships = memberships;
    }

    public OrganizationMembership requireActive(UUID userId, UUID organizationId) {
        return memberships.findByOrganizationIdAndUserAccountId(organizationId, userId)
                .filter(membership -> membership.getStatus() == MembershipStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
    }
}
