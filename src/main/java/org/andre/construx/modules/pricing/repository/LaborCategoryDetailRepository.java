package org.andre.construx.modules.pricing.repository;

import org.andre.construx.modules.pricing.model.LaborCategoryDetail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LaborCategoryDetailRepository extends JpaRepository<LaborCategoryDetail, UUID> {
    Optional<LaborCategoryDetail> findByResourceIdAndOrganizationId(UUID resourceId, UUID organizationId);
}
