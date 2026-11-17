package org.andre.construx.modules.pricing.repository;

import org.andre.construx.modules.pricing.model.Family;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FamilyRepository extends JpaRepository<Family, UUID> {
    @Query("""
            select f from Family f
            where f.organizationId = :organizationId and (:includeArchived = true or f.archivedAt is null)
            order by f.code
            """)
    List<Family> list(@Param("organizationId") UUID organizationId, @Param("includeArchived") boolean includeArchived);

    Optional<Family> findByIdAndOrganizationId(UUID id, UUID organizationId);

    long countByOrganizationIdAndArchivedAtIsNull(UUID organizationId);

    boolean existsByOrganizationIdAndCode(UUID organizationId, String code);
}
