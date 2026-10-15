package org.andre.construx.modules.project.repository;

import org.andre.construx.modules.project.model.ProjectAccess;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProjectAccessRepository extends JpaRepository<ProjectAccess, UUID> {
    boolean existsByProjectIdAndOrganizationMembershipId(UUID projectId, UUID membershipId);
}
