package org.andre.construx.modules.organization.repository;

import org.andre.construx.modules.organization.model.MembershipStatus;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationMembershipRepository extends JpaRepository<OrganizationMembership, UUID> {
    Optional<OrganizationMembership> findByOrganizationIdAndUserAccountId(UUID organizationId, UUID userId);

    boolean existsByOrganizationIdAndUserAccountEmail(UUID organizationId, String email);

    @Query("select m from OrganizationMembership m join fetch m.organization "
            + "where m.userAccount.id = :userId and m.status = :status order by m.organization.name")
    List<OrganizationMembership> findByUserWithOrganization(UUID userId, MembershipStatus status);
}
