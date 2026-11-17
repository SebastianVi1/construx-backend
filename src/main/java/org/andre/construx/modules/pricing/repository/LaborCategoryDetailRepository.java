package org.andre.construx.modules.pricing.repository;

import org.andre.construx.modules.pricing.model.FsrSource;
import org.andre.construx.modules.pricing.model.LaborCategoryDetail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LaborCategoryDetailRepository extends JpaRepository<LaborCategoryDetail, UUID> {
    Optional<LaborCategoryDetail> findByResourceIdAndOrganizationId(UUID resourceId, UUID organizationId);

    /** Every Labor category wage of the organization, archived categories included. */
    List<LaborCategoryDetail> findByOrganizationId(UUID organizationId);

    long countByOrganizationId(UUID organizationId);

    long countByOrganizationIdAndFsrSource(UUID organizationId, FsrSource fsrSource);

    long countByOrganizationIdAndFsrMismatchTrue(UUID organizationId);
}
