package org.andre.construx.modules.pricing.repository;

import org.andre.construx.modules.pricing.model.CatalogImportUpload;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CatalogImportUploadRepository extends JpaRepository<CatalogImportUpload, UUID> {
    Optional<CatalogImportUpload> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
