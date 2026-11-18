package org.andre.construx.modules.pricing.repository;

import org.andre.construx.modules.pricing.model.Resource;
import org.andre.construx.modules.pricing.model.ResourceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResourceRepository extends JpaRepository<Resource, UUID>, JpaSpecificationExecutor<Resource> {
    /** Number of Resources of one type, from a grouped count. */
    interface CountByType {
        ResourceType getType();

        long getTotal();
    }

    /** Callers always include an organization filter in {@code spec}. */
    @Override
    @EntityGraph(attributePaths = {"unit", "family", "supplier"})
    Page<Resource> findAll(Specification<Resource> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"unit", "family", "supplier"})
    Optional<Resource> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Query("""
            select r.type as type, count(r) as total from Resource r
            where r.organization.id = :organizationId and r.archivedAt is null
            group by r.type
            """)
    List<CountByType> countActiveByType(@Param("organizationId") UUID organizationId);

    long countByOrganizationIdAndMissingFromImportAtIsNotNullAndArchivedAtIsNull(UUID organizationId);

    @Query("""
            select r.unit.id as id, count(r) as total from Resource r
            where r.organization.id = :organizationId
            group by r.unit.id
            """)
    List<CountById> countByUnit(@Param("organizationId") UUID organizationId);

    @Query("""
            select r.family.id as id, count(r) as total from Resource r
            where r.organization.id = :organizationId and r.family is not null
            group by r.family.id
            """)
    List<CountById> countByFamily(@Param("organizationId") UUID organizationId);
}
