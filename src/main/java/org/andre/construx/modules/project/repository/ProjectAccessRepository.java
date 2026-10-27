package org.andre.construx.modules.project.repository;

import org.andre.construx.modules.project.model.ProjectAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ProjectAccessRepository extends JpaRepository<ProjectAccess, UUID> {
    boolean existsByProjectIdAndOrganizationMembershipId(UUID projectId, UUID membershipId);

    @Query("select a.project.id from ProjectAccess a where a.organizationMembership.id = :membershipId")
    Set<UUID> findProjectIdsByMembership(UUID membershipId);

    @Query("select a from ProjectAccess a join fetch a.organizationMembership m join fetch m.userAccount "
            + "join fetch m.role where a.project.id = :projectId "
            + "and m.status = org.andre.construx.modules.organization.model.MembershipStatus.ACTIVE")
    List<ProjectAccess> findActiveByProject(UUID projectId);

    Optional<ProjectAccess> findByIdAndProjectId(UUID id, UUID projectId);
}
