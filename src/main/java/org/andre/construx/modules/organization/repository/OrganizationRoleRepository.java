package org.andre.construx.modules.organization.repository;

import jakarta.persistence.LockModeType;
import org.andre.construx.modules.organization.model.OrganizationRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationRoleRepository extends JpaRepository<OrganizationRole, UUID> {
    Optional<OrganizationRole> findByOrganizationIdAndCode(UUID organizationId, String code);

    /** The role with its row locked until the transaction ends, so two writes on it run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from OrganizationRole r where r.organization.id = :organizationId and r.code = :code")
    Optional<OrganizationRole> findForUpdate(UUID organizationId, String code);

    /** Every role of the company with its permissions, in one query. */
    @Query("select distinct r from OrganizationRole r left join fetch r.permissions "
            + "where r.organization.id = :organizationId")
    List<OrganizationRole> findWithPermissions(UUID organizationId);

    List<OrganizationRole> findByOrganizationId(UUID organizationId);
}
