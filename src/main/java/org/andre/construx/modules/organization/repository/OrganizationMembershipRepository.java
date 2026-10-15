package org.andre.construx.modules.organization.repository;

import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationMembershipRepository extends JpaRepository<OrganizationMembership, UUID> {
    Optional<OrganizationMembership> findByOrganizationIdAndUserAccountId(UUID organizationId, UUID userId);
}
