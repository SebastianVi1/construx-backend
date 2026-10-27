package org.andre.construx.modules.project.repository;

import org.andre.construx.modules.project.model.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
    Optional<Project> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
