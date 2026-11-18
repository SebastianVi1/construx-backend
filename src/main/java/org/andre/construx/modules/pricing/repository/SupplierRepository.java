package org.andre.construx.modules.pricing.repository;

import org.andre.construx.modules.pricing.model.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupplierRepository extends JpaRepository<Supplier, UUID> {
    Optional<Supplier> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<Supplier> findByOrganizationIdAndNormalizedKey(UUID organizationId, String normalizedKey);

    long countByOrganizationIdAndArchivedAtIsNull(UUID organizationId);

    Optional<Supplier> findByOrganizationIdAndCode(UUID organizationId, String code);

    /** A null pattern matches everything; otherwise a lower-case LIKE over name, code and contact. */
    @Query("""
            select s from Supplier s
            where s.organization.id = :organizationId
              and (:includeArchived = true or s.archivedAt is null)
              and (:pattern is null or lower(s.name) like :pattern escape '\\'
                   or lower(coalesce(s.code, '')) like :pattern escape '\\'
                   or lower(coalesce(s.contact, '')) like :pattern escape '\\')
            order by lower(s.name), lower(coalesce(s.branch, ''))
            """)
    List<Supplier> list(@Param("organizationId") UUID organizationId,
                        @Param("includeArchived") boolean includeArchived, @Param("pattern") String pattern);
}
