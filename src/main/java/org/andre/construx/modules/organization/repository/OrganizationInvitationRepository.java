package org.andre.construx.modules.organization.repository;

import org.andre.construx.modules.organization.model.InvitationStatus;
import org.andre.construx.modules.organization.model.OrganizationInvitation;
import org.andre.construx.modules.organization.model.OrganizationRole;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationInvitationRepository extends JpaRepository<OrganizationInvitation, UUID> {
    @Query("select i from OrganizationInvitation i join fetch i.role left join fetch i.project "
            + "where i.organization.id = :organizationId and i.status = :status order by i.expiresAt desc")
    List<OrganizationInvitation> findByOrganizationAndStatus(UUID organizationId, InvitationStatus status);

    /** Invitations per role in the given status. */
    @Query("select i.role.id as roleId, count(i) as count from OrganizationInvitation i "
            + "where i.organization.id = :organizationId and i.status = :status group by i.role.id")
    List<RoleCount> countByRole(UUID organizationId, InvitationStatus status);

    long countByRoleId(UUID roleId);

    long countByRoleIdAndStatus(UUID roleId, InvitationStatus status);

    /** Moves every invitation of {@code from}, whatever its status, to {@code to}; returns how many moved. */
    @Modifying(flushAutomatically = true)
    @Query("update OrganizationInvitation i set i.role = :to where i.role = :from")
    int reassignRole(OrganizationRole from, OrganizationRole to);

    List<OrganizationInvitation> findByOrganizationIdAndEmailAndStatus(UUID organizationId, String email,
                                                                       InvitationStatus status);

    Optional<OrganizationInvitation> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** Row lock so two simultaneous accepts of one token cannot both create a membership. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from OrganizationInvitation i where i.tokenHash = :tokenHash")
    Optional<OrganizationInvitation> findByTokenHashForUpdate(String tokenHash);
}
