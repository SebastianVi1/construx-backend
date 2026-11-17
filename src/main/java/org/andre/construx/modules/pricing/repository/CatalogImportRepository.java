package org.andre.construx.modules.pricing.repository;

import jakarta.persistence.LockModeType;
import org.andre.construx.modules.pricing.model.CatalogImport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CatalogImportRepository extends JpaRepository<CatalogImport, UUID> {
    Optional<CatalogImport> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<CatalogImport> findByActiveOrganizationId(UUID organizationId);

    /** The job locked for a state change, so a cancel and the worker never both win. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from CatalogImport i where i.id = :id")
    Optional<CatalogImport> lock(@Param("id") UUID id);
}
