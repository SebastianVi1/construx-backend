package org.andre.construx.modules.pricing.repository;

import jakarta.persistence.LockModeType;
import org.andre.construx.modules.pricing.model.CatalogImport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CatalogImportRepository extends JpaRepository<CatalogImport, UUID> {
    Optional<CatalogImport> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<CatalogImport> findByActiveOrganizationId(UUID organizationId);

    Page<CatalogImport> findByOrganizationId(UUID organizationId, Pageable pageable);

    /** Every organization's VALIDATED jobs whose dry run finished before {@code cutoff}. */
    @Query("select i.id from CatalogImport i where i.status = "
            + "org.andre.construx.modules.pricing.model.ImportStatus.VALIDATED and i.validatedAt < :cutoff")
    List<UUID> findValidatedBefore(@Param("cutoff") Instant cutoff);

    /** Every organization's jobs a worker was running ({@code PENDING}, {@code VALIDATING}, {@code IMPORTING}). */
    @Query("select i.id from CatalogImport i where i.status in ("
            + "org.andre.construx.modules.pricing.model.ImportStatus.PENDING, "
            + "org.andre.construx.modules.pricing.model.ImportStatus.VALIDATING, "
            + "org.andre.construx.modules.pricing.model.ImportStatus.IMPORTING)")
    List<UUID> findRunning();

    /** The job locked for a state change, so a cancel and the worker never both win. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from CatalogImport i where i.id = :id")
    Optional<CatalogImport> lock(@Param("id") UUID id);
}
