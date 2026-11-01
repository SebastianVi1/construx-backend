package org.andre.construx.modules.plan.repository;

import org.andre.construx.modules.plan.model.PlanFolder;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanFolderRepository extends JpaRepository<PlanFolder, UUID> {
    List<PlanFolder> findByOrganizationIdOrderByName(UUID organizationId);
    Optional<PlanFolder> findByIdAndOrganizationId(UUID id, UUID organizationId);
    List<PlanFolder> findByParentId(UUID parentId);
}
