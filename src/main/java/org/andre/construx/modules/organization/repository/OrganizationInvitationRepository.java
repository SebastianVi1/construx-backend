package org.andre.construx.modules.organization.repository;

import org.andre.construx.modules.organization.model.InvitationStatus;
import org.andre.construx.modules.organization.model.OrganizationInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationInvitationRepository extends JpaRepository<OrganizationInvitation, UUID> {
    @Query("select i from OrganizationInvitation i join fetch i.role left join fetch i.project "
            + "where i.organization.id = :organizationId and i.status = :status order by i.expiresAt desc")
    List<OrganizationInvitation> findByOrganizationAndStatus(UUID organizationId, InvitationStatus status);

    List<OrganizationInvitation> findByOrganizationIdAndEmailAndStatus(UUID organizationId, String email,
                                                                       InvitationStatus status);

    Optional<OrganizationInvitation> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
