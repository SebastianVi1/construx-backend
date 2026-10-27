package org.andre.construx.modules.project.repository;

import org.andre.construx.modules.project.model.ProjectAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Set;
import java.util.UUID;

public interface ProjectAccessRepository extends JpaRepository<ProjectAccess, UUID> {
    boolean existsByProjectIdAndOrganizationMembershipId(UUID projectId, UUID membershipId);

    @Query("select a.project.id from ProjectAccess a where a.organizationMembership.id = :membershipId")
    Set<UUID> findProjectIdsByMembership(UUID membershipId);
}
