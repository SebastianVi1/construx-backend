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

    /**
     * The role with a shared lock until the transaction ends: an assignment or invitation into it waits for a
     * delete of the role to finish (and then finds no role), and a delete waits for them.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select r from OrganizationRole r where r.organization.id = :organizationId and r.code = :code")
    Optional<OrganizationRole> findForShare(UUID organizationId, String code);

    /** The role's id, without loading the role: lets a write pick its lock order before locking anything. */
    @Query("select r.id from OrganizationRole r where r.organization.id = :organizationId and r.code = :code")
    Optional<UUID> findIdByCode(UUID organizationId, String code);

    /** {@link #findForShare} by id. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select r from OrganizationRole r where r.id = :id")
    Optional<OrganizationRole> findForShareById(UUID id);

    /** Every role of the company, locked until the transaction ends, taken in id order (see the startup sync). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from OrganizationRole r where r.organization.id = :organizationId order by r.id")
    List<OrganizationRole> findForUpdateByOrganizationId(UUID organizationId);

    /** Every role of the company with its permissions, in one query. */
    @Query("select distinct r from OrganizationRole r left join fetch r.permissions "
            + "where r.organization.id = :organizationId")
    List<OrganizationRole> findWithPermissions(UUID organizationId);

    List<OrganizationRole> findByOrganizationId(UUID organizationId);
}
