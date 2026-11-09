package org.andre.construx.modules.organization.service;

import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * How other modules look up memberships of an organization (to assign people to sites, crews, zones) without
 * reading the membership repository. No permission check: callers authorize with their scope first.
 */
@Service
public class MembershipDirectory {
    private final OrganizationMembershipRepository memberships;

    public MembershipDirectory(OrganizationMembershipRepository memberships) {
        this.memberships = memberships;
    }

    /** A membership of this organization, in any status; empty for a foreign or unknown id. */
    @Transactional(readOnly = true)
    public Optional<OrganizationMembership> find(UUID organizationId, UUID membershipId) {
        return memberships.findByIdAndOrganizationId(membershipId, organizationId);
    }

    /** Memberships by id, in one query; unknown ids are absent. */
    @Transactional(readOnly = true)
    public List<OrganizationMembership> byIds(Collection<UUID> membershipIds) {
        return memberships.findAllById(membershipIds);
    }
}
