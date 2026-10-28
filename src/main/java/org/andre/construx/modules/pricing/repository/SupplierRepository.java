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

    @Query("""
            select s from Supplier s
            where s.organization.id = :organizationId
              and (:includeArchived = true or s.archivedAt is null)
            order by lower(s.name), lower(coalesce(s.branch, ''))
            """)
    List<Supplier> list(@Param("organizationId") UUID organizationId,
                        @Param("includeArchived") boolean includeArchived);
}
