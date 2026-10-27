package org.andre.construx.modules.project.repository;

import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.project.model.ProjectStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
    Optional<Project> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Query("select p from Project p where p.organization.id = :organizationId and p.status in :statuses order by p.name")
    List<Project> findInOrganization(UUID organizationId, Collection<ProjectStatus> statuses);

    /** Only sites the membership has a {@code ProjectAccess} to. */
    @Query("select p from Project p where p.organization.id = :organizationId and p.status in :statuses "
            + "and exists (select a.id from ProjectAccess a where a.project = p and a.organizationMembership.id = :membershipId) "
            + "order by p.name")
    List<Project> findAccessibleInOrganization(UUID organizationId, Collection<ProjectStatus> statuses, UUID membershipId);
}
