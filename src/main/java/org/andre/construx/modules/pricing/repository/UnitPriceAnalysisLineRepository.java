package org.andre.construx.modules.pricing.repository;

import org.andre.construx.modules.pricing.model.Resource;
import org.andre.construx.modules.pricing.model.UnitPriceAnalysisLine;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface UnitPriceAnalysisLineRepository extends JpaRepository<UnitPriceAnalysisLine, UUID> {
    /** Every analysis line of the organization, for a full derivation. */
    List<UnitPriceAnalysisLine> findByOrganizationId(UUID organizationId);

    /** The lines of the given parents with their components and units, for one analysis and its subtree. */
    @Query("""
            select l from UnitPriceAnalysisLine l join fetch l.component c join fetch c.unit
            where l.organizationId = :organizationId and l.parentResourceId in :parentIds
            """)
    List<UnitPriceAnalysisLine> findByParents(@Param("organizationId") UUID organizationId,
                                              @Param("parentIds") Collection<UUID> parentIds);

    /** How many composites use the Resource in their analysis. */
    @Query("""
            select count(distinct l.parentResourceId) from UnitPriceAnalysisLine l
            where l.organizationId = :organizationId and l.component.id = :componentId
            """)
    long countParents(@Param("organizationId") UUID organizationId, @Param("componentId") UUID componentId);

    /** Composites that use the Resource, by code. */
    @Query("""
            select r from Resource r
            where r.organizationId = :organizationId and r.id in (
                select l.parentResourceId from UnitPriceAnalysisLine l
                where l.organizationId = :organizationId and l.component.id = :componentId)
            order by r.code
            """)
    List<Resource> findParents(@Param("organizationId") UUID organizationId,
                               @Param("componentId") UUID componentId, Pageable pageable);
}
