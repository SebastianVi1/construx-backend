package org.andre.construx.modules.pricing.repository;

import org.andre.construx.modules.pricing.model.Catalog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CatalogRepository extends JpaRepository<Catalog, UUID> {
    Optional<Catalog> findByOrganizationId(UUID organizationId);
}
