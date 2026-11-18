package org.andre.construx.modules.pricing.repository;

import org.andre.construx.modules.pricing.model.Resource;
import org.andre.construx.modules.pricing.model.ResourceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResourceRepository extends JpaRepository<Resource, UUID> {
    @Query("""
            select r from Resource r join fetch r.unit left join fetch r.family left join fetch r.supplier
            where r.id = :id and r.organizationId = :organizationId
            """)
    Optional<Resource> findInOrganization(@Param("id") UUID id, @Param("organizationId") UUID organizationId);

    @Query("""
            select r from Resource r join fetch r.unit
            where r.id in :ids and r.organizationId = :organizationId
            """)
    List<Resource> findAllInOrganization(@Param("ids") Collection<UUID> ids,
                                         @Param("organizationId") UUID organizationId);

    /**
     * {@code pattern} is a lower-case LIKE pattern escaped with {@code \}, or null for no search.
     * Null filters match everything ({@code missingFromImport} and {@code discontinued} are tri-state).
     */
    @Query(value = """
            select r from Resource r join fetch r.unit left join fetch r.family left join fetch r.supplier
            where r.organizationId = :organizationId
              and r.type in :types
              and (:includeArchived = true or r.archivedAt is null)
              and (:missing is null or (:missing = true and r.missingFromImportAt is not null)
                   or (:missing = false and r.missingFromImportAt is null))
              and (:familyId is null or r.family.id = :familyId)
              and (:unitId is null or r.unit.id = :unitId)
              and (:supplierId is null or r.supplier.id = :supplierId)
              and (:composite is null or r.composite = :composite)
              and (:discontinued is null or r.discontinued = :discontinued)
              and (:pattern is null or lower(r.code) like :pattern escape '\\'
                   or lower(r.name) like :pattern escape '\\'
                   or lower(coalesce(r.description, '')) like :pattern escape '\\')
            """,
            countQuery = """
            select count(r) from Resource r
            where r.organizationId = :organizationId
              and r.type in :types
              and (:includeArchived = true or r.archivedAt is null)
              and (:missing is null or (:missing = true and r.missingFromImportAt is not null)
                   or (:missing = false and r.missingFromImportAt is null))
              and (:familyId is null or r.family.id = :familyId)
              and (:unitId is null or r.unit.id = :unitId)
              and (:supplierId is null or r.supplier.id = :supplierId)
              and (:composite is null or r.composite = :composite)
              and (:discontinued is null or r.discontinued = :discontinued)
              and (:pattern is null or lower(r.code) like :pattern escape '\\'
                   or lower(r.name) like :pattern escape '\\'
                   or lower(coalesce(r.description, '')) like :pattern escape '\\')
            """)
    Page<Resource> search(@Param("organizationId") UUID organizationId,
                          @Param("types") Collection<ResourceType> types,
                          @Param("includeArchived") boolean includeArchived,
                          @Param("missing") Boolean missingFromImport,
                          @Param("familyId") UUID familyId,
                          @Param("unitId") UUID unitId,
                          @Param("supplierId") UUID supplierId,
                          @Param("composite") Boolean composite,
                          @Param("discontinued") Boolean discontinued,
                          @Param("pattern") String pattern,
                          Pageable pageable);

    /**
     * Simple, non-archived Resources of these types, by code or name ({@code pattern} as in {@link #search}, null
     * for no search); {@code withCost} keeps only those whose current cost is above zero.
     */
    @Query(value = """
            select r from Resource r join fetch r.unit
            where r.organizationId = :organizationId
              and r.type in :types
              and r.archivedAt is null
              and r.composite = false
              and (:withCost = false or r.currentCost > 0)
              and (:pattern is null or lower(r.code) like :pattern escape '\\'
                   or lower(r.name) like :pattern escape '\\')
            """,
            countQuery = """
            select count(r) from Resource r
            where r.organizationId = :organizationId
              and r.type in :types
              and r.archivedAt is null
              and r.composite = false
              and (:withCost = false or r.currentCost > 0)
              and (:pattern is null or lower(r.code) like :pattern escape '\\'
                   or lower(r.name) like :pattern escape '\\')
            """)
    Page<Resource> lookup(@Param("organizationId") UUID organizationId,
                          @Param("types") Collection<ResourceType> types,
                          @Param("withCost") boolean withCost,
                          @Param("pattern") String pattern,
                          Pageable pageable);

    boolean existsByOrganizationIdAndCode(UUID organizationId, String code);

    /**
     * What a derivation needs of every Resource of the organization, archived included (they keep deriving
     * their parents' costs): rows of {@code [UUID id, ResourceType type, Boolean composite, Boolean percentage,
     * BigDecimal currentCost]}.
     */
    @Query("""
            select r.id, r.type, r.composite, r.percentage, r.currentCost from Resource r
            where r.organizationId = :organizationId
            """)
    List<Object[]> derivationInputs(@Param("organizationId") UUID organizationId);

    /** The composite Resources of the organization, archived included. */
    List<Resource> findByOrganizationIdAndCompositeTrue(UUID organizationId);

    /** Non-archived Resources per type: rows of {@code [ResourceType, Long]}. */
    @Query("""
            select r.type, count(r) from Resource r
            where r.organizationId = :organizationId and r.archivedAt is null
            group by r.type
            """)
    List<Object[]> countByType(@Param("organizationId") UUID organizationId);

    @Query("""
            select count(r) from Resource r
            where r.organizationId = :organizationId and r.archivedAt is null and r.missingFromImportAt is not null
            """)
    long countMissingFromImport(@Param("organizationId") UUID organizationId);

    /** Resources per Unit, archived included: rows of {@code [UUID, Long]}. */
    @Query("select r.unit.id, count(r) from Resource r where r.organizationId = :organizationId group by r.unit.id")
    List<Object[]> countByUnit(@Param("organizationId") UUID organizationId);

    /** Resources per Family, archived included: rows of {@code [UUID, Long]}. */
    @Query("""
            select r.family.id, count(r) from Resource r
            where r.organizationId = :organizationId and r.family is not null
            group by r.family.id
            """)
    List<Object[]> countByFamily(@Param("organizationId") UUID organizationId);

    /** Resources per Supplier, archived included: rows of {@code [UUID, Long]}. */
    @Query("""
            select r.supplier.id, count(r) from Resource r
            where r.organizationId = :organizationId and r.supplier is not null
            group by r.supplier.id
            """)
    List<Object[]> countBySupplier(@Param("organizationId") UUID organizationId);

    long countByOrganizationIdAndSupplierId(UUID organizationId, UUID supplierId);

    long countByOrganizationIdAndUnitId(UUID organizationId, UUID unitId);

    long countByOrganizationIdAndFamilyId(UUID organizationId, UUID familyId);
}
