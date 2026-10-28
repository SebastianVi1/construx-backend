package org.andre.construx.modules.pricing.repository;

import org.andre.construx.modules.pricing.model.Material;
import org.andre.construx.modules.pricing.model.MaterialCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MaterialRepository extends JpaRepository<Material, UUID> {
    Optional<Material> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<Material> findByOrganizationIdAndNormalizedKey(UUID organizationId, String normalizedKey);

    /** {@code pattern} is a lower-case LIKE pattern escaped with {@code \}, or null for no search. */
    @Query("""
            select m from Material m
            where m.organization.id = :organizationId
              and (:includeArchived = true or m.archivedAt is null)
              and (:category is null or m.category = :category)
              and (:pattern is null or lower(m.name) like :pattern escape '\\'
                   or lower(coalesce(m.brand, '')) like :pattern escape '\\')
            order by lower(m.name), m.unit, lower(coalesce(m.brand, ''))
            """)
    List<Material> search(@Param("organizationId") UUID organizationId,
                          @Param("includeArchived") boolean includeArchived,
                          @Param("category") MaterialCategory category,
                          @Param("pattern") String pattern);
}
