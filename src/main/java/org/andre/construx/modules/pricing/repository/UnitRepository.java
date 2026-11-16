package org.andre.construx.modules.pricing.repository;

import org.andre.construx.modules.pricing.model.Unit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UnitRepository extends JpaRepository<Unit, UUID> {
    @Query("""
            select u from Unit u
            where u.organizationId = :organizationId and (:includeArchived = true or u.archivedAt is null)
            order by u.code
            """)
    List<Unit> list(@Param("organizationId") UUID organizationId, @Param("includeArchived") boolean includeArchived);

    long countByOrganizationIdAndArchivedAtIsNull(UUID organizationId);

    Optional<Unit> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByOrganizationIdAndCode(UUID organizationId, String code);
}
