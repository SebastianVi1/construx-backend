package org.andre.construx.modules.organization.repository;

import org.andre.construx.modules.organization.model.OrganizationRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationRoleRepository extends JpaRepository<OrganizationRole, UUID> {
    Optional<OrganizationRole> findByOrganizationIdAndCode(UUID organizationId, String code);

    /** Every role of the company with its permissions, in one query. */
    @Query("select distinct r from OrganizationRole r left join fetch r.permissions "
            + "where r.organization.id = :organizationId")
    List<OrganizationRole> findWithPermissions(UUID organizationId);

    List<OrganizationRole> findByOrganizationId(UUID organizationId);
}
